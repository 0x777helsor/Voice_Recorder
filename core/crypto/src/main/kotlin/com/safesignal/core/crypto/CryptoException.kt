package com.safesignal.core.crypto

/**
 * Failures produced by the cryptographic layer.
 *
 * Every one of these is a *fail-closed* condition: SafeSignal never falls back to
 * plaintext, never downgrades an algorithm, and never reports a recording as
 * finalized when its integrity could not be established.
 */
sealed class CryptoException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** An authentication tag did not verify: the ciphertext was altered or the key is wrong. */
    class AuthenticationFailed(detail: String) :
        CryptoException("Authenticated decryption failed: $detail")

    /** Ciphertext is structurally impossible (wrong magic, truncated, unknown version). */
    class MalformedCiphertext(detail: String) :
        CryptoException("Ciphertext is malformed: $detail")

    /** The Keystore rejected the operation (key invalidated, lock-screen changed, etc.). */
    class KeystoreFailure(detail: String, cause: Throwable? = null) :
        CryptoException("Android Keystore operation failed: $detail", cause)

    /** A key required to read existing evidence is no longer available. */
    class KeyUnavailable(detail: String) :
        CryptoException("Required key material is unavailable: $detail")

    /** A signature did not verify. */
    class SignatureInvalid(detail: String) :
        CryptoException("Signature verification failed: $detail")

    /** Call passed arguments that cannot produce a safe result. */
    class InvalidRequest(detail: String) :
        CryptoException("Invalid cryptographic request: $detail")
}