package com.safesignal.service

import com.safesignal.audio.capture.AudioFramePump
import com.safesignal.audio.capture.AudioSourceFactory
import com.safesignal.audio.capture.FinalizedRecording
import com.safesignal.audio.capture.RecordingConfig
import com.safesignal.audio.capture.RecordingSession
import com.safesignal.audio.capture.RecordingState
import com.safesignal.audio.capture.SegmentedRecordingEngine
import com.safesignal.audio.wakeword.DetectionSource
import com.safesignal.audio.wakeword.WakeWordConfig
import com.safesignal.audio.wakeword.WakeWordEngine
import com.safesignal.audio.wakeword.WakeWordEvent
import com.safesignal.core.common.activation.ActivationCandidate
import com.safesignal.core.common.activation.ActivationDecision
import com.safesignal.core.common.activation.ActivationGate
import com.safesignal.core.common.concurrent.DispatcherProvider
import com.safesignal.core.common.log.SafeLogger
import com.safesignal.core.common.state.ActivationSource
import com.safesignal.core.common.time.TimeProvider
import com.safesignal.core.crypto.RecordingKeyManager
import com.safesignal.audio.capture.toRecordingToSeal
import com.safesignal.data.local.sealing.ArchiveRequest
import com.safesignal.data.local.sealing.EvidenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The armed session: listening for the activation phrase, then recording.
 *
 * This is the app's primary interaction and it did not exist. What was there could
 * record for a fixed twelve seconds on demand, which is a diagnostic tool, not an
 * emergency recorder.
 *
 * ### The one microphone rule
 *
 * Arming does **not** open a second audio stream. The wake-word detector is fed the
 * same frames the recorder will write, through
 * [SegmentedRecordingEngine.frameObserver]. Two `AudioRecord` instances on one
 * device is a request the platform may refuse, and even if granted, the two streams
 * are not guaranteed to be aligned — so the detector could fire on audio that was
 * never recorded, or the pre-roll ring could hold different audio from the one the
 * detector analysed.
 *
 * ### What the detection actually is
 *
 * The detector is a signal-processing template matcher, not a speech recogniser. It
 * is genuinely local and genuinely works, and its accuracy is **not** established.
 * `isDetectionVerified` exists so the UI can say so rather than implying a
 * reliability that has never been measured. See KNOWN_LIMITATIONS.md.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ArmingController @Inject constructor(
    private val audioSourceFactory: AudioSourceFactory,
    private val evidenceRoot: File,
    private val keyManager: RecordingKeyManager,
    private val timeProvider: TimeProvider,
    private val dispatchers: DispatcherProvider,
    private val logger: SafeLogger,
    private val wakeWordEngine: WakeWordEngine,
    private val evidenceStore: EvidenceStore,
    private val volumeTrigger: com.safesignal.service.trigger.VolumeTriggerReceiver? = null,
) {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<ArmingState>(ArmingState.Disarmed)
    val state: StateFlow<ArmingState> = _state.asStateFlow()

    /**
     * The last wake-word detection, whatever the gate decided.
     *
     * Surfaced even when rejected: a detector that fires constantly and is silently
     * suppressed looks identical to one that never fires, and a user cannot tell
     * which is wrong with their phone.
     */
    private val _lastDetection = MutableStateFlow<WakeWordEvent?>(null)
    val lastDetection: StateFlow<WakeWordEvent?> = _lastDetection.asStateFlow()

    private val activeEngine = MutableStateFlow<SegmentedRecordingEngine?>(null)
    private var sessionScope: CoroutineScope? = null
    private var activationJob: Job? = null
    private val gate = ActivationGate(
        config = com.safesignal.core.common.activation.ActivationGateConfig(),
        nowElapsedRealtimeMillis = { timeProvider.elapsed().elapsedRealtimeMs },
    )

    val isArmed: Boolean get() = activeEngine.value != null

    val isRecording: Boolean get() = _state.value is ArmingState.Recording

    /**
     * When the current arming expires, on the monotonic clock. Null when disarmed.
     *
     * Exposed so the UI can show a countdown rather than "armed" with no end. An
     * armed state with no visible deadline is the thing that makes this kind of app
     * indistinguishable from something that never stops.
     */
    private val _armedUntil = MutableStateFlow<Long?>(null)
    val armedUntil: StateFlow<Long?> = _armedUntil.asStateFlow()

    private var expiryJob: Job? = null

    init {
        scope.launch {
            activeEngine.flatMapLatest { engine ->
                engine?.state() ?: flowOf(RecordingState.Idle)
            }.collect { engineState -> deriveState(engineState) }
        }
    }

    /**
     * Arms the session: opens the microphone and begins listening.
     *
     * Pre-roll is non-zero, so the engine opens in [RecordingState.Listening] and
     * holds the preceding seconds in a RAM ring. Nothing reaches evidence until
     * [trigger] confirms an activation — that is what makes the ring a *ring*
     * rather than a recorder that runs all the time.
     *
     * @param durationMillis how long to stay armed before disarming automatically.
     *
     * The expiry is not a convenience. An app that holds a microphone indefinitely
     * has the same observable behaviour as spyware, and that is true regardless of
     * intent. Ending on a deadline the user chose and can see is what makes the
     * armed state defensible — so it is enforced here rather than left to the user
     * to remember, and a disarmed app cannot be revived without a fresh arm.
     */
    suspend fun arm(
        config: RecordingConfig = ARMED_CONFIG,
        durationMillis: Long = DEFAULT_ARM_DURATION_MILLIS,
    ): Result<Unit> = mutex.withLock {
        if (activeEngine.value != null) {
            return@withLock Result.failure(IllegalStateException("already armed"))
        }
        require(durationMillis > 0) { "an arming must have a positive duration" }

        val source = audioSourceFactory.create()
        val engine = SegmentedRecordingEngine(
            audioSource = source,
            evidenceRoot = evidenceRoot,
            keyManager = keyManager,
            timeProvider = timeProvider,
            dispatchers = dispatchers,
            logger = logger,
        )
        // A real armed session, not a test. The flag travels into the manifest, so
        // arming can never produce evidence indistinguishable from a test run.
        engine.isTestSession = false
        engine.frameObserver = { frame -> onCapturedFrame(frame) }

        val started = engine.start(config)
        if (started.isFailure) {
            engine.release()
            _state.value = ArmingState.Failed(
                message = started.exceptionOrNull()?.message ?: "could not open the microphone",
            )
            return@withLock Result.failure(started.exceptionOrNull()!!)
        }

        val newScope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val pump = AudioFramePump(source, dispatchers, logger)
        engine.attachPump(pump, newScope)
        sessionScope = newScope
        activeEngine.value = engine
        gate.reset()

        wakeWordEngine.initialize(WakeWordConfig(phraseId = DEFAULT_PHRASE_ID, phraseText = DEFAULT_PHRASE_TEXT))
            .onFailure { logger.w("wake word engine did not initialise", it) }
        // The local template engine reports a successful start whether or not it is
        // enrolled, because an unenrolled engine legitimately listens for nothing.
        // `isEnrolled` is surfaced so the UI can say which case this is rather than
        // showing a confident "armed" over a detector that cannot fire.
        wakeWordEngine.start()

        startExpiry(durationMillis)

        logger.i(
            "armed; listening",
            fields = mapOf(
                "durationMillis" to durationMillis.toString(),
                "wakeWordEnrolled" to wakeWordEngine.isEnrolled.toString(),
            ),
        )
        Result.success(Unit)
    }

    /**
     * Disarms when the deadline passes.
     *
     * Recomputed against the monotonic clock rather than a `delay` alone, so a
     * disarmed-and-re-armed session cannot be cancelled by a stale timer from the
     * previous one firing into the new session.
     */
    private fun startExpiry(durationMillis: Long) {
        expiryJob?.cancel()
        val deadline = timeProvider.elapsed().elapsedRealtimeMs + durationMillis
        _armedUntil.value = deadline
        expiryJob = scope.launch {
            val remaining = (deadline - timeProvider.elapsed().elapsedRealtimeMs).coerceAtLeast(0)
            kotlinx.coroutines.delay(remaining)
            if (_armedUntil.value == deadline) {
                logger.i("arming expired; disarming", fields = mapOf("reason" to "deadline"))
                disarm()
            }
        }
    }

    /**
     * Makes the volume-pattern trigger live for as long as the session is armed.
     *
     * Opt-in, and reported rather than assumed. Becoming the media volume owner
     * means the buttons stop changing music volume and a media notification appears
     * — real costs, so a user who has not chosen a pattern does not silently get
     * them. The failure is logged and left non-fatal: the manual and wake-word
     * triggers still work, and refusing to arm over a volume problem would take
     * away the triggers that do work.
     */
    suspend fun enableVolumePattern(pattern: com.safesignal.core.common.trigger.VolumePattern) {
        val receiver = volumeTrigger ?: return
        receiver.arm(pattern) { trigger(ActivationSource.PHYSICAL_BUTTON) }
            .onSuccess { logger.i("volume pattern armed") }
            .onFailure { logger.w("volume pattern could not be armed", it) }
    }

    private fun releaseVolumePattern() {
        volumeTrigger?.release()
    }

    /**
     * Confirms an activation and begins writing.
     *
     * Called by the wake-word pipeline and by the UI's "record now" button. The
     * gate is applied to acoustic detections only; a button press is a
     * deterministic user action and must not be second-guessed, though it is still
     * subject to the already-recording guard.
     */
    suspend fun trigger(source: ActivationSource = ActivationSource.IN_APP_BUTTON): Result<Unit> {
        val engine = activeEngine.value
            ?: return Result.failure(IllegalStateException("not armed"))

        if (source == ActivationSource.VOICE) {
            val decision = gate.evaluate(
                ActivationCandidate(
                    source = source,
                    confidence = _lastDetection.value?.confidence ?: 0f,
                    engineVersion = wakeWordEngine.engineVersion,
                    phraseId = _lastDetection.value?.phraseId,
                ),
            )
            if (decision !is ActivationDecision.Accepted) {
                logger.d(
                    "activation rejected",
                    fields = mapOf("reason" to decision::class.simpleName.orEmpty()),
                )
                return Result.success(Unit) // Not an error: rejection is a normal outcome.
            }
        }

        return engine.trigger().onSuccess {
            gate.onRecordingStarted(timeProvider.elapsed().elapsedRealtimeMs)
            logger.i("recording started", fields = mapOf("source" to source.name))
        }.onFailure {
            logger.w("trigger failed", it)
        }
    }

    /** Stops, finalizes, seals and stores whatever was captured. */
    suspend fun disarm(): Result<FinalizedRecording> = mutex.withLock {
        val engine = activeEngine.value
        wakeWordEngine.stop()
        activationJob?.cancel()
        activationJob = null

        if (engine == null) {
            _state.value = ArmingState.Disarmed
            return@withLock Result.failure(IllegalStateException("not armed"))
        }

        val result = engine.stop()
        val capture = result.getOrNull()
        if (capture != null) {
            gate.onRecordingStopped()
            // Sealing must not be skipped because a *cache* write failed. The
            // manifest is what makes the segments a claim rather than a pile of
            // files, and losing it would leave evidence that cannot be verified.
            archiveQuietly(capture)
        }
        detach()
        result
    }

    private suspend fun onCapturedFrame(frame: ShortArray) {
        // Subsampled rather than analysed on every frame: the detector's own
        // cadence is 100 ms, and running Goertzel on every 50 ms frame would burn
        // battery for no accuracy. Dropped frames are dropped audio for *detection*
        // only — the recorder still receives every frame.
        if (frame.size < MIN_ANALYSIS_SAMPLES) return
        if (!shouldAnalyseThisFrame()) return

        runCatching { wakeWordEngine.submitFrame(frame) }
            .onFailure { logger.d("wake word analysis skipped", fields = mapOf("error" to "analysis")) }
    }

    private fun deriveState(engineState: RecordingState) {
        _state.value = when (engineState) {
            RecordingState.Idle, RecordingState.Starting -> ArmingState.Disarmed
            RecordingState.Listening -> ArmingState.Listening
            RecordingState.Recording -> ArmingState.Recording
            RecordingState.Stopping, RecordingState.Finalizing -> ArmingState.Finalizing
            RecordingState.Stopped -> ArmingState.Finalizing
            is RecordingState.Failed -> ArmingState.Failed(message = engineState.reason)
        }
    }

    private fun detach() {
        releaseVolumePattern()
        expiryJob?.cancel()
        expiryJob = null
        _armedUntil.value = null
        sessionScope?.cancel()
        sessionScope = null
        activeEngine.value = null
        _state.value = ArmingState.Disarmed
    }

    private suspend fun archiveQuietly(capture: FinalizedRecording) {
        runCatching {
            evidenceStore.archive(
                ArchiveRequest(
                    capture = capture.toRecordingToSeal(),
                    appVersion = APP_VERSION,
                    deviceTimezoneId = java.util.TimeZone.getDefault().id,
                    wakeWordEngineVersion = wakeWordEngine.engineVersion,
                    captureQuality = CAPTURE_QUALITY_RECORDED,
                    retentionPolicy = RETENTION_KEEP_INDEFINITELY,
                    nowWallClockMillis = timeProvider.now().epochMillis,
                ),
            )
        }.onFailure {
            // Deliberately swallowed and logged. The evidence is sealed on disk
            // whether or not this succeeds, and reporting the disarm as failed would
            // make a user think their recording was lost when it is intact.
            logger.e("evidence archived with problems", it)
        }
    }

    /** Collects detections from the engine and turns them into activations. */
    suspend fun observeDetections() {
        activationJob?.cancel()
        activationJob = scope.launch {
            wakeWordEngine.events().collect { event ->
                if (event.source == DetectionSource.TEST) return@collect
                _lastDetection.value = event
                trigger(ActivationSource.VOICE)
            }
        }
    }

    /** Waits for the armed session to reach a terminal state, then disarms. */
    suspend fun awaitCompletion(timeoutMillis: Long): Result<FinalizedRecording> {
        kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) {
            activeEngine.flatMapLatest { it?.state() ?: flowOf(RecordingState.Idle) }
                .first { it == RecordingState.Stopped || it is RecordingState.Failed }
        }
        return disarm()
    }

    private var lastAnalysisAt: Long = 0L

    private fun shouldAnalyseThisFrame(): Boolean {
        val now = timeProvider.elapsed().elapsedRealtimeMs
        if (now - lastAnalysisAt < LocalWakeWordCadence) return false
        lastAnalysisAt = now
        return true
    }

    companion object {
        /**
         * Eight hours.
         *
         * Long enough to cover a shift or a night, short enough that a forgotten
         * arming is not a microphone left open indefinitely. The countdown is
         * visible in the UI for the whole period.
         */
        const val DEFAULT_ARM_DURATION_MILLIS = 8L * 60 * 60 * 1000

        const val DEFAULT_PHRASE_ID = "default"
        const val DEFAULT_PHRASE_TEXT = "SafeSignal, record"
        const val APP_VERSION = "1.0.0-dev"
        const val CAPTURE_QUALITY_RECORDED = "NORMAL"
        const val RETENTION_KEEP_INDEFINITELY = "KEEP_INDEFINITELY"

        /** Matches the detector's own 100 ms cadence. */
        const val LocalWakeWordCadence = 100L

        private const val MIN_ANALYSIS_SAMPLES = 64

        /**
         * The armed configuration.
         *
         * `preBufferSeconds = 5` is what makes the phrase's own beginning part of
         * the evidence: a detector fires partway through the utterance, and without a
         * ring the recording would start after the words that identified it.
         */
        val ARMED_CONFIG = RecordingConfig(
            segmentDurationSeconds = 30,
            maxDurationSeconds = 15 * 60,
            preBufferSeconds = 5,
        )
    }
}

/** What the armed session is doing. The UI renders this and nothing else. */
sealed interface ArmingState {
    data object Disarmed : ArmingState

    /** Microphone open, watching for the phrase. Nothing is being written. */
    data object Listening : ArmingState

    data object Recording : ArmingState

    data object Finalizing : ArmingState

    data class Failed(val message: String) : ArmingState

    val isActive: Boolean get() = this !is Disarmed
}
