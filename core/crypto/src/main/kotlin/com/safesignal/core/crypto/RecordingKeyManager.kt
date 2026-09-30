package com.safesignal.core.crypto

import java.security.SecureRandom
import javax.crypto.KeyGenerator

/**
 * Envelope encryption for recordings (SPEC §23, §43).
 *
 * ```
 *   per-recording DEK (AES-256, random, in memory only while writing)
 *        │
 *        ├────────────► wraps every segment with AES-256-GCM + per-segment nonce
 *        │
 *        └────────────► wrapped once by the Keystore KEK, stored in Room
 * ```
 *
 * Properties this buys us, and why each matters for an evidence product:
 *
 *  * **The DEK never touches persistent storage in plaintext.** Losing the
 *    Keystore key makes old recordings unreadable, which is the intended
 *    behaviour for a stolen device — see SPEC §54, which requires that account
 *    recovery never silently weakens encryption.
 *  * **Rotation is cheap.** Rotating the KEK means re-wrapping 32-byte DEKs, not
 *    re-encrypting gigabytes of audio. [keyVersion] records which KEK version
 *    wrapped a given recording.
 *  * **The backend never needs plaintext.** Only wrapped material is uploaded,
 *    so a compromised storage bucket yields ciphertext (SPEC §43).
 *
 * The one property this does *not* give us, and the specification is explicit
 * about it: the backend cannot decrypt a recording, so there is no
 * "forgot my key, ask the server" recovery path. Losing the device keystore
 * means losing the audio. That trade-off is stated in the onboarding copy.
 */
class RecordingKeyManager(
    private val keyProvider: KeyProvider,
    private val random: SecureRandom = SecureRandom(),
) {

    /** Key-establishment key alias. Single, long-lived, device-scoped. */
    var keyEstablishmentAlias: String = DEFAULT_KEK_ALIAS
        private set

    /** Current KEK version. Bump when rotating; recorded per recording. */
    var keyVersion: Int = DEFAULT_KEY_VERSION

    /**
     * Creates fresh key material for a new recording.
     *
     * The returned [RecordingKeyMaterial] holds the DEK in memory. Callers must
     * zero it via [RecordingKeyMaterial.clear] once the last segment is sealed.
     */
    fun createRecordingKeyMaterial(recordingId: String): RecordingKeyMaterial {
        keyProvider.ensureKeyEstablishmentKey(keyEstablishmentAlias)
        val dek = ByteArray(SegmentCipher.DATA_KEY_SIZE_BYTES).also(random::nextBytes)
        val wrapped = keyProvider.wrapKey(keyEstablishmentAlias, dek)
        return RecordingKeyMaterial(
            recordingId = recordingId,
            dataKey = dek,
            wrappedKey = wrapped,
            keyVersion = keyVersion,
            keyProvider = keyProvider.providerDescription,
        )
    }

    /** Recovers the DEK for an existing recording so its segments can be read. */
    fun unwrapRecordingKey(material: RecordingKeyMaterial): ByteArray =
        keyProvider.unwrapKey(keyEstablishmentAlias, material.wrappedKey)

    /**
     * Re-wraps a recording's DEK under the current KEK version.
     *
     * Used by key rotation. The audio segments are untouched — only the 32-byte
     * DEK blob is re-protected, so rotation is O(number of recordings), not
     * O(bytes of audio).
     */
    fun rotateWrappedKey(material: RecordingKeyMaterial): RecordingKeyMaterial {
        keyProvider.ensureKeyEstablishmentKey(keyEstablishmentAlias)
        val dek = unwrapRecordingKey(material)
        val wrapped = keyProvider.wrapKey(keyEstablishmentAlias, dek)
        dek.fill(0)
        return material.copy(
            wrappedKey = wrapped,
            keyVersion = keyVersion,
            keyProvider = keyProvider.providerDescription,
        )
    }

    /**
     * The public key third parties use to verify an evidence manifest.
     * Published inside the export package; reveals nothing secret.
     */
    fun signingPublicKey(alias: String): ByteArray = keyProvider.signingPublicKey(alias)

    companion object {
        const val DEFAULT_KEK_ALIAS = "safesignal.kek.v1"
        const val DEFAULT_KEY_VERSION = 1

        /** Creates a [KeyGenerator] for a 256-bit AES key. */
        fun aesKeyGenerator(random: SecureRandom = SecureRandom()): KeyGenerator =
            KeyGenerator.getInstance("AES").apply { init(SegmentCipher.DATA_KEY_SIZE_BYTES * 8, random) }
    }
}

/**
 * Key material for exactly one recording.
 *
 * @property dataKey the DEK. Call [clear] as soon as the final segment is sealed.
 */
data class RecordingKeyMaterial(
    val recordingId: String,
    val dataKey: ByteArray,
    val wrappedKey: WrappedKey,
    val keyVersion: Int,
    val keyProvider: String,
) {
    /** True once [clear] has run. Using a cleared context must fail. */
    var isCleared: Boolean = false
        private set

    /** Overwrites the DEK in memory. Best effort — the JVM may have copied it. */
    fun clear() {
        dataKey.fill(0)
        isCleared = true
    }

    fun toEncryptionContext(segmentId: String, sequenceNumber: Int, plaintextLength: Int): EncryptionContext {
        check(!isCleared) { "key material for $recordingId has been cleared" }
        return EncryptionContext(
            recordingId = recordingId,
            segmentId = segmentId,
            sequenceNumber = sequenceNumber,
            plaintextLength = plaintextLength,
            dataKey = dataKey,
        )
    }

    override fun toString(): String =
        "RecordingKeyMaterial(recording=$recordingId, keyVersion=$keyVersion, provider=$keyProvider)"
}