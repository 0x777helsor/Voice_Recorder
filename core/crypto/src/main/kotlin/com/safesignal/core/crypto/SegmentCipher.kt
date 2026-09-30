package com.safesignal.core.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.GCMParameterSpec

/**
 * Authenticated encryption of a single evidence segment.
 *
 * Format (`SSEG` container):
 *
 * ```
 * offset  size  field
 * 0       4     magic  "SSEG"
 * 4       1     container version
 * 5       1     algorithm id (1 = AES-256-GCM)
 * 6       2     reserved, must be zero
 * 8       12    nonce (96-bit, unique per segment)
 * 20      n     ciphertext, followed by the 16-byte GCM tag
 * ```
 *
 * The nonce is stored in the clear because GCM nonces are not secret; they must
 * be *unique*. [EncryptionContext] guarantees that a given data key is never
 * used twice with the same nonce: every call draws a fresh 96-bit value from
 * [SecureRandom].
 *
 * ### Why the additional authenticated data matters
 *
 * Each segment is bound to its identity — recording id, segment id, sequence
 * number and total length. Those fields go into the GCM AAD, so an attacker who
 * can write to the evidence directory cannot:
 *
 *  * swap segment 7 for segment 3 and still pass verification, or
 *  * move a segment from one recording into another, or
 *  * truncate a segment and re-append it as a shorter, complete one.
 *
 * All three are detected by [EvidenceIntegrity.verify] rather than trusted.
 */
class SegmentCipher(
    private val random: SecureRandom = SecureRandom(),
) {

    /**
     * Seals [plaintext].
     *
     * @param plaintext raw PCM or container bytes for exactly one segment.
     * @param context identity to bind into the authentication tag.
     */
    fun seal(plaintext: ByteArray, context: EncryptionContext): SealedSegment =
        try {
            val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, context.secretKey(), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(context.aad)
            val ciphertext = cipher.doFinal(plaintext)

            val out = ByteArray(HEADER_SIZE + nonce.size + ciphertext.size)
            MAGIC.copyInto(out, 0)
            out[4] = CONTAINER_VERSION.toByte()
            out[5] = ALGORITHM_AES_256_GCM.toByte()
            // bytes 6..7 remain zero
            nonce.copyInto(out, HEADER_SIZE)
            ciphertext.copyInto(out, HEADER_SIZE + nonce.size)
            SealedSegment(
                bytes = out,
                nonce = nonce,
                plaintextLength = plaintext.size,
                algorithm = TRANSFORMATION,
            )
        } catch (e: CryptoException) {
            throw e
        } catch (e: Exception) {
            // Never emit partial plaintext, never downgrade. Fail closed.
            throw CryptoException.InvalidRequest("seal failed: ${e.javaClass.simpleName}")
        }

    /**
     * Opens a segment produced by [seal].
     *
     * @throws CryptoException.AuthenticationFailed if a single bit changed.
     */
    fun open(sealed: SealedSegment, context: EncryptionContext): ByteArray = try {
        val bytes = sealed.bytes
        requireHeader(bytes)
        val nonce = bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + NONCE_SIZE)
        val ciphertext = bytes.copyOfRange(HEADER_SIZE + NONCE_SIZE, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, context.secretKey(), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(context.aad)
        cipher.doFinal(ciphertext)
    } catch (e: CryptoException) {
        throw e
    } catch (e: Exception) {
        // AEADBadTagException lands here: the single most important check in the
        // whole product. It must never be converted into "best effort" output.
        throw CryptoException.AuthenticationFailed("segment ${context.segmentId}")
    }

    /** Structural validation that does not require any key material. */
    fun inspectHeader(bytes: ByteArray): SegmentHeader? = runCatching {
        if (bytes.size < HEADER_SIZE + NONCE_SIZE) return null
        if (!MAGIC.indices.all { bytes[it] == MAGIC[it] }) return null
        val version = bytes[4].toInt() and 0xFF
        val algorithm = bytes[5].toInt() and 0xFF
        if (version != CONTAINER_VERSION) return null
        SegmentHeader(
            containerVersion = version,
            algorithmId = algorithm,
            nonce = bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + NONCE_SIZE),
            ciphertextLength = bytes.size - HEADER_SIZE - NONCE_SIZE,
        )
    }.getOrNull()

    private fun requireHeader(bytes: ByteArray) {
        if (bytes.size < HEADER_SIZE + NONCE_SIZE) {
            throw CryptoException.MalformedCiphertext("truncated: ${bytes.size} bytes")
        }
        if (!MAGIC.indices.all { bytes[it] == MAGIC[it] }) {
            throw CryptoException.MalformedCiphertext("bad magic")
        }
        val version = bytes[4].toInt() and 0xFF
        if (version != CONTAINER_VERSION) {
            throw CryptoException.MalformedCiphertext("unsupported container version $version")
        }
        val algorithm = bytes[5].toInt() and 0xFF
        if (algorithm != ALGORITHM_AES_256_GCM) {
            throw CryptoException.MalformedCiphertext("unsupported algorithm id $algorithm")
        }
    }

    companion object {
        val MAGIC = byteArrayOf('S'.code.toByte(), 'S'.code.toByte(), 'E'.code.toByte(), 'G'.code.toByte())
        const val CONTAINER_VERSION = 1
        const val ALGORITHM_AES_256_GCM = 1
        const val HEADER_SIZE = 8
        const val NONCE_SIZE = 12
        const val GCM_TAG_BITS = 128
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** AES-256 data key length. */
        const val DATA_KEY_SIZE_BYTES = 32
    }
}

/** Parsed container header. */
data class SegmentHeader(
    val containerVersion: Int,
    val algorithmId: Int,
    val nonce: ByteArray,
    val ciphertextLength: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SegmentHeader) return false
        return containerVersion == other.containerVersion &&
            algorithmId == other.algorithmId &&
            nonce.contentEquals(other.nonce) &&
            ciphertextLength == other.ciphertextLength
    }

    override fun hashCode(): Int {
        var result = containerVersion
        result = 31 * result + algorithmId
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + ciphertextLength
        return result
    }
}

/**
 * Identity bound into a segment's authentication tag.
 *
 * Keeping [dataKey] out of [aad] is deliberate: the key is the encryption
 * secret, not authenticated data.
 */
class EncryptionContext(
    val recordingId: String,
    val segmentId: String,
    val sequenceNumber: Int,
    val plaintextLength: Int,
    val dataKey: ByteArray,
) {
    /**
     * Wraps the raw DEK bytes in a [SecretKeySpec] for the Cipher API.
     *
     * The bytes are copied into a fresh spec on every call rather than cached:
     * a cached [SecretKeySpec] would retain a reference to the key longer than
     * necessary, and [RecordingKeyMaterial.clear] must be able to invalidate it.
     */
    fun secretKey(): SecretKeySpec = SecretKeySpec(dataKey, "AES")

    /** Canonical, unambiguous serialisation. Order and separators are fixed. */
    val aad: ByteArray = buildAad(recordingId, segmentId, sequenceNumber, plaintextLength)

    override fun toString(): String =
        "EncryptionContext(recording=$recordingId, segment=$segmentId, seq=$sequenceNumber, len=$plaintextLength)"

    companion object {
        /**
         * Field separator that cannot appear in any field, so that
         * ("ab", "c") and ("a", "bc") cannot produce the same AAD. Without this,
         * a segment could be re-labelled from one id pair to another.
         */
        private const val SEP = '\u0000'

        fun buildAad(
            recordingId: String,
            segmentId: String,
            sequenceNumber: Int,
            plaintextLength: Int,
        ): ByteArray = buildString {
            append(recordingId).append(SEP)
            append(segmentId).append(SEP)
            append(sequenceNumber).append(SEP)
            append(plaintextLength)
        }.toByteArray(Charsets.UTF_8)
    }
}

/** Result of [SegmentCipher.seal]. */
class SealedSegment(
    val bytes: ByteArray,
    val nonce: ByteArray,
    val plaintextLength: Int,
    val algorithm: String,
) {
    val sealedLength: Int get() = bytes.size
}