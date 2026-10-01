package com.safesignal.core.common.state

import com.safesignal.core.common.model.InstantEpochMillis

/**
 * How a session was activated. Recorded with every evidence package.
 *
 * A trigger identifies *what started the recording*. It is not an identity
 * claim: anyone who can speak the phrase, or reach a physical trigger, can
 * produce a [VOICE] or [PHYSICAL_BUTTON] activation. See THREAT_MODEL.md §
 * "Activation is not authentication".
 */
enum class ActivationSource {
    VOICE,
    PHYSICAL_BUTTON,
    NOTIFICATION,
    WIDGET,
    BLUETOOTH,
    HEADSET,

    /**
     * The user started it deliberately from inside the app.
     *
     * Distinct from [WIDGET] and [NOTIFICATION] because it is a different trust
     * story, and that distinction is recorded on the evidence. An in-app tap is
     * unambiguous and deliberate. A wake word is a probabilistic guess about what
     * someone said, and a volume pattern is a guess about which buttons were
     * pressed. All three can be wrong; only the first is reliably a decision, and a
     * reader of the evidence is entitled to know which one produced it.
     */
    IN_APP_BUTTON,

    TEST,
    ;

    /**
     * Test activations are excluded from evidence export and are never
     * synchronized unless the user separately opts in.
     */
    val isTest: Boolean get() = this == TEST

    /**
     * True when the source is a probabilistic inference rather than a deliberate
     * user action.
     *
     * Recorded so a reader can tell "the user pressed record" from "the detector
     * believed the phrase was spoken". Both start a recording; only one of them is
     * a decision, and conflating them would misrepresent how the evidence came to
     * exist.
     */
    val isInferred: Boolean get() = this == VOICE || this == PHYSICAL_BUTTON
}

/**
 * A single trigger occurrence handed to the state machine.
 *
 * @property source which mechanism fired.
 * @property occurredAtElapsedRealtime monotonic timestamp; authoritative for ordering.
 * @property occurredAtWallClock user-facing timestamp; never treated as trustworthy.
 * @property confidence detector confidence in 0..1, or null for non-acoustic triggers.
 * @property phraseId identifier of the matched phrase, if the source is acoustic.
 */
data class ActivationRequest(
    val source: ActivationSource,
    val occurredAtElapsedRealtime: Long,
    val occurredAtWallClock: InstantEpochMillis,
    val confidence: Float? = null,
    val phraseId: String? = null,
    val engineVersion: String? = null,
) {
    init {
        require(occurredAtElapsedRealtime >= 0) { "elapsedRealtime must be monotonic and non-negative" }
        require(confidence == null || confidence in 0f..1f) { "confidence must be within 0..1" }
    }
}

/** Why a recording session ended. */
enum class FinalizationReason {
    /** User (or a spoken stop phrase, or a notification action) stopped it. */
    USER_REQUESTED,

    /** Configured maximum duration reached. */
    MAX_DURATION_REACHED,

    /** Free space approached the configured critical threshold. */
    LOW_STORAGE,

    /** The recorder reported an unrecoverable error. */
    RECORDER_FAILURE,

    /** The user disabled Emergency Listening while recording. */
    LISTENING_DISABLED,

    /** Process is shutting the session down cleanly. */
    SERVICE_STOPPED,
}

/**
 * The immutable outcome of one recording session.
 */
data class RecordingOutcome(
    val recordingId: String?,
    val reason: FinalizationReason,
    val segmentCount: Int,
    val totalBytes: Long,
    val sealed: Boolean,
    val notes: List<String> = emptyList(),
)

/**
 * The single source of truth for SafeSignal runtime state.
 *
 * @property phase the recorder's lifecycle phase.
 * @property sessionId identifier of the active or most recent recording.
 * @property elapsedRecordingMs duration measured with the monotonic clock.
 * @property syncPhaseByRecording synchronization phase per recording id.
 * @property lastError the most recent recoverable error, if any.
 * @property note a short human-readable caveat for the UI ("Low storage").
 */
data class SafeSignalRuntimeState(
    val phase: SafeSignalPhase = SafeSignalPhase.IDLE,
    val sessionId: String? = null,
    val elapsedRecordingMs: Long = 0L,
    val syncPhaseByRecording: Map<String, SyncPhase> = emptyMap(),
    val lastError: SafeSignalFailure? = null,
    val note: String? = null,
) {
    val isArmed: Boolean get() = phase.holdsMicrophone

    companion object {
        val Idle = SafeSignalRuntimeState()
    }
}

/**
 * A failure that the UI must be able to explain to the user in plain language.
 *
 * Failures are values, not log lines: they are persisted in the incident
 * timeline so that a support bundle can explain what happened without ever
 * containing audio or key material.
 */
data class SafeSignalFailure(
    val stage: FailureStage,
    val code: String,
    val userMessage: String,
    val recoverable: Boolean,
    val preservedEvidence: Boolean,
    val detail: String? = null,
)

enum class FailureStage {
    ARMING,
    MICROPHONE,
    WAKE_WORD,
    ACTIVATION_GATE,
    RECORDING,
    ENCRYPTION,
    STORAGE,
    FINALIZATION,
    SYNC,
}