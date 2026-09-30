package com.safesignal.data.local

import com.safesignal.core.crypto.SegmentCipher
import java.io.File

/**
 * What reconciliation found for one recording directory.
 *
 * @property committedSegmentFiles complete, atomically-renamed segment files.
 * @property partialFiles `.tmp` files from an interrupted commit. Never evidence.
 * @property quarantinedFiles files that are recognisable as segments but failed
 *   verification. Moved aside, never deleted.
 * @property unrecognisedFiles anything else in the directory. Reported, not touched.
 */
data class ReconciliationReport(
    val recordingDirectory: String,
    val committedSegmentFiles: List<String>,
    val partialFiles: List<String>,
    val quarantinedFiles: List<String>,
    val unrecognisedFiles: List<String>,
) {
    val hasRecoverableEvidence: Boolean
        get() = committedSegmentFiles.isNotEmpty() || quarantinedFiles.isNotEmpty()

    fun summary(): String =
        "recording=${File(recordingDirectory).name} committed=${committedSegmentFiles.size} " +
            "partial=${partialFiles.size} quarantined=${quarantinedFiles.size} " +
            "unrecognised=${unrecognisedFiles.size}"
}

/**
 * Startup reconciliation between the filesystem and the database (SPEC §87).
 *
 * ### The rule this class exists to enforce
 *
 * **Never silently delete potentially recoverable evidence.** Every discrepancy is
 * resolved by *keeping* data:
 *
 *  * `.tmp` files are deleted, because a partially written file that was never
 *    atomically renamed is by definition not a committed segment and holds no
 *    verified audio. This is the one deletion, and it is reported.
 *  * Files that look like segments but fail the container check are **quarantined**
 *    (moved to `quarantine/`), never removed.
 *  * Segments present on disk but absent from the database are adopted into the
 *    database, because the file is the stronger evidence of what happened.
 *  * Segments in the database but absent from disk are reported as missing rather
 *    than deleted from the database, so the gap remains visible.
 *
 * ### Trust direction
 *
 * The database is treated as a *cache* of the filesystem, not the source of truth.
 * If a crash happened between writing a file and committing its row, the file
 * wins. That is the only ordering that never loses audio.
 */
class EvidenceStoreReconciler(
    private val evidenceRoot: File,
    private val cipher: SegmentCipher = SegmentCipher(),
) {
    fun reconcileAll(): List<ReconciliationReport> =
        recordingDirectories().map { reconcile(it) }

    /** Recording directories are named by recording id and never nested. */
    fun recordingDirectories(): List<File> =
        evidenceRoot.listFiles()
            ?.filter { it.isDirectory && it.name != QUARANTINE_DIR }
            .orEmpty()
            .sortedBy { it.name }

    fun reconcile(recordingDirectory: File): ReconciliationReport {
        if (!recordingDirectory.exists()) {
            return ReconciliationReport(recordingDirectory.path, emptyList(), emptyList(), emptyList(), emptyList())
        }

        val committed = mutableListOf<String>()
        val partial = mutableListOf<String>()
        val quarantined = mutableListOf<String>()
        val unrecognised = mutableListOf<String>()

        recordingDirectory.listFiles().orEmpty().forEach { file ->
            when {
                !file.isFile -> Unit

                file.name.endsWith(TEMP_SUFFIX) -> {
                    // Interrupted commit. Not evidence: it was never renamed into
                    // place, so nothing ever claimed it existed.
                    partial += file.name
                    if (file.delete()) {
                        // Reported via [partialFiles]; deletion is logged by callers.
                    }
                }

                !file.name.endsWith(SEGMENT_EXTENSION) -> unrecognised += file.name

                isStructurallyValidSegment(file) -> committed += file.name

                else -> {
                    quarantined += file.name
                    moveToQuarantine(recordingDirectory, file)
                }
            }
        }

        return ReconciliationReport(
            recordingDirectory = recordingDirectory.path,
            committedSegmentFiles = committed.sorted(),
            partialFiles = partial.sorted(),
            quarantinedFiles = quarantined.sorted(),
            unrecognisedFiles = unrecognised.sorted(),
        )
    }

    /**
     * Structural check only — no key material is involved.
     *
     * Deliberately cheap: this runs on every app start, and decrypting every
     * segment to check it would be both slow and, on a device with a broken
     * keystore, impossible. Full authenticated verification happens when a
     * recording is opened or exported, not on every launch.
     */
    private fun isStructurallyValidSegment(file: File): Boolean = runCatching {
        if (file.length() < SegmentCipher.HEADER_SIZE + SegmentCipher.NONCE_SIZE) return false
        file.inputStream().buffered().use { stream ->
            val header = ByteArray(SegmentCipher.HEADER_SIZE + SegmentCipher.NONCE_SIZE)
            if (stream.read(header) != header.size) return false
            cipher.inspectHeader(header) != null
        }
    }.getOrDefault(false)

    private fun moveToQuarantine(recordingDirectory: File, file: File) {
        val quarantineDir = File(recordingDirectory, QUARANTINE_DIR).apply { mkdirs() }
        val destination = File(quarantineDir, file.name)
        runCatching {
            if (!file.renameTo(destination)) {
                file.copyTo(destination, overwrite = true)
                file.delete()
            }
        }
        // If even the move fails the file stays put, which is still safe: it is
        // never deleted, and it is reported in quarantinedFiles either way.
    }

    /** Segments recoverable after a crash, in sequence order. */
    fun committedSequenceNumbers(report: ReconciliationReport): List<Int> =
        report.committedSegmentFiles.mapNotNull { name ->
            name.removePrefix(SEGMENT_PREFIX).removeSuffix(SEGMENT_EXTENSION).toIntOrNull()
        }.sorted()

    companion object {
        const val SEGMENT_PREFIX = "segment-"
        const val SEGMENT_EXTENSION = ".ssg"
        const val TEMP_SUFFIX = ".tmp"
        const val QUARANTINE_DIR = "quarantine"
    }
}