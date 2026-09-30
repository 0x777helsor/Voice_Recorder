package com.safesignal.core.common.id

import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

/**
 * Identifier generation for evidence.
 *
 * Two properties matter and neither is cosmetic:
 *
 *  * **Unpredictability.** Recording ids appear in upload URLs and in object
 *    storage keys. A sequential or timestamp-derived id would let anyone who
 *    obtains one id enumerate a user's recordings. Ids are therefore random.
 *  * **Opacity.** A recording id must not leak when or where a recording was
 *    made. No timestamp component, no device id, no user id.
 *
 * `java.util.UUID.randomUUID()` is backed by `SecureRandom` on Android, so it is
 * used directly rather than wrapping `UUID.nameUUIDFromBytes` (which is an MD5
 * of its input and would be deterministic).
 */
object Identifiers {

    private val secureRandom = SecureRandom()

    /** 32 lowercase hex characters, drawn from a CSPRNG. */
    fun recordingId(): String = randomHex(16)

    /** 24 hex characters. Used for segment ids within a recording. */
    fun segmentId(): String = randomHex(12)

    /** 16 hex characters, for manifest/upload idempotency keys. */
    fun idempotencyKey(): String = randomHex(8)

    /** Random 96-bit nonce for AES-GCM, hex encoded. */
    fun nonce(): String = randomHex(12)

    private fun randomHex(bytes: Int): String {
        val buffer = ByteArray(bytes)
        secureRandom.nextBytes(buffer)
        val out = StringBuilder(bytes * 2)
        for (b in buffer) {
            out.append(String.format(Locale.ROOT, "%02x", b))
        }
        return out.toString()
    }
}

/**
 * Formats an identifier for display or logging.
 *
 * `8d2f0c9ab31d4e7f…` — long enough to be correlatable by a human reading a
 * support report, short enough that it is useless as a storage key guess.
 */
fun String.asShortId(): String =
    if (length <= 12) this else take(8) + "…"