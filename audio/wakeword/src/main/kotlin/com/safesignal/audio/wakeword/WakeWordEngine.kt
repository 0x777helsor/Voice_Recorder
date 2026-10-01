package com.safesignal.audio.wakeword

import com.safesignal.core.common.model.InstantEpochMillis
import kotlinx.coroutines.flow.Flow

/**
 * How a detection was produced.
 *
 * The distinction is load-bearing. SPEC §9 requires that production and test
 * detections never be conflated, because a TEST activation must not produce an
 * evidence package that looks like a real one. Carrying provenance on the event
 * — rather than inferring it later from context — makes that impossible to get
 * wrong by accident.
 */
enum class DetectionSource {
    PRODUCTION,
    TEST,
}

/**
 * A single wake-word detection event (SPEC §9).
 *
 * Contains no audio. SPEC §92 is explicit that the detector must not persist the
 * ambient audio it analyses unless a pre-roll policy requires it, and the event
 * type has no field that could hold audio even by accident.
 */
data class WakeWordEvent(
    val phraseId: String,
    val confidence: Float,
    val detectedAtElapsedRealtime: Long,
    val detectedAtWallClock: InstantEpochMillis,
    val engineVersion: String,
    val source: DetectionSource,
) {
    init {
        require(confidence in 0f..1f) { "confidence must be within 0..1" }
    }
}

/** Tuning for detection sensitivity and false-positive suppression (SPEC §10). */
data class WakeWordConfig(
    /** Identifier of the configured phrase. */
    val phraseId: String,
    /** The phrase text, for display only. The engine may use a phoneme form. */
    val phraseText: String,
    /**
     * Minimum confidence to emit an event.
     *
     * Raising this reduces false activations at the cost of missed ones. There
     * is no free setting here, which is why the UI presents the trade-off rather
     * than a single "sensitivity" slider — and why SPEC §64 requires measured
     * false-accept/false-reject rates to be reported rather than assumed.
     */
    val confidenceThreshold: Float = 0.72f,
    /** Minimum gap between accepted activations (SPEC §10). */
    val cooldownMillis: Long = 15_000L,
    /**
     * Rejects a detection that is followed within this window by a contradicting
     * one, reducing single-spurious-frame activations. 0 disables.
     */
    val confirmationWindowMillis: Long = 0L,
    /**
     * Report detections to the UI without activating. Lets a user verify their
     * phrase and environment before relying on it (SPEC §10, §33).
     */
    val recognitionTestMode: Boolean = false,
    /**
     * Reduce detector work while listening, trading sensitivity for battery
     * (SPEC §31). The detector must state its own detection rate at this setting
     * rather than assuming it is acceptable.
     */
    val lowPowerMode: Boolean = false,
) {
    init {
        require(confidenceThreshold in 0f..1f) { "confidenceThreshold must be within 0..1" }
        require(cooldownMillis >= 0) { "cooldownMillis must be non-negative" }
    }
}

/**
 * The wake-word boundary (SPEC §8).
 *
 * ### The invariant this interface exists to protect
 *
 * Activation must never depend on connectivity. A production implementation of
 * this interface performs recognition **entirely on the device**; it must not
 * stream ambient audio to a cloud recogniser to check for a phrase. That rules
 * out using a general cloud speech API as the activation path, regardless of how
 * convenient it would be — SPEC §7.1 and §14 require offline activation, and §14
 * is unambiguous that a network failure must not affect activation.
 *
 * The interface exists so a better on-device model can be swapped in later
 * without touching the recording architecture. [MockWakeWordEngine] and
 * [LocalWakeWordEngine] are both behind it.
 */
interface WakeWordEngine {

    suspend fun initialize(config: WakeWordConfig): Result<Unit>

    /** Hot stream of detections. Completes on [release]. */
    fun events(): Flow<WakeWordEvent>

    suspend fun start(): Result<Unit>

    /**
     * Offers one captured audio frame for analysis, returning whether it matched.
     *
     * Part of the interface rather than an implementation detail because the caller
     * must own this decision. A detector with its own `AudioRecord` would be
     * requesting a second open of a microphone the platform may refuse to grant, and
     * even if granted the two streams are not guaranteed to be aligned — so the
     * detector could fire on audio that was never recorded, and the recorder's
     * pre-roll ring could hold different audio from the one the detector analysed.
     * Feeding the recorder's own frames is the only arrangement that keeps detection
     * and evidence in step.
     *
     * The array is the recorder's live buffer and is reused between reads. An
     * implementation that needs to retain it must copy, and must not assume the
     * contents are stable after returning.
     *
     * @return true when this frame alone met the configured confidence threshold.
     *   Implementations with internal state (windowing, smoothing) may return false
     *   for a frame that contributed to a later detection.
     */
    suspend fun submitFrame(pcm: ShortArray): Boolean

    suspend fun stop()

    suspend fun release()

    /**
     * Whether this engine can actually detect the configured phrase.
     *
     * Distinct from a successful [initialize]. The local template engine
     * initialises successfully while unenrolled and then legitimately matches
     * nothing, so "armed" over an unenrolled detector would be a lie — the user
     * would say the phrase and nothing would happen, with every indicator
     * reporting a healthy armed session.
     */
    val isEnrolled: Boolean

    /**
     * Engine identification, persisted in evidence metadata (SPEC §93).
     *
     * A future model update must not invalidate historical recordings, so
     * [engineName] and [engineVersion] are recorded at record time rather than
     * read back at display time.
     */
    val engineName: String

    val engineVersion: String

    /**
     * Whether this engine is usable in production.
     *
     * A `false` here must prevent the engine from being selected as the production
     * activation path. Development builds may use a test engine; release builds
     * must not (SPEC §8, §33).
     */
    val isProductionReady: Boolean
}