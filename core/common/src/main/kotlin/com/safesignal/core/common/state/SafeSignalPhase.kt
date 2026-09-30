package com.safesignal.core.common.state

/**
 * Canonical runtime phases for SafeSignal.
 *
 * These names are normative: they appear in the specification, in the exported
 * diagnostics bundle and in the incident timeline. Do not rename them casually —
 * existing evidence metadata references them.
 *
 * Note that synchronization phases are deliberately *not* part of this enum.
 * A network failure must never be able to move the recorder out of
 * [RECORDING]; see [SyncPhase].
 */
enum class SafeSignalPhase {
    /** Nothing is armed. No microphone is held. */
    IDLE,

    /** User requested Emergency Listening; subsystems are being brought up. */
    ARMING,

    /** Foreground service is running and the local wake-word engine is listening. */
    LISTENING,

    /** A trigger fired. Acceptance rules are being evaluated. */
    ACTIVATING,

    /** Audio is being captured, segmented and encrypted. */
    RECORDING,

    /** Capture stopped; the active segment, manifest and hashes are being written. */
    FINALIZING,

    /** The recording is sealed and stored locally. Terminal for a session. */
    STOPPED,

    /** A subsystem failed. See [SafeSignalRuntimeState.lastError]. */
    FAILED,
    ;

    val isActive: Boolean
        get() = this == LISTENING || this == ACTIVATING || this == RECORDING

    /** True while the microphone is (or should be) held. */
    val holdsMicrophone: Boolean
        get() = this == LISTENING || this == ACTIVATING || this == RECORDING
}

/**
 * Synchronization phases. Modelled separately from [SafeSignalPhase] precisely so
 * that connectivity problems can never be confused with recording problems.
 */
enum class SyncPhase {
    /** Recording exists and is preserved locally only. Not queued. */
    LOCAL_ONLY,

    /** Queued for upload; no upload in flight. */
    QUEUED,

    /** An upload attempt is in flight. */
    UPLOADING,

    /** Some chunks uploaded, others remain. Resumable. */
    PARTIALLY_UPLOADED,

    /** Server confirmed the manifest and issued a receipt. */
    SYNCED,

    /** The last attempt failed; the local copy is untouched. */
    FAILED,

    /** Waiting out a backoff window before the next attempt. */
    RETRYING,
    ;

    val isTerminalForUploader: Boolean get() = this == SYNCED
}