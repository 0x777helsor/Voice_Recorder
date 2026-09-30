package com.safesignal.audio.wakeword

import com.safesignal.core.common.model.InstantEpochMillis
import com.safesignal.core.common.time.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A controllable [WakeWordEngine] for tests and for Test Activation mode
 * (SPEC §33).
 *
 * Emits nothing on its own. This is the correct behaviour for a test double: a
 * mock that spontaneously "detected" a phrase would be a great way to ship an
 * app that activates in production.
 *
 * It is also the engine behind Test Activation mode in the shipped app, which is
 * legitimate because SPEC §33 requires a mode where the user can verify the
 * pipeline end to end without depending on speech recognition quality.
 */
class MockWakeWordEngine(
    private val timeProvider: TimeProvider,
    override val engineName: String = "mock",
    override val engineVersion: String = "mock-1.0.0",
    override val isProductionReady: Boolean = false,
) : WakeWordEngine {

    private val _events = MutableSharedFlow<WakeWordEvent>(extraBufferCapacity = 16)
    private val _initialized = MutableSharedFlow<Boolean>(replay = 1)
    private var started = false
    private var currentConfig: WakeWordConfig? = null

    override fun events(): Flow<WakeWordEvent> = _events.asSharedFlow()

    override suspend fun initialize(config: WakeWordConfig): Result<Unit> {
        currentConfig = config
        _initialized.tryEmit(true)
        return Result.success(Unit)
    }

    override suspend fun start(): Result<Unit> {
        check(_initialized.replayCache.firstOrNull() == true) {
            "MockWakeWordEngine must be initialized before start()"
        }
        started = true
        return Result.success(Unit)
    }

    override suspend fun stop() {
        started = false
    }

    override suspend fun release() {
        started = false
        currentConfig = null
    }

    val isStarted: Boolean get() = started

    /**
     * Injects a detection, as though the engine had recognised the phrase.
     *
     * Silently ignored while stopped. A test double that emitted events after
     * [stop] would be modelling a bug, not a system.
     */
    suspend fun emitDetection(
        confidence: Float = 0.95f,
        phraseId: String? = null,
        source: DetectionSource = DetectionSource.TEST,
    ): Boolean {
        if (!started) return false
        val config = currentConfig ?: return false
        val event = WakeWordEvent(
            phraseId = phraseId ?: config.phraseId,
            confidence = confidence,
            detectedAtElapsedRealtime = timeProvider.elapsed().elapsedRealtimeMs,
            detectedAtWallClock = timeProvider.now(),
            engineVersion = engineVersion,
            source = source,
        )
        return _events.emit(event)
    }
}

/** Test source of wall-clock and monotonic time. */
class FakeTimeProvider(
    private var wallClockMillis: Long = 1_700_000_000_000L,
    private var elapsedMillis: Long = 10_000L,
) : TimeProvider {
    override fun now(): InstantEpochMillis = InstantEpochMillis(wallClockMillis)

    override fun elapsed(): com.safesignal.core.common.model.ElapsedMillis =
        com.safesignal.core.common.model.ElapsedMillis(elapsedMillis)

    fun advance(millis: Long) {
        elapsedMillis += millis
        wallClockMillis += millis
    }

    fun setWallClock(millis: Long) {
        wallClockMillis = millis
    }
}