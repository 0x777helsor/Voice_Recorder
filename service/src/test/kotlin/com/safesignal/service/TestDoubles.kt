package com.safesignal.service

import com.safesignal.audio.capture.AudioSource
import com.safesignal.audio.capture.AudioSourceFactory
import com.safesignal.audio.capture.RecordingConfig
import com.safesignal.core.common.concurrent.DispatcherProvider
import com.safesignal.core.common.model.ElapsedMillis
import com.safesignal.core.common.model.InstantEpochMillis
import com.safesignal.core.common.time.TimeProvider
import com.safesignal.data.local.sealing.ArchiveRequest
import com.safesignal.data.local.sealing.EvidenceStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Test doubles shared by the service tests.
 *
 * They live in one file because the two suites that need them assert genuinely
 * different things about the same fake, and two private copies is how they drift.
 */

/**
 * Captures what would have been archived, without touching Room or a keystore.
 *
 * Records rather than discards, so a test can assert that a capture carried the
 * material needed to open it later. That is not hypothetical: the wrapped data key
 * was being dropped entirely, and this is the first place it is checked on a real
 * capture rather than on a hand-built object.
 */
class RecordingEvidenceStore : EvidenceStore {
    val archived = mutableListOf<ArchiveRequest>()

    override suspend fun archive(request: ArchiveRequest) {
        archived += request
    }
}

/** Always fails, to prove a storage problem never costs anyone their audio. */
object FailingEvidenceStore : EvidenceStore {
    override suspend fun archive(request: ArchiveRequest): Unit =
        throw IllegalStateException("the database is unavailable")
}

/**
 * An audio source that produces a steady tone.
 *
 * The read **suspends**. `AudioFramePump`'s loop has no delay of its own, so a fake
 * that returned instantly would spin forever on an unconfined dispatcher and hang
 * the test rather than fail it.
 */
class ToneAudioSource(
    private val sampleRateHz: Int = 48_000,
) : AudioSource {
    override val isOpen: Boolean = true
    override val actualSampleRateHz: Int get() = sampleRateHz

    private val pcm = ShortArray(2400) { (it % 512 - 256).toShort() }

    override suspend fun open(config: RecordingConfig): Result<Unit> = Result.success(Unit)

    override suspend fun read(target: ShortArray): Int {
        delay(50)
        val n = minOf(target.size, pcm.size)
        System.arraycopy(pcm, 0, target, 0, n)
        return n
    }

    override suspend fun close() = Unit
}

class ToneAudioSourceFactory(
    private val sampleRateHz: Int = 48_000,
) : AudioSourceFactory {
    var createCount: Int = 0
        private set

    override suspend fun create(): AudioSource {
        createCount++
        return ToneAudioSource(sampleRateHz)
    }
}

/** Routes every dispatcher at the test scope, so nothing escapes virtual time. */
class TestDispatchers(scheduler: TestCoroutineScheduler) : DispatcherProvider {
    private val dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(scheduler)
    override val main: CoroutineDispatcher get() = dispatcher
    override val io: CoroutineDispatcher get() = dispatcher
    override val default: CoroutineDispatcher get() = dispatcher
    override val audio: CoroutineDispatcher get() = dispatcher
}

/**
 * A clock driven by the test scheduler's virtual time.
 *
 * Not optional. `SegmentedRecordingEngine.enforceLimits` compares elapsed real time
 * against `maxDurationSeconds`, so a frozen clock means the cap never fires and a
 * test pumps frames forever — which is how one of these tests wrote 1.1 GB to
 * /tmp before this existed. Virtual time is what makes the limit observable.
 */
class SchedulerTimeProvider(
    private val scheduler: TestCoroutineScheduler,
) : TimeProvider {
    override fun now(): InstantEpochMillis = InstantEpochMillis(scheduler.currentTime)
    override fun elapsed(): ElapsedMillis = ElapsedMillis(scheduler.currentTime)
}

/**
 * The same, but on the *standard* dispatcher rather than unconfined.
 *
 * Preferred for tests that call `advanceUntilIdle()`, because an unconfined
 * dispatcher runs eagerly and makes the advance meaningless.
 */
class StandardTestDispatchers(scheduler: TestCoroutineScheduler) : DispatcherProvider {
    private val dispatcher: CoroutineDispatcher = StandardTestDispatcher(scheduler)
    override val main: CoroutineDispatcher get() = dispatcher
    override val io: CoroutineDispatcher get() = dispatcher
    override val default: CoroutineDispatcher get() = dispatcher
    override val audio: CoroutineDispatcher get() = dispatcher
}
