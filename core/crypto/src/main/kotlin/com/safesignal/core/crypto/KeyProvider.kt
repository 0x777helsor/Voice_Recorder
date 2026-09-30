package com.safesignal.core.crypto

/**
 * Key wrapping and signing, abstracted away from `android.security.keystore`.
 *
 * Two reasons for the boundary:
 *
 *  1. **Testability.** Unit tests must be able to exercise wrapping, unwrapping,
 *     tamper detection and key rotation on a plain JVM. `AndroidKeyStore` does
 *     not exist off-device.
 *  2. **Portability.** Keeping the surface this small means a future move to
 *     StrongBox, to a TEE-backed provider, or to a software provider under
 *     Robolectric does not ripple into the recorder.
 *
 * The contract implementations must honour:
 *
 *  - Wrapped key material must never leave this interface in plaintext.
 *  - [unwrapKey] must fail loudly rather than returning garbage.
 *  - [sign] must produce a signature that [verify] accepts only for the exact
 *    bytes signed.
 */
interface KeyProvider {

    /** Creates a key-establishment key under [alias] if it does not exist. */
    fun ensureKeyEstablishmentKey(alias: String)

    /** Creates a manifest signing key under [alias] if it does not exist. */
    fun ensureSigningKey(alias: String)

    /** Wraps [plaintextKey] with the key-establishment key at [alias]. */
    fun wrapKey(alias: String, plaintextKey: ByteArray): WrappedKey

    /** Unwraps previously wrapped key material. */
    fun unwrapKey(alias: String, wrapped: WrappedKey): ByteArray

    /** Signs [data] with the signing key at [alias]. */
    fun sign(alias: String, data: ByteArray): ByteArray

    /** Verifies [signature] over [data]. Returns false rather than throwing on mismatch. */
    fun verify(alias: String, data: ByteArray, signature: ByteArray): Boolean

    /** Public key of the signing key, for publishing a verification key. */
    fun signingPublicKey(alias: String): ByteArray

    fun containsKeyEstablishmentKey(alias: String): Boolean

    fun containsSigningKey(alias: String): Boolean

    /** Irreversibly deletes a key. Used only for explicit user-initiated reset. */
    fun deleteKey(alias: String)

    /** Stable identifier of the backing provider, recorded in evidence metadata. */
    val providerDescription: String
}

/**
 * A key-encryption key output plus everything needed to unwrap it again.
 *
 * @property wrappedKeyBytes opaque output of the KEK's encryption.
 * @property algorithm algorithm identifier recorded in evidence metadata.
 * @property keyVersion allows a future migration to a new KEK without invalidating old evidence.
 */
data class WrappedKey(
    val wrappedKeyBytes: ByteArray,
    val algorithm: String,
    val keyVersion: Int,
    val iv: ByteArray,
    val provider: String,
) {
    // ByteArray in a data class needs structural equals/hashCode.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WrappedKey) return false
        return wrappedKeyBytes.contentEquals(other.wrappedKeyBytes) &&
            algorithm == other.algorithm &&
            keyVersion == other.keyVersion &&
            iv.contentEquals(other.iv) &&
            provider == other.provider
    }

    override fun hashCode(): Int {
        var result = wrappedKeyBytes.contentHashCode()
        result = 31 * result + algorithm.hashCode()
        result = 31 * result + keyVersion
        result = 31 * result + iv.contentHashCode()
        result = 31 * result + provider.hashCode()
        return result
    }
}