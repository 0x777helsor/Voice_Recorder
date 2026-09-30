package com.safesignal.core.common.activation

import com.safesignal.core.common.state.ActivationSource

/**
 * Configuration of the false-positive protections required by SPEC §10.
 *
 * Every value here trades missed activations against spurious ones. There is no
 * setting that eliminates both, so the UI presents the trade-off and SPEC §64
 * requires the measured rates to be reported rather than assumed.
 */
data class ActivationGateConfig(
    /**
     * Minimum detector confidence to accept an acoustic activation, 0f..1f.
     *
     * Applied to [ActivationSource.VOICE] only. Non-acoustic triggers are
     * deterministic user actions, not probabilistic detections.
     */
    val confidenceThreshold: Float = 0.75f,

    /**
     * Minimum gap between two accepted activations.
     *
     * Protects against a detector re-firing on the tail of the same utterance.
     */
    val cooldownMillis: Long = 10_000L,

    /**
     * Window after an acceptance during which *any* further trigger is dropped.
     *
     * Distinct from cooldown and deliberately shorter: it exists to stop a
     * spurious activation cascade, so that a single misfire cannot immediately
     * produce a second recording.
     */
    val duplicateSuppressionWindowMillis: Long = 5_000L,

    /**
     * When true, a sub-threshold candidate is still reported to the caller so it
     * can be shown in recognition test mode. The accept/reject decision is
     * identical either way; only observability changes.
     */
    val reportLowConfidence: Boolean = false,
) {
    init {
        require(confidenceThreshold in 0f..1f) { "confidenceThreshold must be within 0..1" }
        require(cooldownMillis >= 0) { "cooldownMillis must not be negative" }
        require(duplicateSuppressionWindowMillis >= 0) {
            "duplicateSuppressionWindowMillis must not be negative"
        }
    }
}

/** Outcome of asking the gate whether a trigger may start a recording. */
sealed interface ActivationDecision {
    data class Accepted(val source: ActivationSource) : ActivationDecision

    data class RejectedLowConfidence(val confidence: Float, val threshold: Float) : ActivationDecision

    data class RejectedCooldown(val remainingMillis: Long) : ActivationDecision

    data class RejectedDuplicate(val remainingMillis: Long) : ActivationDecision

    /** A recording is already in progress. A trigger must never restart it. */
    data class RejectedAlreadyRecording(val elapsedMillis: Long) : ActivationDecision

    /** True when the candidate should still be surfaced in a diagnostics view. */
    val shouldReportToUi: Boolean
        get() = this !is RejectedDuplicate && this !is RejectedAlreadyRecording
}

/**
 * A candidate activation, normalised across trigger kinds.
 *
 * @property confidence detector confidence; [PHYSICAL_TRIGGER_CONFIDENCE] for
 *   non-acoustic triggers.
 * @property engineVersion detector version, recorded on the evidence package so a
 *   recording can be reproduced against the detector that triggered it (SPEC §93).
 */
data class ActivationCandidate(
    val source: ActivationSource,
    val confidence: Float,
    val engineVersion: String,
    val phraseId: String? = null,
) {
    init {
        require(confidence in 0f..1f) { "confidence must be within 0..1" }
    }

    /** True when the trigger is acoustic and therefore subject to the threshold. */
    val isAcoustic: Boolean get() = source == ActivationSource.VOICE

    companion object {
        /**
         * Confidence reported for deterministic user actions such as a
         * notification tap or a widget button.
         *
         * These bypass the *confidence* threshold but never bypass cooldown or
         * duplicate suppression: a mis-tapped button should not produce two
         * recordings.
         */
        const val PHYSICAL_TRIGGER_CONFIDENCE = 1.0f
    }
}

/**
 * The single decision point for "should this trigger start a recording now?" (SPEC §10).
 *
 * Pure, synchronous and dependency-free so the safety rules can be exhaustively
 * unit tested:
 *
 *  - confidence threshold (acoustic triggers only),
 *  - cooldown between accepted activations,
 *  - duplicate-event suppression,
 *  - an "already recording" guard so a trigger cannot restart a live session.
 *
 * ### What this class is not
 *
 * It is **not** authentication and **not** identity verification. Anyone who can
 * say the phrase — or reach a physical trigger — can start a recording. That is
 * inherent to non-contact activation. THREAT_MODEL.md § "Activation is not
 * authentication" records it as an accepted, disclosed risk, and the onboarding
 * copy states it plainly.
 */
class ActivationGate(
    private val config: ActivationGateConfig,
    /** Monotonic clock. Ordering must never depend on the wall clock. */
    private val nowElapsedRealtimeMillis: () -> Long,
) {
    private var lastAcceptedAt: Long? = null
    private var recordingStartedAt: Long? = null

    fun onRecordingStarted(elapsedRealtimeMillis: Long) {
        recordingStartedAt = elapsedRealtimeMillis
    }

    fun onRecordingStopped() {
        recordingStartedAt = null
        // Reset the cooldown so the user can immediately start another recording.
        // Holding it back after an explicit stop would be surprising.
        lastAcceptedAt = null
    }

    /** Clears suppression state. Called when the user re-arms. */
    fun reset() {
        lastAcceptedAt = null
        recordingStartedAt = null
    }

    fun evaluate(candidate: ActivationCandidate): ActivationDecision {
        val now = nowElapsedRealtimeMillis()

        recordingStartedAt?.let { started ->
            return ActivationDecision.RejectedAlreadyRecording(now - started)
        }

        lastAcceptedAt?.let { last ->
            val since = now - last
            if (since < config.duplicateSuppressionWindowMillis) {
                return ActivationDecision.RejectedDuplicate(config.duplicateSuppressionWindowMillis - since)
            }
            if (since < config.cooldownMillis) {
                return ActivationDecision.RejectedCooldown(config.cooldownMillis - since)
            }
        }

        if (candidate.isAcoustic && candidate.confidence < config.confidenceThreshold) {
            // The *reason* is returned so the caller can count categories in
            // diagnostics. The candidate itself carries no audio and nothing
            // recognisable as speech, so no suppression logic is needed here.
            return ActivationDecision.RejectedLowConfidence(candidate.confidence, config.confidenceThreshold)
        }

        lastAcceptedAt = now
        return ActivationDecision.Accepted(candidate.source)
    }
}