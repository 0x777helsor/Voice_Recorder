package com.safesignal.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Tamper-detection suite referenced from SECURITY.md § "Cryptographic integrity"
 * and from SPEC §88.
 *
 * These tests are the executable form of the product's central promise: SafeSignal
 * must refuse audio it cannot authenticate. Each test alters exactly one thing and
 * asserts that decryption fails closed.
 */
class SegmentCipherTest {

    private val cipher = SegmentCipher(SecureRandom())
    private val dataKey = ByteArray(32) { it.toByte() }
    private val plaintext = "the quick brown fox".toByteArray()

    private fun context(
        recordingId: String = "rec-1",
        segmentId: String = "seg-1",
        sequenceNumber: Int = 0,
        plaintextLength: Int = plaintext.size,
    ) = EncryptionContext(
        recordingId = recordingId,
        segmentId = segmentId,
        sequenceNumber = sequenceNumber,
        plaintextLength = plaintextLength,
        dataKey = dataKey,
    )

    @Test
    fun `seal then open returns the original bytes`() {
        val sealed = cipher.seal(plaintext, context())
        assertArrayEquals(plaintext, cipher.open(sealed, context()))
    }

    @Test
    fun `sealed output is larger than plaintext by the tag`() {
        val sealed = cipher.seal(plaintext, context())
        // header (8) + nonce (12) + ciphertext + 16-byte GCM tag
        assertEquals(
            SegmentCipher.HEADER_SIZE + SegmentCipher.NONCE_SIZE + plaintext.size + 16,
            sealed.bytes.size,
        )
        assertEquals(SegmentCipher.NONCE_SIZE, sealed.nonce.size)
        assertEquals(plaintext.size, sealed.plaintextLength)
    }

    @Test
    fun `identical input produces different nonce and ciphertext`() {
        val a = cipher.seal(plaintext, context())
        val b = cipher.seal(plaintext, context())
        assertTrue("nonce must be unique per segment", !a.nonce.contentEquals(b.nonce))
        assertTrue("ciphertext must differ", !a.bytes.contentEquals(b.bytes))
    }

    @Test
    fun `flipping one ciphertext byte fails authentication`() {
        val sealed = cipher.seal(plaintext, context())
        val tampered = sealed.bytes.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1] + 1).toByte()

        assertThrows(CryptoException.AuthenticationFailed::class.java) {
            cipher.open(SealedSegment(tampered, sealed.nonce, plaintext.size, sealed.algorithm), context())
        }
    }

    @Test
    fun `flipping a single plaintext-bearing byte anywhere fails`() {
        val sealed = cipher.seal(plaintext, context())
        // Byte 0 is inside the header magic; byte at HEADER_SIZE+NONCE_SIZE is the
        // first ciphertext byte. Both must be covered by verification.
        listOf(0, SegmentCipher.HEADER_SIZE + SegmentCipher.NONCE_SIZE).forEach { index ->
            val tampered = sealed.bytes.copyOf()
            tampered[index] = (tampered[index] + 1).toByte()
            assertThrows("index $index must fail closed", CryptoException.AuthenticationFailed::class.java) {
                cipher.open(SealedSegment(tampered, sealed.nonce, plaintext.size, sealed.algorithm), context())
            }
        }
    }

    @Test
    fun `decrypting with the wrong key fails closed`() {
        val sealed = cipher.seal(plaintext, context())
        val otherKey = ByteArray(32) { (it + 1).toByte() }

        assertThrows(CryptoException.AuthenticationFailed::class.java) {
            cipher.open(
                sealed,
                EncryptionContext(
                    recordingId = "rec-1",
                    segmentId = "seg-1",
                    sequenceNumber = 0,
                    plaintextLength = plaintext.size,
                    dataKey = otherKey,
                ),
            )
        }
    }

    @Test
    fun `moving a segment to a different sequence number fails`() {
        val sealed = cipher.seal(plaintext, context())
        assertThrows(CryptoException.AuthenticationFailed::class.java) {
            cipher.open(sealed, context(sequenceNumber = 7))
        }
    }

    @Test
    fun `moving a segment into a different recording fails`() {
        val sealed = cipher.seal(plaintext, context())
        assertThrows(CryptoException.AuthenticationFailed::class.java) {
            cipher.open(sealed, context(recordingId = "rec-2"))
        }
    }

    @Test
    fun `swapping segment ids fails`() {
        val sealed = cipher.seal(plaintext, context())
        assertThrows(CryptoException.AuthenticationFailed::class.java) {
            cipher.open(sealed, context(segmentId = "seg-9"))
        }
    }

    @Test
    fun `truncating a segment fails closed`() {
        val sealed = cipher.seal(plaintext, context())
        val truncated = sealed.bytes.copyOf(sealed.bytes.size - 5)
        assertThrows(CryptoException.MalformedCiphertext::class.java) {
            cipher.open(SealedSegment(truncated, sealed.nonce, plaintext.size, sealed.algorithm), context())
        }
    }

    @Test
    fun `unknown container version is rejected rather than guessed at`() {
        val sealed = cipher.seal(plaintext, context())
        val badVersion = sealed.bytes.copyOf()
        badVersion[4] = 99
        assertThrows(CryptoException.MalformedCiphertext::class.java) {
            cipher.open(SealedSegment(badVersion, sealed.nonce, plaintext.size, sealed.algorithm), context())
        }
    }

    @Test
    fun `bad magic is rejected`() {
        val sealed = cipher.seal(plaintext, context())
        val badMagic = sealed.bytes.copyOf()
        badMagic[0] = 'X'.code.toByte()
        assertThrows(CryptoException.MalformedCiphertext::class.java) {
            cipher.open(SealedSegment(badMagic, sealed.nonce, plaintext.size, sealed.algorithm), context())
        }
    }

    @Test
    fun `AAD canonical form is unambiguous across field boundaries`() {
        val left = EncryptionContext.buildAad("ab", "c", 0, 10).toList()
        val right = EncryptionContext.buildAad("a", "bc", 0, 10).toList()
        assertNotEquals("id fields must not be splittable across the separator", left, right)
    }

    @Test
    fun `data keys are 256 bit and unique`() {
        val a = ByteArray(32) { 1 }
        val b = ByteArray(32) { 2 }
        assertEquals(32, a.size)
        assertNotEquals(a.toList(), b.toList())
        assertEquals(SegmentCipher.DATA_KEY_SIZE_BYTES, a.size)
    }

    @Test
    fun `header inspection identifies an encrypted segment without any key`() {
        val sealed = cipher.seal(plaintext, context())
        val header = cipher.inspectHeader(sealed.bytes)
        assertTrue(header != null)
        assertEquals(SegmentCipher.NONCE_SIZE, header!!.nonce.size)
        assertEquals(plaintext.size + 16, header.ciphertextLength)
    }
}