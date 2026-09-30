package com.safesignal.core.crypto

import java.util.Base64

/**
 * Signs and verifies evidence manifests with a Keystore-held key (SPEC §25).
 *
 * Signing is asymmetric on purpose. The verification key is published inside the
 * evidence package, so an integrity check can be performed by anyone holding the
 * package — a trusted third party, a lawyer, a court officer — without giving
 * them any secret material, and without SafeSignal having to run a server to
 * answer "is this intact?".
 *
 * A symmetric MAC would force the verifier to hold a secret too, which either
 * means shipping the secret with the package (defeating the purpose) or calling
 * home to SafeSignal infrastructure (which the specification forbids as a
 * dependency of evidence handling).
 */
class ManifestSigner(
    private val keyProvider: KeyProvider,
    private val signingKeyAlias: String = DEFAULT_SIGNING_KEY_ALIAS,
) {
    fun sign(manifest: RecordingManifest): SignedManifest =
        EvidenceIntegrity.sign(manifest, keyProvider, signingKeyAlias)

    /**
     * Verifies using the key embedded in the signed manifest.
     *
     * Note the security boundary this implies: the embedded public key proves
     * internal consistency (the signature matches the manifest and the key), not
     * provenance (that *this* key belongs to the device that recorded). For
     * provenance a verifier must compare the embedded key against one obtained
     * out of band from the device owner. This distinction is called out in
     * LEGAL_DISCLAIMER.md rather than glossed over.
     */
    fun verify(signed: SignedManifest): Boolean = EvidenceIntegrity.verifySignature(signed)

    /** Public key, base64 encoded, for publication. */
    fun verificationKey(): String {
        keyProvider.ensureSigningKey(signingKeyAlias)
        return Base64.getEncoder().encodeToString(keyProvider.signingPublicKey(signingKeyAlias))
    }

    fun ensureKey() = keyProvider.ensureSigningKey(signingKeyAlias)

    fun hasKey(): Boolean = keyProvider.containsSigningKey(signingKeyAlias)

    companion object {
        const val DEFAULT_SIGNING_KEY_ALIAS = "safesignal.manifest.v1"
    }
}