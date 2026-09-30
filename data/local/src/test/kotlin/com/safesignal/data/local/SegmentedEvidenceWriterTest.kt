package com.safesignal.data.local

import com.safesignal.core.crypto.CryptoException
import com.safesignal.core.crypto.EvidenceIntegrity
import com.safesignal.core.crypto.ManifestSigner
import com.safesignal.core.crypto.RecordingKeyManager
import com.safesignal.core.crypto.RecordingManifest
import com.safesignal.core.crypto.SegmentDigest
import com.safesignal.core.crypto.testing.InMemoryKeyProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Segmented evidence and crash-safety tests (SPEC §17, §75, §87, §88).
 *
 * These tests are the executable form of two promises:
 *
 *  * a crash must not invalidate previously committed segments, and
 *  * nothing that was ever committed is ever silently deleted or overwritten.
 */
class SegmentedEvidenceWriterTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val keyProvider = InMemoryKeyProvider()
    private val keyManager = RecordingKeyManager(keyProvider)
    private val recordingId = "rec-test-0001"

    private fun writerIn(root: File, dirName: String = recordingId): Pair<SegmentedEvidenceWriter, File> {
        val dir = File(root, dirName)
        val material = keyManager.createRecordingKeyMaterial(recordingId)
        return SegmentedEvidenceWriter(dir, recordingId, material) to dir
    }

    private fun payload(marker: Int, size: Int = 4096): ByteArray =
        ByteArray(size) { index -> ((index + marker) % 251).toByte() }

    @Test
    fun `a committed segment reads back byte-identically`() {
        val root = temporaryFolder.newFolder("evidence")
        val (writer, _) = writerIn(root)
        val data = payload(1)

        val committed = writer.commitSegment(data, 0)

        assertArrayEqualsBytes(data, writer.readSegment(committed))
        assertEquals(data.size.toLong(), committed.plaintextLengthBytes)
        assertTrue(committed.sealedLengthBytes > committed.plaintextLengthBytes)
    }

    @Test
    fun `segment files carry the SafeSignal container magic`() {
        val root = temporaryFolder.newFolder("evidence")
        val (writer, dir) = writerIn(root)
        writer.commitSegment(payload(2), 0)

        val file = dir.listFiles()!!.single()
        assertEquals("segment-000000.ssg", file.name)
        val header = file.readBytes().take(4).toByteArray().toString(Charsets.US_ASCII)
        assertEquals("SSEG", header)
    }

    @Test
    fun `multiple segments commit independently and keep their order`() {
        val root = temporaryFolder.newFolder("evidence")
        val (writer, _) = writerIn(root)

        val committed = (0 until 4).map { index ->
            writer.commitSegment(payload(index + 3), index)
        }

        assertEquals(listOf(0, 1, 2, 3), committed.map { it.sequenceNumber })
        assertEquals(3, writer.highestCommittedSequence())
        committed.forEach { segment ->
            assertArrayEqualsBytes(
                payload(segment.sequenceNumber + 3),
                writer.readSegment(segment),
            )
        }
    }

    @Test
    fun `an uncommitted temporary file is never mistaken for evidence`() {
        val root = temporaryFolder.newFolder("evidence")
        val (writer, dir) = writerIn(root)
        writer.commitSegment(payload(4), 0)

        // Simulate a crash between "write tmp" and "atomic rename".
        File(dir, "segment-000001.ssg.tmp").writeBytes(ByteArray(64) { 9 })

        assertEquals("only committed segments count", 0, writer.highestCommittedSequence())
    }

    @Test
    fun `a committed segment is never overwritten`() {
        val root = temporaryFolder.newFolder("evidence")
        val (writer, _) = writerIn(root)
        writer.commitSegment(payload(5), 0)

        val error = assertThrows(java.io.IOException::class.java) {
            writer.commitSegment(payload(6), 0)
        }
        assertTrue(error.message!!.contains("Refusing to overwrite"))
    }

    @Test
    fun `tampering with a sealed segment fails closed on read`() {
        val root = temporaryFolder.newFolder("evidence")
        val (writer, dir) = writerIn(root)
        val committed = writer.commitSegment(payload(7), 0)

        val file = File(dir, committed.fileName)
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
        file.writeBytes(bytes)

        assertThrows(CryptoException.AuthenticationFailed::class.java) {
            writer.readSegment(committed)
        }
    }

    @Test
    fun `a manifest built from committed segments verifies`() {
        val root = temporaryFolder.newFolder("evidence")
        val (writer, _) = writerIn(root)
        val committed = (0 until 3).map { writer.commitSegment(payload(it + 10), it) }

        val digests = committed.map {
            SegmentDigest(
                sequenceNumber = it.sequenceNumber,
                segmentId = it.segmentId,
                sealedLengthBytes = it.sealedLengthBytes,
                plaintextLengthBytes = it.plaintextLengthBytes,
                sha256 = it.sha256,
            )
        }
        val manifest = EvidenceIntegrity.buildManifest(
            recordingId = recordingId,
            appVersion = "0.1.0",
            audioFormat = "wav",
            sampleRateHz = 48_000,
            channels = 1,
            segments = digests,
            keyVersion = 1,
            keyProvider = "InMemoryKeyProvider(TEST-ONLY)",
            startedAtWallClockMillis = 1_000L,
            endedAtWallClockMillis = 2_000L,
            startedElapsedRealtimeMillis = 10L,
            endedElapsedRealtimeMillis = 1_010L,
            activationSource = "VOICE",
            activationConfidence = 0.9f,
            wakeWordEngineVersion = "local-template-1.0.0",
            deviceTimezoneId = "UTC",
            isTestRecording = false,
        )

        val report = EvidenceIntegrity.verify(manifest, digests)
        assertTrue("intact package must verify: ${report.issues}", report.isIntact)
        assertTrue(manifest.digest().isNotBlank())
    }

    @Test
    fun `a manifest detects a missing segment`() {
        val root = temporaryFolder.newFolder("evidence")
        val (writer, _) = writerIn(root)
        val committed = (0 until 3).map { writer.commitSegment(payload(it + 20), it) }
        val digests = committed.map { digestOf(it) }
        val manifest = manifestOf(digests)

        val withoutMiddle = digests.filterNot { it.sequenceNumber == 1 }
        val report = EvidenceIntegrity.verify(manifest, withoutMiddle)

        assertTrue(!report.isIntact)
        assertTrue(report.issues.any { it.userMessage().contains("missing segments 1") })
    }

    @Test
    fun `a manifest detects a reordered segment`() {
        val root = temporaryFolder.newFolder("evidence")
        val (writer, _) = writerIn(root)
        val digests = (0 until 3).map { digestOf(writer.commitSegment(payload(it + 30), it)) }
        val manifest = manifestOf(digests)

        val swapped = listOf(digests[0], digests[2], digests[1])
        val report = EvidenceIntegrity.verify(manifest, swapped)

        assertTrue(!report.isIntact)
        assertTrue(report.issues.any { it.userMessage().contains("order incorrect") })
    }

    @Test
    fun `aggregate hash is insensitive to input iteration order`() {
        // Canonicalisation is deliberate: the same evidence must produce the same
        // manifest regardless of how the caller happened to iterate its segments.
        val a = SegmentDigest(0, "a", 1, 1, "aaaa")
        val b = SegmentDigest(1, "b", 1, 1, "bbbb")
        assertEquals(
            EvidenceIntegrity.aggregateRecordingHash(listOf(a, b)),
            EvidenceIntegrity.aggregateRecordingHash(listOf(b, a)),
        )
    }

    @Test
    fun `aggregate hash is sensitive to sequence numbers, so swapping order changes it`() {
        val a = SegmentDigest(0, "a", 1, 1, "aaaa")
        val b = SegmentDigest(1, "b", 1, 1, "bbbb")
        val swapped = listOf(b.copy(sequenceNumber = 0), a.copy(sequenceNumber = 1))
        assertNotEquals(
            EvidenceIntegrity.aggregateRecordingHash(listOf(a, b)),
            EvidenceIntegrity.aggregateRecordingHash(swapped),
        )
    }

    @Test
    fun `a signed manifest verifies and a tampered manifest does not`() {
        val root = temporaryFolder.newFolder("evidence")
        val (writer, _) = writerIn(root)
        val digests = (0 until 2).map { digestOf(writer.commitSegment(payload(it + 40), it)) }
        val manifest = manifestOf(digests)

        val signer = ManifestSigner(keyProvider)
        val signed = signer.sign(manifest)
        assertTrue("a freshly signed manifest must verify", EvidenceIntegrity.verifySignature(signed))

        val tampered = signed.copy(
            manifest = signed.manifest.copy(recordingSha256 = "0000000000000000000000000000000000000000000000000000000000000000"),
        )
        assertTrue(
            "a manifest altered after signing must fail verification",
            !EvidenceIntegrity.verifySignature(tampered),
        )
    }

    @Test
    fun `manifest serialisation round-trips`() {
        val root = temporaryFolder.newFolder("evidence")
        val (writer, _) = writerIn(root)
        val manifest = manifestOf((0 until 2).map { digestOf(writer.commitSegment(payload(it), it)) })

        val restored = RecordingManifest.fromJson(manifest.toCanonicalJson())
        assertEquals(manifest, restored)
        assertEquals("canonical form must be byte-stable", manifest.digest(), restored.digest())
    }

    private fun manifestOf(digests: List<SegmentDigest>) = EvidenceIntegrity.buildManifest(
        recordingId = recordingId,
        appVersion = "0.1.0",
        audioFormat = "wav",
        sampleRateHz = 48_000,
        channels = 1,
        segments = digests,
        keyVersion = 1,
        keyProvider = "InMemoryKeyProvider(TEST-ONLY)",
        startedAtWallClockMillis = 1_000L,
        endedAtWallClockMillis = 2_000L,
        startedElapsedRealtimeMillis = 10L,
        endedElapsedRealtimeMillis = 1_010L,
        activationSource = "VOICE",
        activationConfidence = 0.9f,
        wakeWordEngineVersion = "local-template-1.0.0",
        deviceTimezoneId = "UTC",
        isTestRecording = false,
    )

    private fun digestOf(committed: CommittedSegmentFile) = SegmentDigest(
        sequenceNumber = committed.sequenceNumber,
        segmentId = committed.segmentId,
        sealedLengthBytes = committed.sealedLengthBytes,
        plaintextLengthBytes = committed.plaintextLengthBytes,
        sha256 = committed.sha256,
    )

    private fun assertArrayEqualsBytes(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            if (expected[i] != actual[i]) {
                throw AssertionError("byte $i differs")
            }
        }
    }
}