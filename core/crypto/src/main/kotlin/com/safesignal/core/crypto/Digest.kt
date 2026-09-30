package com.safesignal.core.crypto

import java.security.MessageDigest

/**
 * SHA-256 helpers used for evidence integrity.
 *
 * A recording's integrity rests on two independent mechanisms, and it is worth
 * being explicit about why both are needed:
 *
 *  * **AES-GCM tags** prove a *segment* has not been altered, provided you
 *    already hold the key.
 *  * **SHA-256 hashes in the manifest** let a third party — or a later version
 *    of the app, or a court officer with only the export package — check that
 *    the whole recording is complete and in the right order, without any key.
 *
 * Neither alone is sufficient: GCM says nothing about a *missing* segment, and
 * an unsigned hash list can simply be rewritten to match corrupted files. The
 * manifest is therefore signed (see [ManifestSigner]) as well.
 */
object Digest {

    private const val ALGORITHM = "SHA-256"

    /** Hex-encoded, lowercase. The wire format used everywhere in SafeSignal. */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance(ALGORITHM).digest(bytes).toHex()

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    /**
     * Hashes a file without loading it into memory.
     *
     * Evidence packages are routinely larger than a phone's heap budget, so
     * every hashing path in SafeSignal streams.
     */
    fun sha256Hex(file: java.io.File): String {
        val digest = MessageDigest.getInstance(ALGORITHM)
        file.inputStream().buffered(BUFFER_SIZE).use { stream ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    fun newDigest(): MessageDigest = MessageDigest.getInstance(ALGORITHM)

    private fun ByteArray.toHex(): String {
        val out = CharArray(size * 2)
        for (i in indices) {
            val v = this[i].toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }

    private const val BUFFER_SIZE = 64 * 1024
    private val HEX = "0123456789abcdef".toCharArray()
}