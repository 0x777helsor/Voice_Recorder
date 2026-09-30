package com.safesignal.core.common.state

import com.safesignal.core.common.model.InstantEpochMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The only component permitted to mutate [SafeSignalRuntimeState].
 *
 * Every transition is validated against [ALLOWED_TRANSITIONS]. An illegal
 * transition is *rejected*, not silently coerced: the machine emits the
 * rejection so that the failure is visible in the incident timeline instead of
 * disappearing.
 *
 * Two invariants this class exists to protect:
 *
 *  1. `RECORDING -> UPLOADING` is not a legal transition. Networking is not the
 *     recorder's business.
 *  2. Once audio has been captured, nothing a network or a cloud service does
 *     can move the machine out of [SafeSignalPhase.FINALIZING] into a failure
 *     state that would imply the evidence was lost.
 */
class SafeSignalStateMachine(
    initial: SafeSignalRuntimeState = SafeSignalRuntimeState.Idle,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(initial)

    /** Observable, read-only runtime state. */
    val state: StateFlow<SafeSignalRuntimeState> = _state.asStateFlow()

    /** Rejections observed so far. Exposed for diagnostics and tests. */
    private val _rejections = MutableStateFlow<List<RejectedTransition>>(emptyList())
    val rejections: StateFlow<List<RejectedTransition>> = _rejections.asStateFlow()

    val current: SafeSignalRuntimeState get() = _state.value

    suspend fun currentPhase(): SafeSignalPhase = mutex.withLock { _state.value.phase }

    /**
     * Attempts to move to [next].
     *
     * @return [TransitionResult.Accepted] or [TransitionResult.Rejected].
     */
    suspend fun transition(
        next: SafeSignalPhase,
        mutate: (SafeSignalRuntimeState) -> SafeSignalRuntimeState = { it },
    ): TransitionResult = mutex.withLock {
        val from = _state.value.phase
        if (!isAllowed(from, next)) {
            val rejection = RejectedTransition(
                from = from,
                to = next,
                at = currentTimestamp(),
            )
            _rejections.value = _rejections.value + rejection
            TransitionResult.Rejected(rejection)
        } else {
            val updated = mutate(_state.value).copy(phase = next)
            require(updated.phase == next) { "mutate must not override the requested phase" }
            _state.value = updated
            TransitionResult.Accepted(from, next)
        }
    }

    /**
     * Convenience for transitions that only change the phase (and optionally the note).
     */
    suspend fun transitionTo(next: SafeSignalPhase, note: String? = null): TransitionResult =
        transition(next) { it.copy(note = note ?: it.note) }

    /**
     * Records a recoverable or terminal failure without discarding evidence state.
     *
     * A failure while finalizing still results in [SafeSignalPhase.STOPPED] when the
     * previously committed segments were preserved, because "stopped with errors"
     * is a truer description than "failed".
     */
    suspend fun reportFailure(
        failure: SafeSignalFailure,
        evidencePreserved: Boolean,
    ): TransitionResult = mutex.withLock {
        val from = _state.value.phase
        val target = when {
            evidencePreserved && from == SafeSignalPhase.FINALIZING -> SafeSignalPhase.STOPPED
            evidencePreserved && from == SafeSignalPhase.RECORDING -> SafeSignalPhase.FINALIZING
            else -> SafeSignalPhase.FAILED
        }
        if (!isAllowed(from, target)) {
            val rejection = RejectedTransition(from, target, currentTimestamp())
            _rejections.value = _rejections.value + rejection
            TransitionResult.Rejected(rejection)
        } else {
            _state.value = _state.value.copy(
                phase = target,
                lastError = failure,
                note = failure.userMessage,
            )
            TransitionResult.Accepted(from, target)
        }
    }

    suspend fun clearFailure() = mutex.withLock {
        _state.value = _state.value.copy(lastError = null)
    }

    /** Updates synchronization bookkeeping without touching the recorder phase. */
    suspend fun updateSync(recordingId: String, syncPhase: SyncPhase) = mutex.withLock {
        _state.value = _state.value.copy(
            syncPhaseByRecording = _state.value.syncPhaseByRecording + (recordingId to syncPhase),
        )
    }

    suspend fun reset() = mutex.withLock {
        _state.value = SafeSignalRuntimeState.Idle
    }

    // Overridable so tests can supply a deterministic timestamp without a clock.
    internal open fun currentTimestamp(): Long =
        System.currentTimeMillis()

    companion object {
        /**
         * The complete, validated transition table.
         *
         * Read this table as the executable version of the specification's state
         * machine diagram (ARCHITECTURE.md § "State machine").
         */
        val ALLOWED_TRANSITIONS: Map<SafeSignalPhase, Set<SafeSignalPhase>> = mapOf(
            SafeSignalPhase.IDLE to setOf(SafeSignalPhase.ARMING),
            SafeSignalPhase.ARMING to setOf(
                SafeSignalPhase.LISTENING,
                SafeSignalPhase.IDLE, // user cancelled while arming
                SafeSignalPhase.FAILED,
            ),
            SafeSignalPhase.LISTENING to setOf(
                SafeSignalPhase.ACTIVATING,
                SafeSignalPhase.ARMING, // re-arm (e.g. engine restart)
                SafeSignalPhase.FINALIZING, // stop -> finalize any in-flight session
                SafeSignalPhase.FAILED,
            ),
            SafeSignalPhase.ACTIVATING to setOf(
                SafeSignalPhase.RECORDING,
                SafeSignalPhase.LISTENING, // activation gate rejected the trigger
                SafeSignalPhase.FINALIZING,
                SafeSignalPhase.FAILED,
            ),
            SafeSignalPhase.RECORDING to setOf(
                SafeSignalPhase.FINALIZING,
                SafeSignalPhase.FAILED,
            ),
            SafeSignalPhase.FINALIZING to setOf(
                SafeSignalPhase.STOPPED,
                SafeSignalPhase.FAILED,
            ),
            // STOPPED -> IDLE is applied by the caller after the session summary is emitted,
            // because "STOPPED" is what the user must see in the timeline.
            SafeSignalPhase.STOPPED to setOf(SafeSignalPhase.IDLE),
            // A failed arming (no session ever started) may be returned to IDLE.
            // A failed recording must go through FINALIZING so evidence is sealed.
            SafeSignalPhase.FAILED to setOf(
                SafeSignalPhase.IDLE,
                SafeSignalPhase.ARMING,
                SafeSignalPhase.FINALIZING,
            ),
        )

        fun isAllowed(from: SafeSignalPhase, to: SafeSignalPhase): Boolean =
            to in ALLOWED_TRANSITIONS.getValue(from)

        /** Phases from which an audio capture may already exist. */
        val CAPTURE_MAY_HAVE_STARTED: Set<SafeSignalPhase> = setOf(
            SafeSignalPhase.ACTIVATING,
            SafeSignalPhase.RECORDING,
            SafeSignalPhase.FINALIZING,
            SafeSignalPhase.STOPPED,
            SafeSignalPhase.FAILED,
        )
    }
}

sealed interface TransitionResult {
    data class Accepted(val from: SafeSignalPhase, val to: SafeSignalPhase) : TransitionResult
    data class Rejected(val rejection: RejectedTransition) : TransitionResult
}

data class RejectedTransition(
    val from: SafeSignalPhase,
    val to: SafeSignalPhase,
    val at: Long,
)

/** Marker used by [SafeSignalStateMachine.reportFailure] callers. */
internal fun SafeSignalRuntimeState.withNote(note: String?): SafeSignalRuntimeState =
    copy(note = note)

/** Utility for building a runtime state snapshot in tests. */
internal fun runtimeStateSnapshot(
    phase: SafeSignalPhase,
    startedAt: InstantEpochMillis = InstantEpochMillis(0L),
): SafeSignalRuntimeState = SafeSignalRuntimeState(phase = phase)