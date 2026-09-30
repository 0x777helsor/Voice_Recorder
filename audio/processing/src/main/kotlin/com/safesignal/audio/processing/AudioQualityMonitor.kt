package com.safesignal.audio.processing

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Audio quality monitoring (SPEC §19).
 *
 * ### What this is for
 *
 * Not to improve the audio — quality enhancement is derivative and optional — but
 * to tell the user the truth about what was captured. A recording that is
 * silent, clipped, or captured while another app held the microphone is worse
 * than useless in an emergency, and silence is easy to mistake for "nothing
 * happened" later. So the app reports:
 *
 *  * that the capture is effectively silent,
 *  * that the input is clipping (levels pinned at full scale),
 *  * that the signal is very low,
 *  * that the microphone became unavailable or contended.
 *
 * ### What this deliberately does not do
 *
 * It does not estimate how intelligible speech is, and no UI may present these
 * numbers as a quality guarantee. SPEC §19 and §98 forbid implying that a
 * setting or a good level makes speech recoverable at distance. These are
 * *diagnostics*, and they are labelled as such.
 */
class AudioQualityMonitor(
    private val config: Config = Config(),
) {
    data class Config(
        /** RMS below which a window counts as silence. */
        val silenceThreshold: Float = 120f,
        /** RMS below which the signal counts as very low. */
        val lowSignalThreshold: Float = 700f,
        /** Fraction of samples at full scale above which clipping is reported. */
        val clippingRatioThreshold: Float = 0.005f,
        /** Rolling window length. Long enough to be stable, short enough to react. */
        val windowFrames: Int = 25,
    )

    /** A single observation. All values are 0..1 unless stated. */
    data class Observation(
        val rms: Float,
        val peak: Float,
        val clippingRatio: Float,
        val isSilent: Boolean,
        val isClipping: Boolean,
        val isLowSignal: Boolean,
    )

    private var window = FloatArray(config.windowFrames)
    private var windowIndex = 0
    private var windowCount = 0

    /** Feeds one PCM frame (16-bit). Returns the current rolling observation. */
    fun onFrame(frame: ShortArray): Observation {
        var sumSquares = 0.0
        var peak = 0
        var clipped = 0
        for (sample in frame) {
            val value = sample.toInt()
            sumSquares += (value * value).toDouble()
            val magnitude = abs(value)
            if (magnitude > peak) peak = magnitude
            if (magnitude >= FULL_SCALE - 1) clipped++
        }
        val rms = sqrt(sumSquares / frame.size.coerceAtLeast(1)).toFloat()

        window[windowIndex] = rms
        windowIndex = (windowIndex + 1) % window.size
        windowCount = minOf(windowCount + 1, window.size)

        // Rolling average: a single loud transient must not flip the verdict.
        var windowSum = 0f
        for (i in 0 until windowCount) windowSum += window[i]
        val meanRms = windowSum / windowCount
        val clippingRatio = clipped.toFloat() / frame.size.coerceAtLeast(1)

        return Observation(
            rms = meanRms,
            peak = peak.toFloat() / FULL_SCALE,
            clippingRatio = clippingRatio,
            isSilent = meanRms < config.silenceThreshold,
            isClipping = clippingRatio > config.clippingRatioThreshold,
            isLowSignal = !isSilentOf(meanRms) && meanRms < config.lowSignalThreshold,
        )
    }

    private fun isSilentOf(meanRms: Float) = meanRms < config.silenceThreshold

    fun reset() {
        window = FloatArray(config.windowFrames)
        windowIndex = 0
        windowCount = 0
    }

    companion object {
        /** Absolute peak of 16-bit signed PCM. */
        const val FULL_SCALE: Int = 32_767

        /**
         * A capture that produced nothing usable.
         *
         * Reported to the user as "the microphone captured almost no sound",
         * which is materially different information from "nothing happened".
         */
        fun isUnusable(observation: Observation): Boolean =
            observation.isSilent || observation.isClipping
    }
}

/**
 * Aggregate quality outcome attached to a recording (SPEC §18).
 *
 * Recorded as metadata so a later reader knows the capture was, for example,
 * mostly silent — without anyone having to guess.
 */
enum class CaptureQuality {
    /** Normal levels observed. */
    NORMAL,

    /** Almost no signal: microphone obstructed, muted, or pointed away. */
    NEAR_SILENT,

    /** Sustained clipping: input level too high, typically very close to a speaker. */
    CLIPPED,

    /** Microphone became unavailable part-way through the recording. */
    INTERRUPTED,
}