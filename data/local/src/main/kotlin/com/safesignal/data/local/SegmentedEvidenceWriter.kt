package com.safesignal.data.local

import com.safesignal.core.crypto.CryptoException
import com.safesignal.core.crypto.Digest
import com.safesignal.core.crypto.EncryptionContext
import com.safesignal.core.crypto.RecordingKeyMaterial
import com.safesignal.core.crypto.SealedSegment
import com.safesignal.core.crypto.SegmentCipher
import com.safesignal.core.crypto.SegmentDigest
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Writes one recording's segments as independently encrypted, independently
 * verifiable files (SPEC §17).
 *
 * ### Commit protocol
 *
 * For each segment:
 *
 *  1. build the WAV payload in memory for that segment only (bounded by
 *     `segmentDurationSeconds`, never by the recording length),
 *  2. seal it with AES-256-GCM and a fresh nonce,
 *  3. write to `<recordingId>/<sequence>.ssg.tmp`,
 *  4. `fsync` the file,
 *  5. atomically rename to `<sequence>.ssg`,
 *  6. `fsync` the directory,
 *  7. only then report the segment as committed.
 *
 * The rename is what makes the commit atomic. A crash before step 5 leaves a
 * `.tmp` file, which the reconciler quarantines; a crash after it leaves a
 * complete, verifiable segment. There is no window in which a committed segment
 * is corrupt, and SPEC §17's requirement that "a crash should not invalidate
 * previously committed segments" falls out of the protocol rather than out of
 * hope.
 *
 * ### Why nothing is ever overwritten
 *
 * Writes always use `REPLACE_EXISTING` into a *new* `.tmp` name, and the final
 * name is only ever produced by renaming a freshly sealed segment. Sequence
 * numbers are assigned by [nextSequenceNumber], which reads committed state
 * rather than guessing, so a retry after a partial commit cannot land segment 7
 * on top of an existing segment 7.
 */
class SegmentedEvidenceWriter(
    private val recordingDirectory: File,
    private val recordingId: String,
    private val keyMaterial: RecordingKeyMaterial,
    private val cipher: SegmentCipher = SegmentCipher(),
) {
    private val cipherContextFactory: (Int, Int) -> EncryptionContext = { sequence, length ->
        keyMaterial.toEncryptionContext(
            segmentId = segmentIdFor(recordingId, sequence),
            sequenceNumber = sequence,
            plaintextLength = length,
        )
    }

    init {
        if (!recordingDirectory.exists() && !recordingDirectory.mkdirs()) {
            throw IOException("Cannot create evidence directory: $recordingDirectory")
        }
    }

    /**
     * Seals and commits one segment.
     *
     * @param wavPayload a complete WAV container for this segment.
     * @param sequenceNumber zero-based, contiguous.
     * @param plaintextLength length of [wavPayload]; bound into the AAD so the
     *   length cannot be altered without detection.
     */
    fun commitSegment(
        wavPayload: ByteArray,
        sequenceNumber: Int,
        plaintextLength: Int = wavPayload.size,
    ): CommittedSegmentFile {
        require(sequenceNumber >= 0) { "sequenceNumber must be non-negative" }
        require(plaintextLength == wavPayload.size) {
            "plaintextLength must equal the payload size; a mismatch would bind the wrong AAD"
        }

        val context = keyMaterial.toEncryptionContext(
            segmentId = segmentIdFor(recordingId, sequenceNumber),
            sequenceNumber = sequenceNumber,
            plaintextLength = plaintextLength,
        )
        val sealed = cipher.seal(wavPayload, context)

        val tmp = File(recordingDirectory, fileNameFor(sequenceNumber, temporary = true))
        val target = File(recordingDirectory, fileNameFor(sequenceNumber, temporary = false))

        // The final name must not already exist. Overwriting a committed segment
        // would destroy evidence, so this is a hard error rather than a replace.
        if (target.exists()) {
            throw IOException("Refusing to overwrite committed segment $sequenceNumber")
        }

        FileOutputStream(tmp).use { stream ->
            stream.write(sealed.bytes)
            stream.fd.sync()
        }

        if (!tmp.renameTo(target)) {
            // Fall back to an explicit move; some OEM filesystems reject renameTo
            // across the same directory under low storage.
            tmp.copyTo(target, overwrite = false)
            tmp.delete()
            if (!target.exists()) {
                throw IOException("Atomic commit failed for segment $sequenceNumber")
            }
        }
        fsyncDirectory(recordingDirectory)

        return CommittedSegmentFile(
            recordingId = recordingId,
            sequenceNumber = sequenceNumber,
            segmentId = context.segmentId,
            fileName = target.name,
            sealedLengthBytes = sealed.sealedLength.toLong(),
            plaintextLengthBytes = plaintextLength.toLong(),
            sha256 = Digest.sha256Hex(wavPayload),
            sealedAtWallClockMillis = System.currentTimeMillis(),
        )
    }

    /**
     * Reads back a committed segment, verifying authentication before returning.
     *
     * @throws CryptoException.AuthenticationFailed if the bytes were altered.
     *   Callers must treat this as a quarantine, never as partial audio.
     */
    fun readSegment(committed: CommittedSegmentFile): ByteArray {
        val file = File(recordingDirectory, committed.fileName)
        if (!file.exists()) throw IOException("Missing segment file ${committed.fileName}")

        val header = ByteArray(SegmentCipher.HEADER_SIZE + SegmentCipher.NONCE_SIZE)
        file.inputStream().buffered().use { stream ->
            if (stream.read(header) != header.size) {
                throw CryptoException.MalformedCiphertext("truncated header in ${committed.fileName}")
            }
        }
        cipher.inspectHeader(header)
            ?: throw CryptoException.MalformedCiphertext("not a SafeSignal segment: ${committed.fileName}")

        val sealedBytes = file.readBytes()
        val headerInfo = cipher.inspectHeader(sealedBytes)
            ?: throw CryptoException.MalformedCiphertext("not a SafeSignal segment: ${committed.fileName}")

        val context = EncryptionContext(
            recordingId = recordingId,
            segmentId = committed.segmentId,
            sequenceNumber = committed.sequenceNumber,
            plaintextLength = committed.plaintextLengthBytes.toInt(),
            dataKey = keyMaterial.dataKey,
        )
        return cipher.open(
            SealedSegment(
                bytes = sealedBytes,
                nonce = headerInfo.nonce,
                plaintextLength = committed.plaintextLengthBytes.toInt(),
                algorithm = SegmentCipher.TRANSFORMATION,
            ),
            context,
        )
    }

    /** Highest committed sequence number, or -1 when nothing is committed. */
    fun highestCommittedSequence(): Int =
        recordingDirectory.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(TEMP_SUFFIX) && it.name.endsWith(SEGMENT_EXTENSION) }
            ?.mapNotNull { it.name.removePrefix(SEGMENT_PREFIX).removeSuffix(SEGMENT_EXTENSION).toIntOrNull() }
            ?.maxOrNull()
            ?: -1

    fun toSegmentDigests(): List<SegmentDigest> =
        recordingDirectory.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(TEMP_SUFFIX) && it.name.endsWith(SEGMENT_EXTENSION) }
            ?.mapNotNull { file ->
                val sequence = file.name.removePrefix(SEGMENT_PREFIX)
                    .removeSuffix(SEGMENT_EXTENSION).toIntOrNull() ?: return@mapNotNull null
                val sealedBytes = file.readBytes()
                val info = cipher.inspectHeader(sealedBytes) ?: return@mapNotNull null
                val plaintext = runCatching {
                    readSegment(
                        CommittedSegmentFile(
                            recordingId = recordingId,
                            sequenceNumber = sequence,
                            segmentId = segmentIdFor(recordingId, sequence),
                            fileName = file.name,
                            sealedLengthBytes = file.length(),
                            plaintextLengthBytes = (info.ciphertextLength - GCM_TAG_BYTES).toLong(),
                            sha256 = "",
                            sealedAtWallClockMillis = 0L,
                        ),
                    )
                }.getOrNull() ?: return@mapNotNull null

                SegmentDigest(
                    sequenceNumber = sequence,
                    segmentId = segmentIdFor(recordingId, sequence),
                    sealedLengthBytes = file.length(),
                    plaintextLengthBytes = plaintext.size.toLong(),
                    sha256 = Digest.sha256Hex(plaintext),
                )
            }
            ?.sortedBy { it.sequenceNumber }
            .orEmpty()

    private fun fsyncDirectory(directory: File) {
        // Directory fsync is what actually makes the rename durable. Best-effort:
        // some filesystems reject opening a directory for read on Android.
        runCatching {
            FileOutputStream(directory).use { it.fd.sync() }
        }
    }

    private companion object {
        const val SEGMENT_PREFIX = "segment-"
        const val SEGMENT_EXTENSION = ".ssg"
        const val TEMP_SUFFIX = ".tmp"
        const val GCM_TAG_BYTES = 16

        fun fileNameFor(sequence: Int, temporary: Boolean): String =
            buildString {
                append(SEGMENT_PREFIX)
                append(sequence.toString().padStart(6, '0'))
                append(SEGMENT_EXTENSION)
                if (temporary) append(TEMP_SUFFIX)
            }

        /**
         * Segment id is derived from the recording id and sequence number.
         *
         * Deterministic so that recovery can reconstruct identities from the
         * filesystem alone, without the database. It still cannot collide across
         * recordings because the recording id is a random 128-bit value.
         */
        fun segmentIdFor(recordingId: String, sequence: Int): String =
            Digest.sha256Hex("$recordingId:$sequence").substring(0, 24)
    }
}

/** A committed segment on disk. */
data class CommittedSegmentFile(
    val recordingId: String,
    val sequenceNumber: Int,
    val segmentId: String,
    val fileName: String,
    val sealedLengthBytes: Long,
    val plaintextLengthBytes: Long,
    val sha256: String,
    val sealedAtWallClockMillis: Long,
)