package com.safesignal.service

import com.safesignal.audio.capture.AudioFramePump
import com.safesignal.audio.capture.AudioSourceFactory
import com.safesignal.audio.capture.FinalizedRecording
import com.safesignal.audio.capture.RecordingConfig
import com.safesignal.audio.capture.RecordingEngine
import com.safesignal.audio.capture.RecordingSession
import com.safesignal.audio.capture.RecordingState
import com.safesignal.audio.capture.SegmentedRecordingEngine
import com.safesignal.audio.capture.toRecordingToSeal
import com.safesignal.core.common.concurrent.DispatcherProvider
import com.safesignal.core.common.log.SafeLogger
import com.safesignal.core.common.time.TimeProvider
import com.safesignal.core.crypto.RecordingKeyManager
import com.safesignal.data.local.sealing.ArchiveRequest
import com.safesignal.data.local.sealing.EvidenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the recording engine for the whole process.
 *
 * The service is a lifecycle host, not a recording owner: Android will destroy
 * and recreate it, and the notification it posts must not be coupled to who owns
 * the microphone. Keeping the engine here means an in-progress recording survives
 * a service restart, and — more importantly — that "stop the recording" has one
 * owner, so there is a single answer to whether audio is currently being captured.
 *
 * ### State reporting
 *
 * [state] is derived from the *live engine* rather than tracked alongside it.
 * That matters because the engine can stop itself: [RecordingEngine]'s storage and
 * duration limits finalize a recording from inside the capture loop. A controller
 * that only assigned states it knew about would leave the UI and the notification
 * claiming "recording" after the engine had already sealed and closed the
 * microphone — an indicator that is simply wrong while the microphone is free.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class EmergencyRecordingController @Inject constructor(
    private val audioSourceFactory: AudioSourceFactory,
    private val evidenceRoot: File,
    private val keyManager: RecordingKeyManager,
    private val timeProvider: TimeProvider,
    private val dispatchers: DispatcherProvider,
    private val logger: SafeLogger,
    private val evidenceStore: EvidenceStore,
) {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)

    /** What the microphone is actually doing. Never optimistic. */
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _lastFinalized = MutableStateFlow<FinalizedRecording?>(null)

    /** The most recent finalized recording, or null if there has not been one. */
    val lastFinalized: StateFlow<FinalizedRecording?> = _lastFinalized.asStateFlow()

    private val activeEngine = MutableStateFlow<RecordingEngine?>(null)
    private var sessionScope: CoroutineScope? = null

    /** True while the microphone is held. */
    val isRecording: Boolean get() = activeEngine.value != null

    init {
        // Mirrors the live engine's state, following whichever engine is active.
        // Launched from the controller's own scope so it outlives any one service
        // instance.
        scope.launch {
            activeEngine.flatMapLatest { engine ->
                engine?.state() ?: flowOf(RecordingState.Idle)
            }.collect { _state.value = it }
        }
    }

    /**
     * Starts a bounded, explicitly labelled test recording.
     *
     * A test rather than a real recording: the engine is told the session is a
     * test so the evidence carries that label, and no pre-roll buffer is opened,
     * so nothing is retained before the user asked for it.
     */
    suspend fun startTest(config: RecordingConfig = TEST_CONFIG): Result<RecordingSession> =
        mutex.withLock {
            if (activeEngine.value != null) {
                return@withLock Result.failure(
                    IllegalStateException("a recording is already in progress"),
                )
            }

            val source = audioSourceFactory.create()
            val engine = SegmentedRecordingEngine(
                audioSource = source,
                evidenceRoot = evidenceRoot,
                keyManager = keyManager,
                timeProvider = timeProvider,
                dispatchers = dispatchers,
                logger = logger,
            )
            engine.activationSource = ACTIVATION_SOURCE_TEST
            engine.isTestSession = true

            val started = engine.start(config)
            if (started.isFailure) {
                // Releases the microphone and clears any partial key material
                // before the failure propagates, so a failed start cannot leave
                // the device holding an open AudioRecord.
                engine.release()
                logger.w("test recording failed to start", started.exceptionOrNull())
                return@withLock started
            }

            val newSessionScope = CoroutineScope(SupervisorJob() + dispatchers.default)
            val pump = AudioFramePump(source, dispatchers, logger)

            // Order matters: the engine has an open microphone but no pump yet,
            // so a frame cannot be lost and the two are never momentarily out of
            // step.
            engine.attachPump(pump, newSessionScope)

            sessionScope = newSessionScope
            _lastFinalized.value = null
            activeEngine.value = engine

            logger.i("test recording started", fields = mapOf("durationSeconds" to config.maxDurationSeconds.toString()))
            started
        }

    /**
     * Stops recording, seals it, stores it, and returns what was preserved.
     *
     * Safe to call when the engine has already stopped itself, because the engine
     * returns the same finalized recording rather than failing — a stop triggered
     * by the storage limit must be reportable to the user as a success, not as a
     * crash.
     *
     * Sealing happens here as well as in [ArmingController] because a test run is
     * still a real capture: it produced sealed ciphertext, and without a manifest
     * and a wrapped key in the database that ciphertext is unrecoverable. Leaving
     * the test path unarchived would mean the one flow a developer actually
     * exercises produces evidence nobody can ever open.
     */
    suspend fun stop(): Result<FinalizedRecording> = mutex.withLock {
        val engine = activeEngine.value
            ?: return@withLock Result.failure(IllegalStateException("no recording in progress"))

        val result = engine.stop()
        result.onSuccess { finalized ->
            _lastFinalized.value = finalized
            archiveQuietly(finalized)
        }
        result.onFailure { logger.w("test recording failed to stop", it) }
        detach()
        result
    }

    /**
     * Seals and stores without ever failing the stop.
     *
     * The evidence is already on disk at this point. A failure here means the
     * *index* is incomplete, not that audio was lost, and reporting the stop as
     * failed would tell the user their recording was gone when it is intact.
     */
    private suspend fun archiveQuietly(capture: FinalizedRecording) {
        runCatching {
            evidenceStore.archive(
                ArchiveRequest(
                    capture = capture.toRecordingToSeal(),
                    appVersion = APP_VERSION,
                    deviceTimezoneId = java.util.TimeZone.getDefault().id,
                    wakeWordEngineVersion = null,
                    captureQuality = CAPTURE_QUALITY_NORMAL,
                    retentionPolicy = RETENTION_KEEP_INDEFINITELY,
                    nowWallClockMillis = timeProvider.now().epochMillis,
                ),
            )
        }.onFailure { logger.e("test recording sealed but not indexed", it) }
    }

    /**
     * Finalizes and releases, without blocking the caller.
     *
     * Used from `Service.onDestroy`, which cannot suspend. It stops rather than
     * merely releases, because release discards the in-progress segment: an
     * emergency recorder that throws away the audio it captured when the service is
     * killed would fail at the one moment the recording mattered most.
     */
    fun shutdown() {
        scope.launch {
            mutex.withLock {
                val engine = activeEngine.value ?: return@withLock
                // Best effort: a finalize failure must not prevent the release
                // below, or the microphone would stay open.
                runCatching { engine.stop() }
                runCatching { engine.release() }
                detach()
            }
        }
    }

    private fun detach() {
        sessionScope?.cancel()
        sessionScope = null
        activeEngine.value = null
    }

    companion object {
        /**
         * A short, bounded, labelled test recording.
         *
         * Five-second segments so the run exercises segment sealing more than once
         * rather than producing a single file that would prove very little.
         * Pre-roll is zero: a test must not retain audio captured before the user
         * asked for it.
         */
        val TEST_CONFIG = RecordingConfig(
            segmentDurationSeconds = 5,
            maxDurationSeconds = 60,
            preBufferSeconds = 0,
        )

        const val ACTIVATION_SOURCE_TEST = "TEST"
        const val APP_VERSION = "1.0.0-dev"
        const val CAPTURE_QUALITY_NORMAL = "NORMAL"
        const val RETENTION_KEEP_INDEFINITELY = "KEEP_INDEFINITELY"
    }
}