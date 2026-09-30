package com.safesignal.core.crypto.testing

import com.safesignal.core.crypto.CryptoException
import com.safesignal.core.crypto.KeyProvider
import com.safesignal.core.crypto.WrappedKey
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A software-only [KeyProvider] for unit tests and for Robolectric runs.
 *
 * This class lives in `main` rather than `test` on purpose: the encryption,
 * integrity and recovery tests that need it live in several modules
 * (`data:local`, `data:repository`), and duplicating the double in each module's
 * test source set guarantees the copies drift apart.
 *
 * SECURITY NOTE: this provider is **not** a security boundary. Keys live in the
 * heap as ordinary byte arrays. It must never be referenced from production
 * wiring; the Hilt graph binds [com.safesignal.core.crypto.AndroidKeystoreKeyProvider].
 */
class InMemoryKeyProvider(
    private val random: SecureRandom = SecureRandom(),
) : KeyProvider {

    private val lock = Any()
    private val keyEstablishmentKeys = mutableMapOf<String, SecretKey>()
    private val signingKeys = mutableMapOf<String, KeyPairEntry>()

    private data class KeyPairEntry(
        val privateKey: java.security.PrivateKey,
        val publicKey: java.security.PublicKey,
    )

    /** Set by tests that want to simulate a lost or invalidated key. */
    var simulateKeyLoss: Boolean = false

    override val providerDescription: String = "InMemoryKeyProvider(TEST-ONLY)"

    override fun ensureKeyEstablishmentKey(alias: String) = synchronized(lock) {
        if (keyEstablishmentKeys.containsKey(alias)) return@synchronized
        val generator = KeyGenerator.getInstance("AES")
        generator.init(256, random)
        keyEstablishmentKeys[alias] = generator.generateKey()
    }

    override fun ensureSigningKey(alias: String) = synchronized(lock) {
        if (signingKeys.containsKey(alias)) return@synchronized
        val generator = java.security.KeyPairGenerator.getInstance("EC")
        generator.initialize(java.security.spec.ECGenParameterSpec("secp256r1"), random)
        val pair = generator.generateKeyPair()
        signingKeys[alias] = KeyPairEntry(pair.private, pair.public)
    }

    override fun wrapKey(alias: String, plaintextKey: ByteArray): WrappedKey {
        if (simulateKeyLoss) throw CryptoException.KeyUnavailable("simulated loss for alias=$alias")
        val key = synchronized(lock) { keyEstablishmentKeys[alias] }
            ?: throw CryptoException.KeyUnavailable("no key-establishment key at alias=$alias")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return WrappedKey(
            wrappedKeyBytes = cipher.doFinal(plaintextKey),
            algorithm = "AES/GCM/NoPadding",
            keyVersion = 1,
            iv = cipher.iv,
            provider = providerDescription,
        )
    }

    override fun unwrapKey(alias: String, wrapped: WrappedKey): ByteArray {
        if (simulateKeyLoss) throw CryptoException.KeyUnavailable("simulated loss for alias=$alias")
        val key = synchronized(lock) { keyEstablishmentKeys[alias] }
            ?: throw CryptoException.KeyUnavailable("no key-establishment key at alias=$alias")
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped.iv))
            cipher.doFinal(wrapped.wrappedKeyBytes)
        } catch (e: Exception) {
            throw CryptoException.AuthenticationFailed("unwrap failed for alias=$alias")
        }
    }

    override fun sign(alias: String, data: ByteArray): ByteArray {
        val entry = synchronized(lock) { signingKeys[alias] }
            ?: throw CryptoException.KeyUnavailable("no signing key at alias=$alias")
        val signature = java.security.Signature.getInstance("SHA256withECDSA")
        signature.initSign(entry.privateKey)
        signature.update(data)
        return signature.sign()
    }

    override fun verify(alias: String, data: ByteArray, signature: ByteArray): Boolean {
        val entry = synchronized(lock) { signingKeys[alias] } ?: return false
        return runCatching {
            java.security.Signature.getInstance("SHA256withECDSA").run {
                initVerify(entry.publicKey)
                update(data)
                verify(signature)
            }
        }.getOrDefault(false)
    }

    override fun signingPublicKey(alias: String): ByteArray =
        synchronized(lock) { signingKeys[alias] }
            ?.publicKey
            ?.encoded
            ?: throw CryptoException.KeyUnavailable("no signing key at alias=$alias")

    override fun containsKeyEstablishmentKey(alias: String): Boolean =
        synchronized(lock) { keyEstablishmentKeys.containsKey(alias) } && !simulateKeyLoss

    override fun containsSigningKey(alias: String): Boolean =
        synchronized(lock) { signingKeys.containsKey(alias) } && !simulateKeyLoss

    override fun deleteKey(alias: String) = synchronized(lock) {
        keyEstablishmentKeys.remove(alias)
        signingKeys.remove(alias)
        Unit
    }

    /** Test helper: drop one alias, simulating Keystore invalidation. */
    fun invalidate(alias: String) = synchronized(lock) {
        keyEstablishmentKeys.remove(alias)
        signingKeys.remove(alias)
        Unit
    }

    private companion object {
        // Referenced so the unused-import checker does not complain about Digest
        // in configurations where only some helpers are used.
        @Suppress("unused")
        val UNUSED = MessageDigest.getInstance("SHA-256")
    }
}