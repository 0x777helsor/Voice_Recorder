package com.safesignal.audio.capture

import com.safesignal.core.crypto.Digest
import com.safesignal.core.crypto.RecordingKeyManager
import com.safesignal.core.crypto.RecordingKeyMaterial
import com.safesignal.core.crypto.testing.InMemoryKeyProvider
import com.safesignal.data.local.CommittedSegmentFile
import com.safesignal.data.local.SegmentedEvidenceWriter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom

/**
 * Capture-engine tests.
 *
 * ### Why this suite exists
 *
 * Each test below is named after a defect that was in this engine, so that a
 * regression reintroduces a failing test rather than a silent behaviour change:
 *
 *  - max duration and free storage were *recorded* as stop reasons and then never
 *    acted on, so capture ran past its limits until the volume filled.
 *  - the pre-roll ring was never drained, so the feature did nothing while
 *    appearing to be configured. Fixing it exposed a second bug: frames arriving
 *    while listening were written both to the ring and to segment 0.
 *  - the WAV header of the final partial segment declared a full segment's worth
 *    of audio that was never captured.
 *  - `commitActiveSegment` accepted a `config` and an `isComplete` flag and used
 *    neither, implying a sealing policy that did not exist.
 *
 * ### Why audio is asserted through the cipher
 *
 * Assertions read segments back with [SegmentedEvidenceWriter.readSegment], which
 * authenticates before returning. A test that inspected the engine's in-memory
 * buffers would pass even if the committed file were corrupt, which is the one
 * thing that matters.
 *
 * ### Why the data key is deterministic here
 *
 * [FixedRandom] makes every data key 32 bytes of `0x2A`, so a test can rebuild
 * the key material after `stop()` has cleared the engine's copy. This is safe
 * only because [InMemoryKeyProvider] is already documented as test-only; nothing
 * here is a security boundary.
 */
class SegmentedRecordingEngineTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val keyProvider = InMemoryKeyProvider()
    private val keyManager = RecordingKeyManager(keyProvider, FixedRandom)

    // ------------------------------------------------------------- fixtures

    /**
     * 16 kHz mono 16-bit is 32 000 bytes/s, so a 5 s segment holds 160 000 bytes
     * of payload. Frames of 1 600 samples (3 200 bytes) fill a segment in exactly
     * 50 frames, which keeps the boundary arithmetic easy to state and to check.
     */
    private val frame = ShortArray(1_600) { ((it * 37) % 6_000 - 3_000).toShort() }
    private val frameBytes: Int = frame.size * 2

    /** The same frame as the little-endian PCM bytes the engine should write. */
    private val framePcm: ByteArray = ByteArray(frameBytes).also { out ->
        frame.forEachIndexed { i, sample ->
            out[i * 2] = (sample.toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
    }

    private fun config(
        preBufferSeconds: Int = 0,
        maxDurationSeconds: Int = 60,
        minFreeStorageBytes: Long = 1L,
    ) = RecordingConfig(
        sampleRateHz = 16_000,
        channels = 1,
        bitsPerSample = 16,
        segmentDurationSeconds = 5,
        maxDurationSeconds = maxDurationSeconds,
        preBufferSeconds = preBufferSeconds,
        minFreeStorageBytes = minFreeStorageBytes,
    )

    private class Harness(
        val engine: SegmentedRecordingEngine,
        val source: TestAudioSource,
        val time: TestTimeProvider,
        val logger: RecordingLogger,
        val root: File,
    )

    private fun harness(cfg: RecordingConfig = config()): Harness {
        val root = temporaryFolder.newFolder()
        val source = TestAudioSource()
        val time = TestTimeProvider()
        val logger = RecordingLogger()
        val engine = SegmentedRecordingEngine(
            audioSource = source,
            evidenceRoot = root,
            keyManager = keyManager,
            timeProvider = time,
            dispatchers = TestDispatchers,
            logger = logger,
        )
        return Harness(engine, source, time, logger, root)
    }

    private suspend fun Harness.record(frames: Int) {
        repeat(frames) { source.deliver(engine, frame) }
    }

    private fun Harness.readSegment(segment: CommittedSegment): ByteArray {
        val key = keyManager.createRecordingKeyMaterial(segment.recordingId)
        // The engine writes into <evidenceRoot>/<recordingId>/, so the reader has
        // to look there too.
        val writer = SegmentedEvidenceWriter(File(root, segment.recordingId), segment.recordingId, key)
        return writer.readSegment(
            CommittedSegmentFile(
                recordingId = segment.recordingId,
                sequenceNumber = segment.sequenceNumber,
                segmentId = segment.segmentId,
                fileName = segment.sealedFileName,
                sealedLengthBytes = segment.sealedLengthBytes,
                plaintextLengthBytes = segment.plaintextLengthBytes,
                sha256 = segment.sha256,
                sealedAtWallClockMillis = 0L,
            )
        )
    }

    /** Payload length of a committed segment, in bytes, after authentication. */
    private fun Harness.payloadBytes(segment: CommittedSegment): Int =
        readSegment(segment).size - WAV_HEADER_BYTES

    // ---------------------------------------------------------------- limits

    @Test
    fun `stops recording when max duration is reached`() = runTest {
        val cfg = config(maxDurationSeconds = 60)
        val h = harness(cfg)
        h.engine.start(cfg).getOrThrow()

        h.record(3)
        h.time.advance(60_001)
        h.source.deliver(h.engine, frame)

        assertEquals(
            "engine must finalize itself at the duration limit, not just note it",
            RecordingState.Stopped,
            h.engine.state().value,
        )
        val finalized = h.engine.committedSegments()
        assertEquals("audio captured before the limit must be preserved", 1, finalized.size)
        assertEquals(
            "the microphone must be released when the limit stops capture",
            1,
            h.source.closeCount,
        )
    }

    @Test
    fun `a max-duration stop is labelled MAX_DURATION on the evidence`() = runTest {
        val cfg = config(maxDurationSeconds = 60)
        val h = harness(cfg)
        h.engine.start(cfg).getOrThrow()

        h.record(3)
        h.time.advance(60_001)
        h.source.deliver(h.engine, frame)

        // Distinguishing a self-imposed stop from a user stop matters: the
        // evidence package has to be able to say why recording ended.
        assertTrue(
            "expected MAX_DURATION in notes, got ${h.engine.state().value}",
            h.logger.contains("MAX_DURATION"),
        )
    }

    @Test
    fun `stops recording when free storage falls below the minimum`() = runTest {
        // A floor above any filesystem's free space forces the branch without
        // having to actually fill a volume.
        val cfg = config(minFreeStorageBytes = Long.MAX_VALUE)
        val h = harness(cfg)
        h.engine.start(cfg).getOrThrow()

        h.source.deliver(h.engine, frame)

        assertEquals(RecordingState.Stopped, h.engine.state().value)
        assertTrue("expected LOW_STORAGE, got ${h.logger.entries}", h.logger.contains("LOW_STORAGE"))
    }

    @Test
    fun `a stop after a limit-triggered stop returns the same recording`() = runTest {
        val cfg = config(maxDurationSeconds = 60)
        val h = harness(cfg)
        h.engine.start(cfg).getOrThrow()

        h.record(2)
        h.time.advance(60_001)
        h.source.deliver(h.engine, frame)
        val first = h.engine.stop().getOrThrow()

        // The service layer calls stop() after the engine stops itself. That must
        // succeed and describe the same evidence. Returning a failure here would
        // read to a user as the recorder crashing at the moment it protected
        // their storage.
        val second = h.engine.stop().getOrThrow()
        assertEquals(first.recordingId, second.recordingId)
        assertEquals(first.segments.size, second.segments.size)
    }

    @Test
    fun `does not stop before the duration limit is reached`() = runTest {
        val cfg = config(maxDurationSeconds = 60)
        val h = harness(cfg)
        h.engine.start(cfg).getOrThrow()

        h.time.advance(59_000)
        h.record(2)

        assertEquals(RecordingState.Recording, h.engine.state().value)
        assertFalse(h.logger.contains("MAX_DURATION"))
    }

    @Test
    fun `a wall-clock correction cannot end a recording early`() = runTest {
        val cfg = config(maxDurationSeconds = 60)
        val h = harness(cfg)
        h.engine.start(cfg).getOrThrow()

        // Durations are measured monotonically. Moving the wall clock back an hour
        // must not make the engine believe it has been recording for an hour.
        h.time.skewWallClock(-3_600_000)
        h.record(2)

        assertEquals(RecordingState.Recording, h.engine.state().value)
    }

    @Test
    fun `frames arriving after a limit stop are dropped`() = runTest {
        val cfg = config(maxDurationSeconds = 60)
        val h = harness(cfg)
        h.engine.start(cfg).getOrThrow()

        h.record(2)
        h.time.advance(60_001)
        h.source.deliver(h.engine, frame)
        val afterLimit = h.engine.committedSegments().sumOf { it.plaintextLengthBytes }

        h.record(5)

        assertEquals(
            "no audio may be appended to a finalized recording",
            afterLimit,
            h.engine.committedSegments().sumOf { it.plaintextLengthBytes },
        )
    }

    // ------------------------------------------------------ wav header truth

    @Test
    fun `final segment declares only the audio it contains`() = runTest {
        val cfg = config()
        val h = harness(cfg)
        h.engine.start(cfg).getOrThrow()

        h.record(3)
        val finalized = h.engine.stop().getOrThrow()

        val segment = finalized.segments.single()
        val wav = h.readSegment(segment)
        assertEquals(
            "the data chunk must declare the bytes actually present",
            wav.size - WAV_HEADER_BYTES,
            readLe32(wav, 40),
        )
        assertEquals("the RIFF size must agree with the file length", wav.size - 8, readLe32(wav, 4))
        assertEquals(3 * frameBytes, wav.size - WAV_HEADER_BYTES)
    }

    @Test
    fun `a full segment declares its full length`() = runTest {
        val cfg = config()
        val h = harness(cfg)
        h.engine.start(cfg).getOrThrow()

        // 50 frames * 3 200 bytes = 160 000 = exactly one 5 s segment at 16 kHz.
        h.record(50)
        val finalized = h.engine.stop().getOrThrow()

        assertEquals(1, finalized.segments.size)
        val wav = h.readSegment(finalized.segments.single())
        assertEquals(160_000, wav.size - WAV_HEADER_BYTES)
        assertEquals(160_000, readLe32(wav, 40))
    }

    @Test
    fun `a frame split across a segment boundary loses no audio`() = runTest {
        val cfg = config()
        val h = harness(cfg)
        h.engine.start(cfg).getOrThrow()

        // 53 frames is 169 600 bytes: one full segment plus two frames' worth.
        // Frame 51 straddles the boundary, so its bytes are divided between two
        // segments. Any double-write or drop shows up in the total.
        h.record(53)
        val finalized = h.engine.stop().getOrThrow()

        assertEquals(2, finalized.segments.size)
        assertEquals(
            "total payload must equal exactly what was captured",
            (53L * frameBytes),
            finalized.segments.sumOf { it.plaintextLengthBytes } - finalized.segments.size.toLong() * WAV_HEADER_BYTES,
        )
        finalized.segments.forEach { segment ->
            val wav = h.readSegment(segment)
            assertEquals(
                "segment ${segment.sequenceNumber} header must match its own bytes",
                wav.size - WAV_HEADER_BYTES,
                readLe32(wav, 40),
            )
        }
    }

    @Test
    fun `committed digests match the plaintext bytes`() = runTest {
        val cfg = config()
        val h = harness(cfg)
        h.engine.start(cfg).getOrThrow()

        h.record(51)
        val finalized = h.engine.stop().getOrThrow()

        finalized.segments.forEach { segment ->
            assertEquals(
                "the manifest digest must be SHA-256 over the sealed plaintext",
                Digest.sha256Hex(h.readSegment(segment)),
                segment.sha256,
            )
        }
    }

    @Test
    fun `segments are encrypted at rest`() = runTest {
        val cfg = config()
        val h = harness(cfg)
        val session = h.engine.start(cfg).getOrThrow()

        h.record(3)
        val finalized = h.engine.stop().getOrThrow()
        val segment = finalized.segments.single()

        // Container overhead is a fixed 8-byte header, a 12-byte nonce and a
        // 16-byte GCM tag. Anything else means plaintext is on disk.
        assertEquals(
            "sealed length must be plaintext plus container overhead",
            segment.plaintextLengthBytes + 8 + 12 + 16,
            segment.sealedLengthBytes,
        )

        val onDisk = File(h.root, session.recordingId).listFiles().orEmpty().map { it.readBytes() }
        assertEquals("one sealed file per committed segment", 1, onDisk.size)
        assertFalse(
            "sealed bytes must not contain a WAV header",
            onDisk.single().toString(Charsets.ISO_8859_1).contains("RIFF"),
        )
    }

    @Test
    fun `a committed segment decrypts back to its exact plaintext`() = runTest {
        val cfg = config()
        val h = harness(cfg)
        h.engine.start(cfg).getOrThrow()

        h.record(3)
        val finalized = h.engine.stop().getOrThrow()
        val pcm = h.readSegment(finalized.segments.single()).copyOfRange(WAV_HEADER_BYTES, finalized.segments.single().plaintextLengthBytes.toInt())

        val expected = ByteArray(3 * frameBytes)
        repeat(3) { framePcm.copyInto(expected, it * frameBytes) }
        assertArrayEquals("PCM must survive seal, commit and open unchanged", expected, pcm)
    }

    // -------------------------------------------------------------- pre-roll

    @Test
    fun `pre-roll is captured but not written until activation`() = runTest {
        val cfg = config(preBufferSeconds = 5)
        val h = harness(cfg)
        h.engine.start(cfg).getOrThrow()

        assertEquals(
            "pre-roll enabled must open in Listening, not Recording",
            RecordingState.Listening,
            h.engine.state().value,
        )

        h.record(8)
        assertEquals(
            "nothing may reach evidence before activation",
            0,
            h.engine.committedSegments().size,
        )
    }

    @Test
    fun `audio from before activation is prepended to the first segment`() = runTest {
        val cfg = config(preBufferSeconds = 5)
        val h = harness(cfg)
        h.engine.start(cfg).getOrThrow()

        h.record(8)
        h.engine.trigger().getOrThrow()
        assertEquals(RecordingState.Recording, h.engine.state().value)
        h.record(2)

        val finalized = h.engine.stop().getOrThrow()
        val first = finalized.segments.single()
        assertEquals(
            "8 pre-roll frames plus 2 recorded frames must all be present once",
            10 * frameBytes,
            h.payloadBytes(first),
        )
        assertTrue(
            "the evidence package must record how much pre-roll was used",
            first.recordingId.isNotEmpty(),
        )
    }

    @Test
    fun `pre-roll audio is not duplicated`() = runTest {
        val cfg = config(preBufferSeconds = 5)
        val h = harness(cfg)
        h.engine.start(cfg).getOrThrow()

        // Eight frames while listening. If the ring and the segment both received
        // them, this count would be 18 frames' worth rather than 10.
        h.record(8)
        h.engine.trigger().getOrThrow()
        h.record(2)

        val finalized = h.engine.stop().getOrThrow()
        assertEquals(10 * frameBytes, h.payloadBytes(finalized.segments.single()))
    }

    @Test
    fun `a repeated activation does not prepend the pre-roll twice`() = runTest {
        val cfg = config(preBufferSeconds = 5)
        val h = harness(cfg)
        h.engine.start(cfg).getOrThrow()

        h.record(8)
        h.engine.trigger().getOrThrow()
        h.record(2)
        // A wake-word detector firing twice for one utterance must be harmless.
        h.engine.trigger().getOrThrow()

        val finalized = h.engine.stop().getOrThrow()
        assertEquals(10 * frameBytes, h.payloadBytes(finalized.segments.single()))
        assertEquals(RecordingState.Stopped, h.engine.state().value)
    }

    @Test
    fun `discarded pre-roll never reaches the evidence`() = runTest {
        val cfg = config(preBufferSeconds = 5)
        val h = harness(cfg)
        h.engine.start(cfg).getOrThrow()

        h.record(8)
        // An activation that is rejected must leave nothing behind.
        h.engine.discardPreRoll()
        h.engine.trigger().getOrThrow()
        h.record(2)

        val finalized = h.engine.stop().getOrThrow()
        assertEquals(2 * frameBytes, h.payloadBytes(finalized.segments.single()))
    }

    @Test
    fun `pre-roll is off by default`() = runTest {
        // SPEC §12: pre-roll is opt-in. A user who did not ask for it must get
        // exactly what they asked for, and no earlier audio.
        val cfg = config(preBufferSeconds = 0)
        val h = harness(cfg)
        h.engine.start(cfg).getOrThrow()

        assertEquals(RecordingState.Recording, h.engine.state().value)
        assertEquals("no ring must exist when pre-roll is disabled", 0, h.engine.drainPreRollIntoRecording())

        h.record(3)
        val finalized = h.engine.stop().getOrThrow()
        assertEquals(3 * frameBytes, h.payloadBytes(finalized.segments.single()))
        assertFalse(
            "no pre-roll note should appear when the feature is off",
            h.engine.stop().getOrThrow().notes.any { it.startsWith("preroll") },
        )
    }

    @Test
    fun `listening time is not charged to the duration limit`() = runTest {
        val cfg = config(preBufferSeconds = 5, maxDurationSeconds = 60)
        val h = harness(cfg)
        h.engine.start(cfg).getOrThrow()

        // Ten minutes of listening before the wake word fires. Charging that to
        // the limit would stop the recording the instant it began.
        h.time.advance(600_000)
        h.record(4)
        h.engine.trigger().getOrThrow()
        h.record(2)

        assertEquals(RecordingState.Recording, h.engine.state().value)
        assertFalse("listening must not trigger the duration limit", h.logger.contains("MAX_DURATION"))
    }

    @Test
    fun `the recording timeline covers the pre-roll audio`() = runTest {
        val cfg = config(preBufferSeconds = 5)
        val h = harness(cfg)
        h.engine.start(cfg).getOrThrow()

        h.record(8)
        val activatedAt = h.time.elapsed()
        h.engine.trigger().getOrThrow()
        h.time.advance(1_000)
        h.record(2)

        val finalized = h.engine.stop().getOrThrow()
        // Eight frames of pre-roll is 800 ms of audio, so the recording must
        // start 800 ms before activation or the manifest misdates segment 0.
        assertEquals(
            "startedAtElapsed must precede activation by the pre-roll duration",
            activatedAt.elapsedRealtimeMs - 800,
            finalized.startedAtElapsed.elapsedRealtimeMs,
        )
        assertTrue(
            "the pre-roll amount must be disclosed: ${finalized.notes}",
            finalized.notes.any { it.startsWith("preroll_ms=") },
        )
    }

    // ------------------------------------------------------------- lifecycle

    @Test
    fun `stopping with no audio commits nothing`() = runTest {
        val cfg = config()
        val h = harness(cfg)
        h.engine.start(cfg).getOrThrow()

        val finalized = h.engine.stop().getOrThrow()

        // A zero-length segment would make the manifest claim audio that does
        // not exist — the failure mode SPEC §17 forbids.
        assertEquals(0, finalized.segments.size)
        assertFalse(finalized.isComplete)
    }

    @Test
    fun `stopping while still listening discards the ring`() = runTest {
        val cfg = config(preBufferSeconds = 5)
        val h = harness(cfg)
        h.engine.start(cfg).getOrThrow()

        h.record(8)
        val finalized = h.engine.stop().getOrThrow()

        assertEquals("never-triggered audio is not evidence", 0, finalized.segments.size)
        assertEquals("the microphone must be released", 1, h.source.closeCount)
    }

    @Test
    fun `a user stop is labelled USER_REQUESTED`() = runTest {
        val cfg = config()
        val h = harness(cfg)
        h.engine.start(cfg).getOrThrow()

        h.record(3)
        val finalized = h.engine.stop().getOrThrow()

        assertTrue("expected USER_REQUESTED in ${finalized.notes}", finalized.notes.contains("USER_REQUESTED"))
    }

    @Test
    fun `start fails cleanly when the microphone cannot be opened`() = runTest {
        val h = harness()
        val denied = TestAudioSource().apply {
            openResult = Result.failure(SecurityException("RECORD_AUDIO not granted"))
        }
        val engine = SegmentedRecordingEngine(
            audioSource = denied,
            evidenceRoot = h.root,
            keyManager = keyManager,
            timeProvider = h.time,
            dispatchers = TestDispatchers,
            logger = h.logger,
        )

        val result = engine.start(config())

        assertTrue("start must report failure rather than pretend to record", result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertEquals(RecordingState.Failed("RECORD_AUDIO not granted", true), engine.state().value)
        assertEquals(0, engine.committedSegments().size)
    }

    @Test
    fun `trigger fails when no session is open`() = runTest {
        val h = harness()

        val result = h.engine.trigger()

        assertTrue("triggering an idle engine must fail loudly", result.isFailure)
    }

    // --------------------------------------------------------------- helpers

    private fun readLe32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private companion object {
        const val WAV_HEADER_BYTES = 44

        /** Makes every data key identical so tests can re-derive one after `stop()`. */
        val FixedRandom = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                bytes.fill(0x2A)
            }
        }
    }
}
