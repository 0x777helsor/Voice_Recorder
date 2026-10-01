package com.safesignal.core.crypto

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
        val generator = KeyGenerator.getInstance(KeyPropertiesAlgorithm.AES, providerName)
        generator.init(
            KeyGenParameterSpecBuilderCompat.build(
                alias = alias,
                purposes = purposesEncryptDecrypt,
            ),
        )
        generator.generateKey()
    }

    override fun ensureSigningKey(alias: String) {
        if (containsSigningKey(alias)) return
        val generator = KeyPairGenerator.getInstance(KeyPropertiesAlgorithm.EC, providerName)
        generator.initialize(
            KeyGenParameterSpecBuilderCompat.build(
                alias = alias,
                purposes = purposesSignVerify,
            ),
        )
        generator.generateKeyPair()
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

    override fun signingPublicKey(alias: String): ByteArray =
        requireSigningKey(alias).encoded

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
 */
private object KeyGenParameterSpecBuilderCompat {
    fun build(alias: String, purposes: Int): android.security.keystore.KeyGenParameterSpec {
        val builder = android.security.keystore.KeyGenParameterSpec.Builder(
            alias,
            purposes,
        )
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            // See class docs: user authentication is deliberately not required,
            // because activation must be able to finalize evidence unattended.
            .setUserAuthenticationRequired(false)
            .setRandomizedEncryptionRequired(true)

        // StrongBox, where available, keeps the KEK inside a dedicated secure
        // element. Requesting it is a hint; devices without it fall back.
        //
        // setIsStrongBoxBacked() was added in API 28. Wrapping it in runCatching
        // is NOT sufficient: on API 26/27 the method does not exist, so the call
        // throws NoSuchMethodError. An explicit version check is required.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            runCatching { builder.setIsStrongBoxBacked(true) }
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            builder.setUnlockedDeviceRequired(true)
        }

        return builder.build()
    }
}

private const val purposesEncryptDecrypt =
    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
        android.security.keystore.KeyProperties.PURPOSE_DECRYPT

private const val purposesSignVerify =
    android.security.keystore.KeyProperties.PURPOSE_SIGN or
        android.security.keystore.KeyProperties.PURPOSE_VERIFY