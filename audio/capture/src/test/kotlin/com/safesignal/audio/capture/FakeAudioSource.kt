package com.safesignal.audio.capture

import com.safesignal.core.common.concurrent.DispatcherProvider
import com.safesignal.core.common.log.SafeLogger
import com.safesignal.core.common.model.ElapsedMillis
import com.safesignal.core.common.model.InstantEpochMillis
import com.safesignal.core.common.time.TimeProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Test doubles for the capture engine.
 *
 * These live in `audio:capture`'s own test source set rather than in a shared
 * fixture module, because they encode the engine's contract: a source that never
 * delivers frames on its own, and a clock that advances only when a test says so.
 * Advancing time under the test's control is what makes the max-duration limit
 * testable at all — with a real clock, asserting a one-minute limit would take a
 * minute.
 */

/**
 * A controllable clock.
 *
 * [advance] moves both the wall clock and the monotonic clock. Monotonic time is
 * what the engine uses for durations, because a wall-clock correction — an NTP
 * sync, a user changing the date — must not be able to shorten or extend a
 * recording. [skewWallClock] exists so a test can prove that.
 */
class TestTimeProvider(
    private var wallMillis: Long = 1_760_000_000_000L,
    private var elapsedMillis: Long = 10_000L,
) : TimeProvider {
    override fun now(): InstantEpochMillis = InstantEpochMillis(wallMillis)

    override fun elapsed(): ElapsedMillis = ElapsedMillis(elapsedMillis)

    fun advance(millis: Long) {
        require(millis >= 0) { "time only moves forward; got $millis" }
        wallMillis += millis
        elapsedMillis += millis
    }

    /** Moves only the wall clock, simulating an NTP correction or a date change. */
    fun skewWallClock(deltaMillis: Long) {
        wallMillis += deltaMillis
    }
}

/**
 * An [AudioSource] that never delivers audio by itself.
 *
 * A test pushes frames through [deliver], so it controls exactly how many bytes
 * accumulate in a segment. This matters for boundary tests: a segment that
 * overflows mid-frame needs a frame whose size is chosen to straddle the limit.
 */
class TestAudioSource : AudioSource {
    var openCount = 0
        private set
    var closeCount = 0
        private set
    var lastConfig: RecordingConfig? = null
        private set

    /** Set to a failure to exercise the engine's open-failure path. */
    var openResult: Result<Unit> = Result.success(Unit)

    private var open = false

    override suspend fun open(config: RecordingConfig): Result<Unit> {
        openCount++
        lastConfig = config
        openResult.onSuccess { open = true }
        return openResult
    }

    override suspend fun read(target: ShortArray): Int = -1

    override suspend fun close() {
        closeCount++
        open = false
    }

    override val isOpen: Boolean get() = open

    override val actualSampleRateHz: Int get() = lastConfig?.sampleRateHz ?: 0

    /** Pushes one frame to a sink, as the real frame pump would. */
    suspend fun deliver(sink: AudioFrameSink, samples: ShortArray) = sink.onFrame(samples)
}

/** A logger that records what was written, so tests can assert on diagnostics. */
class RecordingLogger : SafeLogger {
    val entries = mutableListOf<String>()

    override fun d(message: String, fields: Map<String, String>) {
        entries += "D:$message${if (fields.isEmpty()) "" else " $fields"}"
    }

    override fun i(message: String, fields: Map<String, String>) {
        entries += "I:$message${if (fields.isEmpty()) "" else " $fields"}"
    }

    override fun w(message: String, throwable: Throwable?, fields: Map<String, String>) {
        entries += "W:$message${if (fields.isEmpty()) "" else " $fields"}"
    }

    override fun e(message: String, throwable: Throwable?, fields: Map<String, String>) {
        entries += "E:$message${if (fields.isEmpty()) "" else " $fields"}"
    }

    fun contains(fragment: String): Boolean = entries.any { it.contains(fragment) }
}

/** Unconfined dispatchers, so tests observe effects synchronously. */
object TestDispatchers : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.Unconfined
    override val io: CoroutineDispatcher = Dispatchers.Unconfined
    override val default: CoroutineDispatcher = Dispatchers.Unconfined
    override val audio: CoroutineDispatcher = Dispatchers.Unconfined
}
