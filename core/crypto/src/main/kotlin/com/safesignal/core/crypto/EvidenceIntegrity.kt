package com.safesignal.core.crypto

import com.safesignal.core.common.json.JsonValue
import com.safesignal.core.common.json.MiniJson
import com.safesignal.core.common.json.asObject
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

/**
 * Builds and verifies evidence manifests (SPEC §25, §88).
 *
 * Two independent integrity mechanisms, deliberately:
 *
 *  * **Per-segment SHA-256 inside the manifest.** Detects missing, reordered and
 *    duplicated segments without any key material. A third party holding only the
 *    export package can run this.
 *  * **Detached ECDSA signature over the canonical manifest.** Detects a manifest
 *    that has been rewritten to match corrupted audio. Because it is asymmetric,
 *    verification needs only the *public* key, which ships inside the package.
 *
 * The design deliberately does **not** claim legal admissibility. A signature
 * shows that a manifest has not been altered since it was produced by a device
 * holding a particular key. Whether that satisfies a court is a question of
 * jurisdiction and case law that this software cannot answer. See
 * LEGAL_DISCLAIMER.md.
 */
object EvidenceIntegrity {

    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

    /**
     * Aggregates the whole recording into one hash.
     *
     * Segments are folded in order, and the sequence number is hashed alongside
     * each digest. That matters: hashing only the concatenated digests would let
     * two different orderings of the same segments produce the same aggregate,
     * so reordering would go undetected.
     */
    fun aggregateRecordingHash(segmentDigests: List<SegmentDigest>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        segmentDigests.sortedBy { it.sequenceNumber }.forEach { segment ->
            digest.update(SEGMENT_FOLD_PREFIX)
            digest.update(segment.sequenceNumber.toString().toByteArray(Charsets.US_ASCII))
            digest.update(SEGMENT_FOLD_SEPARATOR)
            digest.update(segment.sha256.toLowerCaseHex().toByteArray(Charsets.US_ASCII))
            digest.update(SEGMENT_FOLD_SEPARATOR)
        }
        return hex(digest.digest())
    }

    fun buildManifest(
        recordingId: String,
        appVersion: String,
        audioFormat: String,
        sampleRateHz: Int,
        channels: Int,
        segments: List<SegmentDigest>,
        keyVersion: Int,
        keyProvider: String,
        startedAtWallClockMillis: Long,
        endedAtWallClockMillis: Long,
        startedElapsedRealtimeMillis: Long,
        endedElapsedRealtimeMillis: Long,
        activationSource: String,
        activationConfidence: Float?,
        wakeWordEngineVersion: String?,
        deviceTimezoneId: String,
        isTestRecording: Boolean,
        manifestVersion: Int = MANIFEST_VERSION,
        encryptionVersion: Int = ENCRYPTION_VERSION,
    ): RecordingManifest {
        val ordered = segments.sortedBy { it.sequenceNumber }
        return RecordingManifest(
            recordingId = recordingId,
            manifestVersion = manifestVersion,
            appVersion = appVersion,
            encryptionVersion = encryptionVersion,
            keyVersion = keyVersion,
            keyProvider = keyProvider,
            audioFormat = audioFormat,
            sampleRateHz = sampleRateHz,
            channels = channels,
            segmentCount = ordered.size,
            totalSealedBytes = ordered.sumOf { it.sealedLengthBytes },
            totalPlaintextBytes = ordered.sumOf { it.plaintextLengthBytes },
            recordingSha256 = aggregateRecordingHash(ordered),
            startedAtWallClockMillis = startedAtWallClockMillis,
            endedAtWallClockMillis = endedAtWallClockMillis,
            startedElapsedRealtimeMillis = startedElapsedRealtimeMillis,
            endedElapsedRealtimeMillis = endedElapsedRealtimeMillis,
            activationSource = activationSource,
            activationConfidence = activationConfidence,
            wakeWordEngineVersion = wakeWordEngineVersion,
            deviceTimezoneId = deviceTimezoneId,
            segments = ordered,
            isTestRecording = isTestRecording,
        )
    }

    /** Signs the manifest with a Keystore-held key. */
    fun sign(
        manifest: RecordingManifest,
        keyProvider: KeyProvider,
        signingKeyAlias: String,
    ): SignedManifest {
        keyProvider.ensureSigningKey(signingKeyAlias)
        val signature = keyProvider.sign(signingKeyAlias, manifest.canonicalBytes())
        return SignedManifest(
            manifest = manifest,
            signatureBase64 = Base64.getEncoder().encodeToString(signature),
            signingKeyAlias = signingKeyAlias,
            verificationKeyBase64 = Base64.getEncoder()
                .encodeToString(keyProvider.signingPublicKey(signingKeyAlias)),
        )
    }

    /**
     * Verifies a signature against a public key.
     *
     * @param publicKeyBase64 the key published alongside the manifest.
     */
    fun verifySignature(signed: SignedManifest, publicKeyBase64: String = signed.verificationKeyBase64): Boolean =
        verifySignature(signed.manifest, signed.signatureBase64, publicKeyBase64)

    fun verifySignature(
        manifest: RecordingManifest,
        signatureBase64: String,
        publicKeyBase64: String,
    ): Boolean = try {
        val keyBytes = Base64.getDecoder().decode(publicKeyBase64)
        val signatureBytes = Base64.getDecoder().decode(signatureBase64)
        val publicKey: PublicKey = KeyFactoryProvider.forEc().generatePublic(
            java.security.spec.X509EncodedKeySpec(keyBytes),
        )
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initVerify(publicKey)
            update(manifest.canonicalBytes())
            verify(signatureBytes)
        }
    } catch (e: Exception) {
        // A malformed key or signature is a verification failure, not a crash.
        false
    }

    /**
     * Full verification of a reconstructed recording against its manifest.
     *
     * Callers pass the segment digests they actually hold. Every structural
     * problem the specification asks about is reported as a distinct, named
     * issue so the UI and the support bundle can say *what* is wrong rather than
     * a bare "verification failed".
     */
    fun verify(
        manifest: RecordingManifest,
        actualSegments: List<SegmentDigest>,
    ): IntegrityReport {
        val issues = buildList {
            // The manifest defines canonical order. The actual list is compared
            // POSITIONALLY and deliberately NOT sorted: sorting both sides would
            // normalise a reordered reconstruction back into the expected order
            // and silently hide exactly the tampering we need to detect.
            val expected = manifest.segments.sortedBy { it.sequenceNumber }
            val actual = actualSegments

            if (expected.size != actual.size) {
                add(
                    IntegrityIssue.MissingOrExtraSegments(
                        expected = expected.size,
                        actual = actual.size,
                    ),
                )
            }

            expected.zip(actual).forEach { (exp, act) ->
                if (exp.sequenceNumber != act.sequenceNumber) {
                    add(IntegrityIssue.ReorderedSegments(atSequence = exp.sequenceNumber))
                }
                if (exp.segmentId != act.segmentId) {
                    add(IntegrityIssue.SegmentIdMismatch(atSequence = exp.sequenceNumber))
                }
                if (exp.sealedLengthBytes != act.sealedLengthBytes) {
                    add(IntegrityIssue.LengthMismatch(atSequence = exp.sequenceNumber))
                }
                if (!exp.sha256.equals(act.sha256, ignoreCase = true)) {
                    add(IntegrityIssue.ChecksumMismatch(atSequence = exp.sequenceNumber))
                }
            }

            val expectedNumbers = expected.map { it.sequenceNumber }.toSet()
            val missing = expectedNumbers - actual.map { it.sequenceNumber }.toSet()
            if (missing.isNotEmpty()) {
                add(IntegrityIssue.MissingSegments(missing.sorted()))
            }

            val duplicates = actual
                .groupBy { it.sequenceNumber }
                .filterValues { it.size > 1 }
                .keys
                .sorted()
            if (duplicates.isNotEmpty()) {
                add(IntegrityIssue.DuplicateSegments(duplicates))
            }

            val recomputedAggregate = aggregateRecordingHash(actual)
            if (!recomputedAggregate.equals(manifest.recordingSha256, ignoreCase = true)) {
                add(IntegrityIssue.RecordingHashMismatch)
            }

            if (actual.sumOf { it.sealedLengthBytes } != manifest.totalSealedBytes && actual.size == expected.size) {
                add(IntegrityIssue.TotalLengthMismatch)
            }
        }

        return IntegrityReport(
            recordingId = manifest.recordingId,
            issues = issues,
            segmentCount = actualSegments.size,
            verifiedAtManifestDigest = manifest.digest(),
        )
    }

    /**
     * Serialises a [SignedManifest] into the `integrity/manifest.json` layout
     * described in SPEC §26.
     */
    fun serialise(signed: SignedManifest): String = MiniJson.write(
        JsonValue.Obj(
            linkedMapOf(
                "manifest" to MiniJson.parse(signed.manifest.toCanonicalJson()),
                "signature" to JsonValue.Obj(
                    linkedMapOf(
                        "algorithm" to JsonValue.Str(SIGNATURE_ALGORITHM),
                        "value" to JsonValue.Str(signed.signatureBase64),
                        "keyAlias" to JsonValue.Str(signed.signingKeyAlias),
                        "verificationKey" to JsonValue.Str(signed.verificationKeyBase64),
                    ),
                ),
            ),
        ),
    )

    /** Reads back the `integrity/manifest.json` layout. */
    fun deserialise(text: String): SignedManifest {
        val root = MiniJson.parse(text).asObject()
        val manifestJson = root.fields["manifest"] ?: throw CryptoException.MalformedCiphertext("manifest missing")
        val signature = root.fields["signature"]?.asObject()
            ?: throw CryptoException.MalformedCiphertext("signature block missing")
        return SignedManifest(
            manifest = RecordingManifest.fromJson(MiniJson.write(manifestJson)),
            signatureBase64 = signature.requiredStringOf("value"),
            signingKeyAlias = signature.requiredStringOf("keyAlias"),
            verificationKeyBase64 = signature.requiredStringOf("verificationKey"),
        )
    }

    private fun JsonValue.Obj.requiredStringOf(key: String): String =
        (fields[key] as? JsonValue.Str)?.value
            ?: throw CryptoException.MalformedCiphertext("signature.$key missing")

    private fun String.toLowerCaseHex(): String = lowercase()

    private fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }

    const val MANIFEST_VERSION = 1
    const val ENCRYPTION_VERSION = 1

    private val SEGMENT_FOLD_PREFIX = "safesignal.segment.v1:".toByteArray(Charsets.US_ASCII)
    private val SEGMENT_FOLD_SEPARATOR = byteArrayOf(0x0A)
    private val HEX = "0123456789abcdef".toCharArray()
}

/** Small indirection so the KeyFactory algorithm string is stated once. */
private object KeyFactoryProvider {
    fun forEc(): java.security.KeyFactory = java.security.KeyFactory.getInstance("EC")
}

data class IntegrityReport(
    val recordingId: String,
    val issues: List<IntegrityIssue>,
    val segmentCount: Int,
    val verifiedAtManifestDigest: String,
) {
    val isIntact: Boolean get() = issues.isEmpty()

    /** Short, user-facing summary. Never claims legal conclusiveness. */
    fun userSummary(): String = when {
        isIntact -> "Integrity verified. $segmentCount segments match the signed manifest."
        else -> "Integrity problems found: ${issues.map { it.userMessage() }.joinToString("; ")}."
    }
}

sealed interface IntegrityIssue {
    fun userMessage(): String

    data class MissingOrExtraSegments(val expected: Int, val actual: Int) : IntegrityIssue {
        override fun userMessage() = "expected $expected segments but found $actual"
    }

    data class MissingSegments(val sequenceNumbers: List<Int>) : IntegrityIssue {
        override fun userMessage() = "missing segments ${sequenceNumbers.joinToString(", ")}"
    }

    data class DuplicateSegments(val sequenceNumbers: List<Int>) : IntegrityIssue {
        override fun userMessage() = "duplicated segments ${sequenceNumbers.joinToString(", ")}"
    }

    data class ReorderedSegments(val atSequence: Int) : IntegrityIssue {
        override fun userMessage() = "segment order incorrect at position $atSequence"
    }

    data class SegmentIdMismatch(val atSequence: Int) : IntegrityIssue {
        override fun userMessage() = "segment identity mismatch at position $atSequence"
    }

    data class LengthMismatch(val atSequence: Int) : IntegrityIssue {
        override fun userMessage() = "segment length mismatch at position $atSequence"
    }

    data class ChecksumMismatch(val atSequence: Int) : IntegrityIssue {
        override fun userMessage() = "content checksum mismatch at position $atSequence"
    }

    data object RecordingHashMismatch : IntegrityIssue {
        override fun userMessage() = "whole-recording hash does not match the manifest"
    }

    data object TotalLengthMismatch : IntegrityIssue {
        override fun userMessage() = "total byte count does not match the manifest"
    }
}