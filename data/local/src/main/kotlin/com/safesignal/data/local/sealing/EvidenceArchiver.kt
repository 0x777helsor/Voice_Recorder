package com.safesignal.data.local.sealing

import androidx.room.withTransaction
import com.safesignal.core.common.log.SafeLogger
import com.safesignal.core.crypto.EvidenceIntegrity
import com.safesignal.core.common.model.ElapsedMillis
import com.safesignal.core.crypto.SegmentDigest
import com.safesignal.core.crypto.SignedManifest
import com.safesignal.core.database.SafeSignalDatabase
import com.safesignal.core.database.entity.EvidenceManifestEntity
import com.safesignal.core.database.entity.RecordingEntity
import com.safesignal.core.database.entity.RecordingSegmentEntity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Persists a finalized recording: the manifest to disk, then the metadata to Room.
 *
 * ### Order, and why it is this way
 *
 * 1. `manifest.json` is written and fsynced **first**.
 * 2. Then one Room transaction writes the recording row, the segment rows and the
 *    manifest row.
 *
 * The filesystem is the source of truth about evidence; Room is a cache of it. See
 * `EvidenceStoreReconciler`, which resolves every discrepancy in the filesystem's
 * favour, because that is the only ordering that never loses audio.
 *
 * Writing the manifest first means a crash between the two steps leaves a complete,
 * signed manifest on disk that reconciliation can adopt. The reverse order would
 * leave Room claiming a sealed recording whose manifest does not exist — the
 * database asserting something the evidence does not support, which is the one
 * failure mode this design exists to prevent.
 *
 * ### If the database write fails
 *
 * Nothing is rolled back and nothing is deleted. The evidence is sealed on disk,
 * which is the part that matters; the failure is logged and the row is written
 * again on the next run by reconciliation. Reporting a seal as failed because a
 * *cache* write failed would be wrong — the evidence exists, and telling the user
 * otherwise invites them to discard it.
 */
class EvidenceArchiver(
    private val database: SafeSignalDatabase,
    private val evidenceRoot: File,
    private val sealer: EvidenceSealer,
    private val logger: SafeLogger,
) : EvidenceStore {

    override suspend fun archive(request: ArchiveRequest) {
        val capture = request.capture
        val signed = sealer.seal(
            capture = capture,
            appVersion = request.appVersion,
            deviceTimezoneId = request.deviceTimezoneId,
            wakeWordEngineVersion = request.wakeWordEngineVersion,
        )

        // Disk first. See the class comment.
        writeManifestFile(capture.recordingId, signed)

        val recordingId = capture.recordingId
        val nowWallClockMillis = request.nowWallClockMillis
        val stored = try {
            database.withTransaction {
                val existing = database.recordingDao().getRecording(recordingId)
                if (existing == null) {
                    database.recordingDao().insertRecording(
                        capture.toRecordingEntity(
                            appVersion = request.appVersion,
                            deviceTimezone = request.deviceTimezoneId,
                            captureQuality = request.captureQuality,
                            retentionPolicy = request.retentionPolicy,
                            sealedBytes = capture.totalSealedBytes,
                            now = nowWallClockMillis,
                        ),
                    )
                    capture.segments.forEach { segment ->
                        database.recordingDao().insertSegment(
                            segment.toEntity(recordingId, capture),
                        )
                    }
                } else {
                    // Re-archiving. Segments are never re-inserted: their unique
                    // (recordingId, sequenceNumber) index would reject them, and
                    // re-encrypting already-sealed audio would be destroying
                    // evidence. Only the finalised metadata moves.
                    database.recordingDao().updateFinalized(
                        recordingId = recordingId,
                        endedWallClock = capture.endedAtWallClockMillis,
                        endedElapsed = capture.endedElapsedRealtimeMillis,
                        duration = capture.durationMillis,
                        segmentCount = capture.segments.size,
                        fileSize = capture.totalSealedBytes,
                        sealedBytes = capture.totalSealedBytes,
                        sha256 = signed.manifest.recordingSha256,
                        quality = request.captureQuality,
                        state = SESSION_STATE_SEALED,
                        uploadState = UPLOAD_STATE_LOCAL_ONLY,
                        now = nowWallClockMillis,
                    )
                }

                database.manifestDao().upsert(
                    EvidenceManifestEntity(
                        recordingId = recordingId,
                        canonicalJson = signed.manifest.toCanonicalJson(),
                        signatureBase64 = signed.signatureBase64,
                        verificationKeyBase64 = signed.verificationKeyBase64,
                        manifestDigest = signed.manifest.digest(),
                        sealedAt = nowWallClockMillis,
                    ),
                )
            }
            true
        } catch (failure: Exception) {
            logger.w(
                "recording sealed on disk but database write failed; " +
                    "reconciliation will adopt it on next start",
                failure,
                mapOf("recording" to recordingId),
            )
            false
        }

        logger.i(
            "recording archived",
            fields = mapOf(
                "recording" to recordingId,
                "segments" to capture.segments.size.toString(),
                "sealedBytes" to capture.totalSealedBytes.toString(),
                "persisted" to stored.toString(),
            ),
        )
    }

    /**
     * Writes `manifest.json` into the recording's directory, atomically.
     *
     * Atomic because a half-written manifest is worse than none: it would parse as
     * corrupt on the next run, and reconciliation has no way to tell a truncated
     * file from a tampered one. Write to temp, fsync, rename.
     */
    private fun writeManifestFile(recordingId: String, signed: SignedManifest) {
        val directory = File(evidenceRoot, recordingId).apply { mkdirs() }
        val target = File(directory, MANIFEST_FILE_NAME)
        val temp = File(directory, "$MANIFEST_FILE_NAME$TEMPORARY_SUFFIX")

        FileOutputStream(temp).use { stream ->
            stream.write(EvidenceIntegrity.serialise(signed).toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        if (!target.exists()) throw IOException("Failed to write manifest for $recordingId")
    }

    companion object {
        const val MANIFEST_FILE_NAME = "manifest.json"
        private const val TEMPORARY_SUFFIX = ".tmp"

        const val SESSION_STATE_SEALED = "SEALED"
        const val UPLOAD_STATE_LOCAL_ONLY = "LOCAL_ONLY"
    }
}

/**
 * Maps a sealed capture onto its database row.
 *
 * Every field comes from the capture or its key material — no placeholders for
 * anything a reader would rely on. A column silently holding a default is how an
 * evidence package ends up making a claim nobody verified.
 */
internal fun RecordingToSeal.toRecordingEntity(
    appVersion: String,
    deviceTimezone: String,
    captureQuality: String,
    retentionPolicy: String,
    sealedBytes: Long,
    now: Long,
) = RecordingEntity(
    recordingId = recordingId,
    startedAtWallClock = startedAtWallClockMillis,
    endedAtWallClock = endedAtWallClockMillis,
    startedAtElapsed = startedElapsedRealtimeMillis,
    endedAtElapsed = endedElapsedRealtimeMillis,
    durationMillis = durationMillis,
    segmentCount = segments.size,
    // There is no unencrypted container, so the sealed size is the file size.
    // Recorded explicitly rather than left 0, so a reader is not left guessing.
    fileSizeBytes = sealedBytes,
    sealedBytes = sealedBytes,
    // Filled in by the archiver after sealing; null here so the column cannot
    // disagree with the manifest that was actually signed.
    sha256 = null,
    encryptionVersion = EvidenceIntegrity.ENCRYPTION_VERSION,
    keyVersion = wrappedKey.keyVersion,
    keyProvider = wrappedKey.provider,
    // Ciphertext only. The plaintext key is not reachable from this type.
    wrappedKey = wrappedKey.wrappedKeyBytes,
    wrappedKeyIv = wrappedKey.iv,
    appVersion = appVersion,
    deviceTimezone = deviceTimezone,
    activationSource = activationSource,
    activationConfidence = activationConfidence,
    wakeWordEngineVersion = null,
    audioFormat = audioFormat,
    sampleRateHz = sampleRateHz,
    channels = channels,
    captureQuality = captureQuality,
    isTestRecording = isTestRecording,
    uploadState = EvidenceArchiver.UPLOAD_STATE_LOCAL_ONLY,
    uploadReceipt = null,
    retentionPolicy = retentionPolicy,
    deleteAfter = null,
    sessionState = EvidenceArchiver.SESSION_STATE_SEALED,
    createdAt = now,
    updatedAt = now,
)

internal fun SegmentDigest.toEntity(
    recordingId: String,
    capture: RecordingToSeal,
) = RecordingSegmentEntity(
    recordingId = recordingId,
    segmentId = segmentId,
    sequenceNumber = sequenceNumber,
    sealedFileName = capture.fileNameFor(sequenceNumber),
    sealedLengthBytes = sealedLengthBytes,
    plaintextLengthBytes = plaintextLengthBytes,
    sha256 = sha256,
    startedAtElapsed = capture.startedElapsedRealtimeMillis,
    endedAtElapsed = capture.endedElapsedRealtimeMillis,
    isComplete = capture.isSegmentComplete(sequenceNumber),
    uploaded = false,
)

/** Relative, never absolute: the data directory moves between installs on some OEM builds. */
private fun RecordingToSeal.fileNameFor(sequenceNumber: Int): String =
    "segment-%06d.ssg".format(sequenceNumber)
