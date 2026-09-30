package com.safesignal.audio.processing

/**
 * User-selectable pre-roll policy (SPEC §12, §94).
 *
 * [DISABLED] is the default and is deliberately the least surprising choice.
 * Pre-roll means capturing audio *before* the user asked for a recording; that is
 * a genuine privacy cost, and SPEC §12 requires it to be opt-in with the
 * implications disclosed. Selecting a duration is the disclosure.
 *
 * Note what a non-zero policy does *not* do: it does not write anything to
 * persistent storage before activation. The audio lives in a bounded RAM ring
 * and is either discarded or prepended on activation. See
 * [RollingPreBuffer].
 */
enum class PreBufferPolicy(
    val seconds: Int,
    val userFacingLabel: String,
) {
    DISABLED(0, "Disabled — nothing is captured before activation"),
    FIVE_SECONDS(5, "5 seconds before activation"),
    TEN_SECONDS(10, "10 seconds before activation"),
    FIFTEEN_SECONDS(15, "15 seconds before activation"),
    ;

    val isEnabled: Boolean get() = seconds > 0

    /**
     * Memory this policy will hold, for display in settings and diagnostics.
     * Surfacing the figure is part of honest disclosure: the user can see what
     * "10 seconds" actually costs.
     */
    fun approximateBytes(sampleRateHz: Int = 48_000, channels: Int = 1): Int =
        if (!isEnabled) 0 else RollingPreBuffer.capacityFor(seconds, sampleRateHz, channels)

    companion object {
        fun fromSeconds(seconds: Int): PreBufferPolicy =
            entries.firstOrNull { it.seconds == seconds } ?: DISABLED
    }
}