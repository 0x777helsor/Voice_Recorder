package com.safesignal.service

import com.safesignal.audio.capture.AudioSource
import com.safesignal.audio.capture.AudioSourceFactory
import com.safesignal.audio.capture.RecordingConfig
import com.safesignal.audio.capture.RecordingState
import com.safesignal.core.common.concurrent.DispatcherProvider
import com.safesignal.core.common.log.NoOpLogSink
import com.safesignal.core.common.log.RedactingLogger
import com.safesignal.core.common.model.ElapsedMillis
import com.safesignal.core.common.model.InstantEpochMillis
import com.safesignal.core.common.time.TimeProvider
import com.safesignal.core.crypto.RecordingKeyManager
import com.safesignal.core.crypto.testing.InMemoryKeyProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The controller is the single owner of "is the microphone open", so these tests
 * care about one question above all: after any sequence of calls, does `state`
 * tell the truth?
 *
 * The indicator is a safety contract, not a convenience. A controller that
 * reports "recording" after the engine has sealed its last segment and closed
 * AudioRecord would tell the user, and anyone who can see the notification, that
 * a recording is in progress when the microphone is in fact free — and the
 * opposite error is worse still, since it hides an active recording.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EmergencyRecordingControllerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val keyManager = RecordingKeyManager(InMemoryKeyProvider())
    private val logger = RedactingLogger("test", NoOpLogSink)

    // The clock and the dispatchers are built per test rather than in a field
    // initialiser, because `testScheduler` only exists on the TestScope receiver —
    // available inside `runTest`, not at construction time. Constructing them per
    // test is also what keeps each test's virtual clock independent.
    private fun kotlinx.coroutines.test.TestScope.clock() = SchedulerTimeProvider(testScheduler)

    private fun kotlinx.coroutines.test.TestScope.testDispatchers() = TestDispatchers(testScheduler)

    @Test
    fun `a fresh controller claims nothing`() = runTest {
        val controller = controller(FakeAudioSourceFactory(SilentAudioSource()))

        assertFalse(controller.isRecording)
        assertEquals(RecordingState.Idle, controller.state.value)
        assertEquals(null, controller.lastFinalized.value)
    }

    @Test
    fun `a test recording starts capturing immediately rather than waiting for a trigger`() = runTest {
        // Pre-roll is zero for a test, so there is nothing to wait for. Reporting
        // Listening here would mean a test that captures nothing until a wake word
        // that is not wired yet — a green result that proved no audio was written.
        val controller = controller(FakeAudioSourceFactory(SilentAudioSource()))

        controller.startTest()
        runCurrent()

        assertTrue(controller.isRecording)
        assertEquals(RecordingState.Recording, controller.state.value)
    }

    @Test
    fun `a test recording seals real audio to disk and labels itself a test`() = runTest {
        // The end-to-end claim: running the controller produces encrypted, sealed
        // evidence on disk. Asserting only on state transitions would pass even if
        // every segment were silently dropped.
        val evidenceRoot = File(temporaryFolder.root, "evidence").apply { mkdirs() }
        val controller = controller(
            factory = FakeAudioSourceFactory(SilentAudioSource()),
            evidenceRoot = evidenceRoot,
        )

        val started = controller.startTest()
        runCurrent()
        assertTrue(started.isSuccess)

        // Longer than one segment duration, so sealing is exercised rather than
        // only the "stop and commit the open segment" path.
        advanceTimeBy(SEGMENT_DURATION_MILLIS + 3_000)
        runCurrent()

        val finalized = controller.stop().getOrThrow()
        runCurrent()

        assertTrue("no segment was sealed: $finalized", finalized.segments.isNotEmpty())
        assertTrue(
            "a test recording must be labelled a test, or it is indistinguishable from evidence",
            finalized.isTestRecording,
        )

        val directory = File(evidenceRoot, finalized.recordingId)
        assertTrue("no directory for the recording at $directory", directory.isDirectory)
        assertTrue(
            "segments were finalized but nothing was written to disk",
            directory.listFiles().orEmpty().any { it.length() > 0 },
        )
    }

    @Test
    fun `stopping releases the microphone and keeps the finalized recording`() = runTest {
        val controller = controller(FakeAudioSourceFactory(SilentAudioSource()))

        controller.startTest()
        runCurrent()
        advanceTimeBy(1_000)
        val stopped = controller.stop()
        runCurrent()

        assertTrue(stopped.isSuccess)
        assertFalse("the microphone must not still be held after a stop", controller.isRecording)
        assertEquals(RecordingState.Idle, controller.state.value)
        assertEquals(controller.lastFinalized.value, stopped.getOrNull())
    }

    @Test
    fun `a second start is refused instead of replacing the first`() = runTest {
        // Swapping the active engine would abandon the in-flight recording's
        // buffered segment and strand the previous session's key material. Failing
        // loudly is the only safe answer.
        val controller = controller(FakeAudioSourceFactory(SilentAudioSource()))

        controller.startTest()
        runCurrent()
        val second = controller.startTest()
        runCurrent()

        assertTrue("a concurrent start must fail loudly", second.isFailure)
        assertEquals(
            "the original recording must still be the active one",
            RecordingState.Recording,
            controller.state.value,
        )
    }

    @Test
    fun `stopping with no recording fails rather than reporting an empty success`() = runTest {
        // A stop that returned success with nothing preserved would tell the user
        // their audio is safe when it was never captured.
        val controller = controller(FakeAudioSourceFactory(SilentAudioSource()))

        val stopped = controller.stop()
        runCurrent()

        assertTrue(stopped.isFailure)
        assertEquals(RecordingState.Idle, controller.state.value)
    }

    @Test
    fun `a refused microphone leaves the controller idle and not recording`() = runTest {
        // REVOKE-then-record is routine: the user can withdraw RECORD_AUDIO from
        // Settings while the app is running. If that left the controller claiming
        // to record, the notification would promise a recording that does not exist.
        val controller = controller(
            FakeAudioSourceFactory(SilentAudioSource(shouldFailToOpen = true)),
        )

        val started = controller.startTest()
        runCurrent()

        assertTrue(started.isFailure)
        assertFalse(controller.isRecording)
        assertEquals(RecordingState.Idle, controller.state.value)
        assertEquals(null, controller.lastFinalized.value)
    }

    @Test
    fun `each recording gets its own audio source`() = runTest {
        // Sharing an AudioRecord across sessions would let a failed stop leave the
        // next recording attached to a half-open device.
        val factory = FakeAudioSourceFactory(SilentAudioSource())
        val controller = controller(factory)

        controller.startTest()
        runCurrent()
        controller.stop()
        runCurrent()
        controller.startTest()
        runCurrent()

        assertEquals(2, factory.createCount)
    }

    @Test
    fun `the test configuration is bounded, unlabelled audio aside`() = runTest {
        val config = EmergencyRecordingController.TEST_CONFIG

        assertTrue("a test must not hold the microphone indefinitely", config.maxDurationSeconds <= 60)
        assertEquals("a test must not retain audio captured before it was asked for", 0, config.preBufferSeconds)
        assertTrue(
            "segments must be short enough that a test crosses a boundary more than once",
            config.segmentDurationSeconds <= 10,
        )
    }

    private fun kotlinx.coroutines.test.TestScope.controller(
        factory: AudioSourceFactory,
        evidenceRoot: File = File(temporaryFolder.root, "evidence").apply { mkdirs() },
    ): EmergencyRecordingController = EmergencyRecordingController(
        audioSourceFactory = factory,
        evidenceRoot = evidenceRoot,
        keyManager = keyManager,
        timeProvider = clock(),
        dispatchers = testDispatchers(),
        logger = logger,
    )
}

/** Counts how many times the controller asked for a microphone. */
private class FakeAudioSourceFactory(
    private val source: AudioSource,
) : AudioSourceFactory {
    var createCount: Int = 0
        private set

    override suspend fun create(): AudioSource {
        createCount++
        return source
    }
}

/**
 * A microphone made of nothing.
 *
 * [read] suspends for one read interval, which is the part that matters. A real
 * `AudioRecord.read` blocks the calling thread until audio arrives, and
 * `AudioFramePump` relies on that: its loop has no delay of its own, so a source
 * that returned instantly would let the pump spin at full speed and hang the test
 * on the dispatcher rather than failing it.
 *
 * [shouldFailToOpen] models the platform refusing access, which is an ordinary
 * event rather than an exotic one.
 */
private class SilentAudioSource(
    private val shouldFailToOpen: Boolean = false,
) : AudioSource {

    private var open = false
    private var readCount = 0

    override suspend fun open(config: RecordingConfig): Result<Unit> =
        if (shouldFailToOpen) {
            Result.failure(SecurityException("RECORD_AUDIO not granted"))
        } else {
            open = true
            readCount = 0
            Result.success(Unit)
        }

    override suspend fun read(target: ShortArray): Int {
        if (!open) return ERROR_INVALID_OPERATION
        readCount++
        delay(READ_INTERVAL_MILLIS)
        // A quiet, non-zero signal, so the quality monitor does not classify the
        // capture as the silent-failure case the engine has to detect.
        target.fill(120)
        return target.size
    }

    override suspend fun close() {
        open = false
    }

    override val isOpen: Boolean get() = open

    override val actualSampleRateHz: Int = 48_000

    private companion object {
        /** Matches AudioFramePump.READS_PER_SECOND. */
        const val READ_INTERVAL_MILLIS = 50L
        const val ERROR_INVALID_OPERATION = -3
    }
}

/**
 * Reads the test scheduler's clock, so `elapsed()` advances exactly as virtual
 * time does.
 *
 * The engine seals a segment when `TimeProvider.elapsed()` has moved by a segment
 * duration. A clock frozen at zero would therefore never seal anything, and every
 * test asserting on sealed evidence would pass for the wrong reason — or fail for
 * a reason that has nothing to do with the controller.
 */
private class SchedulerTimeProvider(
    private val scheduler: TestCoroutineScheduler,
) : TimeProvider {
    override fun now(): InstantEpochMillis = InstantEpochMillis(scheduler.currentTime)
    override fun elapsed(): ElapsedMillis = ElapsedMillis(scheduler.currentTime)
}

/** Routes every dispatcher at the test scope, so nothing escapes virtual time. */
private class TestDispatchers(scheduler: TestCoroutineScheduler) : DispatcherProvider {
    private val dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(scheduler)
    override val main: CoroutineDispatcher get() = dispatcher
    override val io: CoroutineDispatcher get() = dispatcher
    override val default: CoroutineDispatcher get() = dispatcher
    override val audio: CoroutineDispatcher get() = dispatcher
}

private const val SEGMENT_DURATION_MILLIS = 5_000L