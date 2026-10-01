package com.safesignal.core.crypto

import android.annotation.TargetApi
import android.os.Build
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Production [KeyProvider] backed by the Android Keystore.
 *
 * Design notes:
 *
 *  * The key-establishment key is an **AES-256-GCM** key generated inside the
 *    Keystore. It is non-exportable: on devices with a secure element the key
 *    material never enters app memory at all, so a memory-scraping attack
 *    against the app process cannot recover it.
 *  * The signing key is an **EC P-256** key pair. Signing inside the Keystore
 *    lets a manifest be verified with a *public* key. That is deliberate: the
 *    evidence package can be checked by a third party — a lawyer, a court
 *    officer — without handing them anything secret.
 *  * Everything is tagged with `setUserAuthenticationRequired(false)` because
 *    SafeSignal must be able to seal a recording at activation time without a
 *    biometric prompt. Requiring user authentication would mean a recording
 *    could start but never be finalized. This trade-off is documented in
 *    THREAT_MODEL.md § "Keystore authentication not required".
 *  * Keys use `setUnlockedDeviceRequired(true)` on API 28+ where the platform
 *    supports it, so evidence at rest is protected when the device is locked.
 *    On devices that cannot honour it the key still never leaves the Keystore.
 *
 * Failure modes this class surfaces, all fail-closed:
 *  - Keystore invalidated by a lock-screen change → `KeyUnavailable`.
 *  - `UserNotAuthenticatedException` → `KeyUnavailable`.
 *  - `KeyPermanentlyInvalidatedException` → `KeyUnavailable`, evidence stays
 *    encrypted and unrecoverable rather than being silently discarded.
 */
class AndroidKeystoreKeyProvider(
    private val providerName: String = ANDROID_KEYSTORE,
) : KeyProvider {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(providerName).apply { load(null) }
    }

    override val providerDescription: String = "AndroidKeyStore"

    override fun ensureKeyEstablishmentKey(alias: String) {
        if (containsKeyEstablishmentKey(alias)) return
        generateKeyWithStrongBoxFallback { useStrongBox ->
            KeyGenerator.getInstance(KeyPropertiesAlgorithm.AES, providerName).apply {
                init(KeyGenParameterSpecBuilderCompat.buildAes(alias, useStrongBox))
            }.generateKey()
        }
    }

    override fun ensureSigningKey(alias: String) {
        if (containsSigningKey(alias)) return
        generateKeyWithStrongBoxFallback { useStrongBox ->
            KeyPairGenerator.getInstance(KeyPropertiesAlgorithm.EC, providerName).apply {
                initialize(KeyGenParameterSpecBuilderCompat.buildEc(alias, useStrongBox))
            }.generateKeyPair()
        }
    }

    override fun wrapKey(alias: String, plaintextKey: ByteArray): WrappedKey {
        val cipher = Cipher.getInstance(KeyPropertiesAlgorithm.TRANSPFORMATION_GCM)
        val key = requireKeyEstablishmentKey(alias)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val wrapped = try {
            cipher.doFinal(plaintextKey)
        } catch (e: Exception) {
            throw CryptoException.KeystoreFailure("wrap failed for alias=$alias", e)
        }
        return WrappedKey(
            wrappedKeyBytes = wrapped,
            algorithm = KeyPropertiesAlgorithm.TRANSPFORMATION_GCM,
            keyVersion = KEY_VERSION,
            iv = iv,
            provider = providerDescription,
        )
    }

    override fun unwrapKey(alias: String, wrapped: WrappedKey): ByteArray {
        val cipher = Cipher.getInstance(KeyPropertiesAlgorithm.TRANSPFORMATION_GCM)
        val key = requireKeyEstablishmentKey(alias)
        return try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, wrapped.iv))
            cipher.doFinal(wrapped.wrappedKeyBytes)
        } catch (e: CryptoException) {
            throw e
        } catch (e: Exception) {
            // A tampered wrapped-key blob surfaces here as a tag failure. We
            // deliberately do not distinguish "tampered" from "wrong key": both
            // mean the same thing operationally (this evidence is unreadable).
            throw CryptoException.AuthenticationFailed("unwrapping DEK for alias=$alias")
        }
    }

    override fun sign(alias: String, data: ByteArray): ByteArray {
        val privateKey = requireSigningKey(alias)
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        return try {
            signature.initSign(privateKey)
            signature.update(data)
            signature.sign()
        } catch (e: Exception) {
            throw CryptoException.KeystoreFailure("sign failed for alias=$alias", e)
        }
    }

    override fun verify(alias: String, data: ByteArray, signature: ByteArray): Boolean {
        val publicKey = requireSigningPublicKey(alias)
        return runCatching {
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initVerify(publicKey)
                update(data)
                verify(signature)
            }
        }.getOrDefault(false)
    }

    /** The verification key as a [PublicKey]; public keys are exportable. */
    private fun requireSigningPublicKey(alias: String): PublicKey {
        val entry = keyStore.getCertificate(alias) as? java.security.cert.Certificate
            ?: throw CryptoException.KeyUnavailable("no signing certificate at alias=$alias")
        return entry.publicKey
    }

    /**
     * The encoded public key for publication.
     *
     * Reads the **public** key out of the certificate, not the private key.
     * `PrivateKey.getEncoded()` returns null on Android Keystore by design —
     * keys are non-exportable — so the previous `requireSigningKey(alias).encoded`
     * threw `NullPointerException` on every real device and would have published
     * an empty verification key inside every evidence package, making the package
     * unverifiable by anyone.
     */
    override fun signingPublicKey(alias: String): ByteArray =
        requireSigningPublicKey(alias).encoded

    override fun containsKeyEstablishmentKey(alias: String): Boolean = try {
        keyStore.containsAlias(alias) && keyStore.getKey(alias, null) is SecretKey
    } catch (e: Exception) {
        false
    }

    override fun containsSigningKey(alias: String): Boolean = try {
        keyStore.containsAlias(alias) && keyStore.getKey(alias, null) is KeyPair
    } catch (e: Exception) {
        false
    }

    override fun deleteKey(alias: String) {
        try {
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        } catch (e: Exception) {
            throw CryptoException.KeystoreFailure("delete failed for alias=$alias", e)
        }
    }

    private fun requireKeyEstablishmentKey(alias: String): SecretKey =
        (loadKey(alias) as? SecretKey)
            ?: throw CryptoException.KeyUnavailable("no key-establishment key at alias=$alias")

    private fun requireSigningKey(alias: String): PrivateKey {
        val entry = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            ?: throw CryptoException.KeyUnavailable("no signing key at alias=$alias")
        return entry.privateKey
    }

    private fun loadKey(alias: String): java.security.Key? = try {
        keyStore.getKey(alias, null)
    } catch (e: Exception) {
        throw CryptoException.KeyUnavailable("key at alias=$alias is unusable: ${e.javaClass.simpleName}")
    }

    private companion object {
        const val KEY_VERSION = 1
        const val GCM_TAG_BITS = 128
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}

/** Shared algorithm identifiers, kept in one place so metadata cannot drift from code. */
object KeyPropertiesAlgorithm {
    const val AES = "AES"
    const val EC = "EC"
    const val TRANSPFORMATION_GCM = "AES/GCM/NoPadding"
}

/**
 * Builds `KeyGenParameterSpec` without dragging the whole `KeyProperties` name
 * space into every file.
 *
 * There is one builder method per key purpose rather than one shared method,
 * because the parameters are not interchangeable. Encryption keys need block
 * modes and paddings; signing keys are rejected if given them. An earlier version
 * passed the same spec to both and only escaped failing unit tests because those
 * tests never touched the real Keystore.
 */
private object KeyGenParameterSpecBuilderCompat {

    /** AES-256-GCM key-establishment key. */
    fun buildAes(alias: String, useStrongBox: Boolean): android.security.keystore.KeyGenParameterSpec =
        base(alias, purposesEncryptDecrypt, useStrongBox)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()

    /**
     * EC P-256 signing key.
     *
     * No block modes, no encryption padding: those describe ciphers, and a
     * sign/verify key has none. The digest is pinned to SHA-256, which is the
     * only digest [AndroidKeystoreKeyProvider] signs with.
     */
    fun buildEc(alias: String, useStrongBox: Boolean): android.security.keystore.KeyGenParameterSpec =
        base(alias, purposesSignVerify, useStrongBox)
            .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256)
            .build()

    private fun base(
        alias: String,
        purposes: Int,
        useStrongBox: Boolean,
    ): android.security.keystore.KeyGenParameterSpec.Builder {
        val builder = android.security.keystore.KeyGenParameterSpec.Builder(alias, purposes)
            // See class docs: user authentication is deliberately not required,
            // because activation must be able to finalize evidence unattended.
            .setUserAuthenticationRequired(false)

        // setIsStrongBoxBacked() and setUnlockedDeviceRequired() were added in
        // API 28. The version check is required, not defensive: on API 26/27 the
        // methods do not exist and the call would throw NoSuchMethodError.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            builder.setUnlockedDeviceRequired(true)
            if (useStrongBox) builder.setIsStrongBoxBacked(true)
        }

        return builder
    }
}

/**
 * Generates a Keystore key, retrying once without StrongBox.
 *
 * StrongBox is **not a hint**. `setIsStrongBoxBacked(true)` makes the dedicated
 * secure element a requirement, and on a device that cannot provide one, key
 * generation fails outright with [StrongBoxUnavailableException] rather than
 * quietly falling back. Wrapping only the builder call in `runCatching` — as an
 * earlier version of this file did — achieves nothing, because the builder does
 * not throw; the exception surfaces later, from `generateKey`/`generateKeyPair`.
 *
 * This was not theoretical. On a Galaxy A51 (Exynos 9611, no usable StrongBox)
 * the app could not create a key at all, so it could not protect a single
 * recording. No unit test using a fake [KeyProvider] could observe it, because
 * the fake never reaches the platform. It was found by the readiness probe
 * performing a real wrap/unwrap on a real device.
 *
 * The retry is deliberately narrow. Only a genuine StrongBox-unavailable signal
 * triggers a second attempt; any other failure propagates on the first try, and a
 * second failure propagates too. The provider still fails closed rather than
 * degrading to an exportable software key.
 *
 * ### Why the version check lives in its own function
 *
 * `StrongBoxUnavailableException` only exists from API 28, while `minSdk` is 26. On
 * API 26–27 there is no StrongBox to request and no exception to catch, so the
 * first attempt skips it entirely.
 *
 * Lint cannot see that: it reads `Build.VERSION.SDK_INT`, not a parameter, so a
 * guard written against a local variable is not recognised and the catch clause is
 * flagged `NewApi`. Rather than suppress the warning on a catch that genuinely
 * cannot be reached below 28, the guard is hoisted into a function annotated
 * `@RequiresApi(28)`. Lint then understands that the body only runs on 28+, which
 * is true, and the reasoning is stated where it is checked rather than buried in an
 * annotation.
 *
 * `sdkInt` is a parameter so the version branch is testable. It reads as 0 under
 * `isReturnDefaultValues`, so a test calling the production form would take the
 * pre-28 path every time and prove nothing about what ships. That mistake happened:
 * adding the guard broke all four existing tests, which had been silently testing
 * the version branch instead of the fallback.
 *
 * @param attempt receives whether to demand StrongBox. It must build a fresh
 *   generator on each call, because a generator whose `generateKey` has thrown is
 *   not reusable.
 */
internal inline fun <T> generateKeyWithStrongBoxFallback(
    sdkInt: Int = Build.VERSION.SDK_INT,
    attempt: (useStrongBox: Boolean) -> T,
): T =
    if (sdkInt < Build.VERSION_CODES.P) {
        // Pre-28: no dedicated secure element exists, so there is nothing to retry.
        attempt(false)
    } else {
        attemptWithStrongBox(attempt)
    }

/**
 * `android.annotation.TargetApi`, not `androidx.annotation.RequiresApi`.
 *
 * `:core:crypto` has no AndroidX dependency and adding one for a single marker
 * would be a heavier change than the annotation is worth. `TargetApi` is the
 * platform's own marker, is understood by lint, and asserts the same thing: this
 * code only runs on 28 and above.
 */
@TargetApi(Build.VERSION_CODES.P)
private inline fun <T> attemptWithStrongBox(attempt: (useStrongBox: Boolean) -> T): T =
    try {
        attempt(true)
    } catch (strongBoxUnavailable: StrongBoxUnavailableException) {
        attempt(false)
    }

private const val purposesEncryptDecrypt =
    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
        android.security.keystore.KeyProperties.PURPOSE_DECRYPT

private const val purposesSignVerify =
    android.security.keystore.KeyProperties.PURPOSE_SIGN or
        android.security.keystore.KeyProperties.PURPOSE_VERIFY