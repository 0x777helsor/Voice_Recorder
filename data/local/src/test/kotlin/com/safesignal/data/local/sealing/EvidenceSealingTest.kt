package com.safesignal.data.local.sealing

import com.safesignal.core.crypto.EvidenceIntegrity
import com.safesignal.core.crypto.IntegrityIssue
import com.safesignal.core.crypto.ManifestSigner
import com.safesignal.core.crypto.SegmentDigest
import com.safesignal.core.crypto.WrappedKey
import com.safesignal.core.crypto.testing.InMemoryKeyProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seal → read-back → verify round trip, and what it catches.
 *
 * This could not have existed before: `stop()` produced a finalized capture and
 * nothing ever built, signed, stored or verified a manifest. `ManifestSigner` was
 * wired into Hilt with exactly one caller, a unit test using a fake key.
 *
 * Every test uses the **real** [InMemoryKeyProvider] and the **real**
 * [EvidenceIntegrity], so a mistake in canonical serialisation, digest folding or
 * signature verification fails here rather than in a court.
 *
 * The tamper tests matter most. A verifier that always returned `true` would pass
 * every "is it intact" test in the world, so each negative case asserts a specific
 * [EvidenceIntegrity.IntegrityIssue] rather than merely `isIntact == false`.
 */
class EvidenceSealingTest {

    private val signer = ManifestSigner(InMemoryKeyProvider())
    private val sealer = EvidenceSealer(signer)

    @Test
    fun `a sealed recording verifies against its own segments`() {
        val capture = sealed(segments = 3)

        val signed = sealer.seal(capture, APP_VERSION, "Europe/London", null)
        val report = EvidenceIntegrity.verify(signed.manifest, capture.segments)

        assertTrue("sealed recording must verify: ${report.issues}", report.isIntact)
        assertEquals(3, report.segmentCount)
    }

    @Test
    fun `the signature verifies with the published public key`() {
        val capture = sealed(segments = 2)

        val signed = sealer.seal(capture, APP_VERSION, "Europe/London", null)

        assertTrue(
            "a third party holding only the package must be able to verify",
            EvidenceIntegrity.verifySignature(signed),
        )
    }

    @Test
    fun `the verification key is non-empty`() {
        // The bug that shipped once: reading the *private* key's encoding, which
        // Android Keystore returns as null. That would have published an empty
        // verification key inside every evidence package.
        val signed = sealer.seal(sealed(segments = 1), APP_VERSION, "Europe/London", null)

        val key = java.util.Base64.getDecoder().decode(signed.verificationKeyBase64)

        assertTrue("verification key must not be empty", key.isNotEmpty())
    }

    @Test
    fun `a manifest survives serialise and deserialise and still verifies`() {
        val capture = sealed(segments = 3)

        val signed = sealer.seal(capture, APP_VERSION, "Europe/London", null)
        val roundTripped = EvidenceIntegrity.deserialise(EvidenceIntegrity.serialise(signed))

        assertEquals(signed.manifest, roundTripped.manifest)
        assertEquals(signed.signatureBase64, roundTripped.signatureBase64)
        assertTrue(
            "canonical bytes must be byte-identical after a round trip",
            EvidenceIntegrity.verifySignature(roundTripped),
        )
    }

    @Test
    fun `a manifest rewritten to match substituted audio fails verification`() {
        val capture = sealed(segments = 3)
        val signed = sealer.seal(capture, APP_VERSION, "Europe/London", null)

        // The attack the detached signature exists for: drop a segment and rewrite
        // the manifest so the aggregate hash agrees again. The signature was made
        // over the original bytes, so the forgery does not verify.
        val remaining = signed.manifest.segments.dropLast(1)
        val forged = signed.manifest.copy(
            segmentCount = remaining.size,
            segments = remaining,
            recordingSha256 = EvidenceIntegrity.aggregateRecordingHash(remaining),
        )

        assertFalse(
            "a rewritten manifest must not verify",
            EvidenceIntegrity.verifySignature(signed.copy(manifest = forged)),
        )
    }

    @Test
    fun `removing a segment is detected`() {
        val capture = sealed(segments = 3)
        val signed = sealer.seal(capture, APP_VERSION, "Europe/London", null)

        val withoutMiddle = capture.segments.filter { it.sequenceNumber != 1 }
        val report = EvidenceIntegrity.verify(signed.manifest, withoutMiddle)

        assertFalse(report.isIntact)
        assertTrue(
            "expected a count issue, got ${report.issues}",
            report.issues.any { it is IntegrityIssue.MissingOrExtraSegments },
        )
    }

    @Test
    fun `swapping two segments is detected even though the multiset is unchanged`() {
        val capture = sealed(segments = 3)
        val signed = sealer.seal(capture, APP_VERSION, "Europe/London", null)

        // The case that makes position-based comparison necessary. Sorting both
        // sides before comparing — which reads like a harmless tidy-up — would
        // normalise this away and report the recording as intact.
        val swapped = listOf(capture.segments[1], capture.segments[0], capture.segments[2])
        val report = EvidenceIntegrity.verify(signed.manifest, swapped)

        assertFalse("reordered segments must not report intact", report.isIntact)
        assertTrue(
            "expected a reordering issue, got ${report.issues}",
            report.issues.any { it is IntegrityIssue.ReorderedSegments },
        )
    }

    @Test
    fun `a single flipped character in one segment digest is detected`() {
        val capture = sealed(segments = 3)
        val signed = sealer.seal(capture, APP_VERSION, "Europe/London", null)

        val tampered = capture.segments.toMutableList()
        val original = tampered[1].sha256
        val flipped = if (original.first() == 'a') "b" + original.drop(1) else "a" + original.drop(1)
        tampered[1] = tampered[1].copy(sha256 = flipped)

        val report = EvidenceIntegrity.verify(signed.manifest, tampered)

        assertFalse(report.isIntact)
        assertTrue(
            "expected a checksum mismatch, got ${report.issues}",
            report.issues.any { it is IntegrityIssue.ChecksumMismatch },
        )
    }

    @Test
    fun `a test recording is labelled as one in the manifest`() {
        val signed = sealer.seal(sealed(segments = 1, isTest = true), APP_VERSION, "Europe/London", null)

        assertTrue(
            "test evidence must never be indistinguishable from real evidence",
            signed.manifest.isTestRecording,
        )
    }

    @Test
    fun `a real recording is not labelled as a test`() {
        val signed = sealer.seal(sealed(segments = 1, isTest = false), APP_VERSION, "Europe/London", null)

        assertFalse(signed.manifest.isTestRecording)
    }

    @Test
    fun `the aggregate hash is independent of input list order but not of sequence numbers`() {
        val digests = sealed(segments = 3).segments

        // Input order is normalised by design: the aggregate is a canonical value, so
        // the same segments must hash the same however the list is assembled.
        assertEquals(
            EvidenceIntegrity.aggregateRecordingHash(digests),
            EvidenceIntegrity.aggregateRecordingHash(digests.reversed()),
        )

        // What it *is* sensitive to is which sequence number a digest sits at. That
        // is what makes a reordering detectable at all: without the sequence number
        // folded in alongside each digest, two different orderings of the same
        // segments would produce one hash and a swap would go unnoticed.
        val relabelled = digests.map { it.copy(sequenceNumber = it.sequenceNumber + 10) }
        assertNotEquals(
            EvidenceIntegrity.aggregateRecordingHash(digests),
            EvidenceIntegrity.aggregateRecordingHash(relabelled),
        )
    }

    @Test
    fun `swapping two segments and relabelling them still changes the aggregate`() {
        // The strongest form of the reordering test: the segments are the same set
        // with the same digests, only the positions differ.
        val digests = sealed(segments = 3).segments
        val original = EvidenceIntegrity.aggregateRecordingHash(digests)

        val swapped = listOf(
            digests[0].copy(sequenceNumber = 1),
            digests[1].copy(sequenceNumber = 0),
            digests[2],
        )
        assertNotEquals(original, EvidenceIntegrity.aggregateRecordingHash(swapped))
    }

    @Test
    fun `an empty recording produces a manifest with zero segments, not a crash`() {
        // A recording that captured nothing is a real outcome, not an error. It must
        // seal honestly: zero segments, zero bytes, intact on its own terms.
        val signed = sealer.seal(sealed(segments = 0), APP_VERSION, "Europe/London", null)

        assertEquals(0, signed.manifest.segmentCount)
        assertEquals(0L, signed.manifest.totalSealedBytes)
        assertTrue(EvidenceIntegrity.verifySignature(signed))
    }

    @Test
    fun `the manifest carries the digests the engine computed`() {
        // Sealing runs after the data key has been zeroed, so the segments cannot be
        // read back here. The digests must come from the engine untouched, and the
        // manifest must hold exactly those.
        val capture = sealed(segments = 3)

        val signed = sealer.seal(capture, APP_VERSION, "Europe/London", null)

        assertEquals(
            capture.segments.map { it.sha256 }.sorted(),
            signed.manifest.segments.map { it.sha256 }.sorted(),
        )
    }

    @Test
    fun `a full segment is complete and a short tail is not`() {
        // Every segment filled its 5-second duration, so the tail is complete too.
        val full = sealed(segments = 3, plaintextBytes = 480_000)
        assertTrue(full.isSegmentComplete(0))
        assertTrue(full.isSegmentComplete(2))

        // The last segment is short because the recording stopped mid-segment. That
        // is expected, and a reader must be able to tell it apart from truncation.
        val withShortTail = sealed(segments = 3, plaintextBytes = 192_000)
        assertFalse(withShortTail.isSegmentComplete(2))
    }

    @Test
    fun `a non-final segment is always complete regardless of its length`() {
        // A middle segment cannot be partial: the engine only ever writes a short
        // segment at stop. Marking one incomplete would send a reader looking for
        // truncation that never happened.
        val capture = sealed(segments = 3, plaintextBytes = 1_000)

        assertTrue(capture.isSegmentComplete(0))
        assertTrue(capture.isSegmentComplete(1))
        assertFalse(capture.isSegmentComplete(2))
    }

    @Test
    fun `an integrity report names the specific problem rather than failing vaguely`() {
        val capture = sealed(segments = 2)
        val signed = sealer.seal(capture, APP_VERSION, "Europe/London", null)

        val summary = EvidenceIntegrity.verify(signed.manifest, emptyList()).userSummary()

        assertFalse(summary.contains("verified", ignoreCase = true))
        assertTrue("summary must be specific: $summary", summary.contains("segment"))
    }

    @Test
    fun `the wrapped data key travels with the capture`() {
        // Without it the audio is unrecoverable: the plaintext key is zeroed at stop
        // and this ciphertext is the only remaining path back to it.
        val capture = sealed(segments = 1)

        assertEquals(32, capture.wrappedKey.wrappedKeyBytes.size)
        assertTrue(capture.wrappedKey.iv.isNotEmpty())
    }

    private fun sealed(
        segments: Int,
        isTest: Boolean = false,
        plaintextBytes: Long = 480_000,
    ) = RecordingToSeal(
        recordingId = "rec_test_0123456789abcdef",
        segments = List(segments) { index -> digest(index, plaintextBytes) },
        audioFormat = "PCM_WAV",
        sampleRateHz = 48_000,
        channels = 1,
        startedAtWallClockMillis = 1_700_000_000_000L,
        endedAtWallClockMillis = 1_700_000_000_000L + segments * 5_000L,
        startedElapsedRealtimeMillis = 1_000L,
        endedElapsedRealtimeMillis = 1_000L + segments * 5_000L,
        activationSource = if (isTest) "TEST" else "IN_APP_BUTTON",
        activationConfidence = null,
        isTestRecording = isTest,
        durationMillis = segments * 5_000L,
        totalSealedBytes = segments * (plaintextBytes + 80),
        fullSegmentBytes = 480_000L,
        wrappedKey = WrappedKey(
            wrappedKeyBytes = ByteArray(32) { it.toByte() },
            algorithm = "AES/GCM/NoPadding",
            keyVersion = 1,
            iv = ByteArray(12) { 0 },
            provider = "InMemoryKeyProvider",
        ),
    )

    private fun digest(index: Int, plaintextBytes: Long) = SegmentDigest(
        sequenceNumber = index,
        segmentId = "segid_$index",
        sealedLengthBytes = plaintextBytes + 80,
        plaintextLengthBytes = plaintextBytes,
        sha256 = "%064x".format(index + 1),
    )

    private companion object {
        const val APP_VERSION = "1.0.0-test"
    }
}
