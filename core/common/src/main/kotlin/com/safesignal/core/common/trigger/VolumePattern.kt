package com.safesignal.core.common.trigger

/**
 * A user-chosen volume-button pattern that starts a recording.
 *
 * ### Why a pattern at all
 *
 * Because the app's core function must not fail silently at the moment it is
 * needed. A wake word is a probabilistic detector with no measured accuracy; a
 * pattern pressed deliberately is not. The two have different failure modes, so
 * supporting both means neither one is a single point of failure.
 *
 * ### Why the pattern is not a secret
 *
 * A volume pattern is low-entropy and learnable from watching someone press it once.
 * It is a convenience trigger, not an authentication factor, and the app must never
 * imply otherwise. Activation is not authentication — see THREAT_MODEL.md.
 *
 * ### The false-accept problem, which is the serious one
 *
 * Volume buttons are pressed constantly: by the user, by whoever else is holding
 * the phone, by a pocket. A false accept records someone who never consented, which
 * is precisely the harm this product exists to prevent — and volume presses are far
 * more frequent than anyone saying a specific phrase. Every rule below exists to
 * make an accidental match unlikely.
 */
data class VolumePattern(
    val steps: List<Step>,
) {
    init {
        require(steps.isNotEmpty()) { "a pattern must have at least one step" }
    }

    enum class Step { UP, DOWN }

    /** Upper bound on a usable pattern. Longer is not safer, only slower. */
    val length: Int get() = steps.size

    /** Human-readable form for display, e.g. "up, up, down, up". */
    fun describe(): String = steps.joinToString(", ") { step ->
        if (step == Step.UP) "up" else "down"
    }

    /** Stable text form for persistence. Round-trips through [parse]. */
    fun serialise(): String = steps.joinToString(",") { step ->
        if (step == Step.UP) "UP" else "DOWN"
    }

    companion object {
        fun parse(text: String): VolumePattern? {
            val steps = text.split(",").map { part ->
                when (part.trim().uppercase()) {
                    "UP" -> Step.UP
                    "DOWN" -> Step.DOWN
                    else -> return null
                }
            }
            return runCatching { VolumePattern(steps) }.getOrNull()
        }
    }
}

/**
 * How much confidence a chosen pattern deserves, and what is wrong with it.
 *
 * Returned as named reasons rather than a score so the picker can tell someone
 * *which* property to change. "Weak pattern" is useless advice; "all the same
 * direction — that is what skipping tracks looks like" is actionable.
 */
data class PatternAdvice(
    val isAcceptable: Boolean,
    val warnings: List<Warning>,
) {
    enum class Warning {
        /** One press is indistinguishable from adjusting the volume. */
        TOO_SHORT,

        /**
         * All steps the same direction.
         *
         * The most important rejection. Skipping tracks produces up-up-up, and a
         * held or double-tapped button produces repeats, so an all-one-direction
         * pattern would match routine use of the phone.
         */
        NO_DIRECTION_CHANGE,

        /**
         * Three or more consecutive identical steps.
         *
         * A *double* press is deliberate and is deliberately not warned about —
         * `up, up, down` is a perfectly good pattern, and warning on it would push
         * users toward longer, harder-to-enter patterns for no safety gain. A
         * triple is different: that is what a held button produces on its own, and
         * it is indistinguishable from intent.
         */
        REPEATED_STEP,

        /** Long enough that entering it under pressure becomes unlikely. */
        TOO_LONG,
    }
}

/**
 * Judges a proposed pattern.
 *
 * Deliberately a pure function with no timing or state, so the rules can be
 * exhaustively tested and the same judgement is applied in the picker and at
 * arming time. A pattern accepted in one place and rejected in another is how a
 * user ends up with a trigger that silently does not work.
 */
object VolumePatternPolicy {

    /** Below this, a pattern is not distinguishable from a single volume press. */
    const val MIN_STEPS = 3

    /** Above this, the pattern stops being enterable under stress. */
    const val MAX_STEPS = 5

    fun advise(pattern: VolumePattern): PatternAdvice {
        val warnings = buildList {
            if (pattern.length < MIN_STEPS) add(PatternAdvice.Warning.TOO_SHORT)
            if (pattern.length > MAX_STEPS) add(PatternAdvice.Warning.TOO_LONG)

            val directions = pattern.steps.toSet()
            if (directions.size == 1) add(PatternAdvice.Warning.NO_DIRECTION_CHANGE)

            if (hasTripleRun(pattern.steps)) add(PatternAdvice.Warning.REPEATED_STEP)
        }
        // A triple run is a specific case of "no direction change" in an all-same
        // pattern, so it is only worth reporting on its own when the pattern does
        // change direction. Reporting both would tell the user to fix the same
        // thing twice.
        val distinct = if (pattern.steps.toSet().size > 1) {
            warnings
        } else {
            warnings - PatternAdvice.Warning.REPEATED_STEP
        }
        return PatternAdvice(isAcceptable = distinct.isEmpty(), warnings = distinct.distinct())
    }

    private fun hasTripleRun(steps: List<VolumePattern.Step>): Boolean =
        steps.zipWithNext().zipWithNext().any { (a, b) -> a.first == a.second && a.second == b.second }
}

/** Where a pattern attempt currently stands. */
sealed interface PatternProgress {
    /** No press, or a partial attempt that has been abandoned. */
    data object Idle : PatternProgress

    /** Presses seen so far that still match the beginning of the pattern. */
    data class Partial(val matchedSteps: Int, val expectedSteps: Int) : PatternProgress

    /** The full pattern was entered. */
    data class Matched(val pattern: VolumePattern) : PatternProgress

    /**
     * A press that did not fit, with how long to wait before the next attempt is
     * accepted.
     *
     * The delay is what stops a mashing session from being retried into a match.
     * Without it, holding and releasing the button cycles through prefixes and can
     * eventually land on the pattern by chance.
     */
    data class Misfired(val retryAfterMillis: Long) : PatternProgress
}

/**
 * Matches a stream of volume presses against a [VolumePattern].
 *
 * ### Why a prefix is abandoned rather than shifted
 *
 * A sliding-window matcher would resynchronise mid-attempt, so a mistyped press
 * would be reused as the start of the next attempt. That turns accidental button
 * noise into a much higher chance of eventually matching, which is the exact
 * failure this class exists to prevent. A wrong press resets the attempt outright.
 *
 * ### Timing
 *
 * Two rules, and both are about a held button rather than a deliberate one:
 *
 *  * **Too fast** — presses closer together than [minIntervalMillis] are treated as
 *    key repeat, not separate presses, and are dropped. Android delivers
 *    `ACTION_KEY_EVENT` continuously while a key is held; a deliberate press is a
 *    press and a release.
 *  * **Too slow** — a gap longer than [maxIntervalMillis] abandons the attempt.
 *    A partial pattern left over from a minute ago is not a pattern.
 */
class VolumePatternMatcher(
    private val pattern: VolumePattern,
    private val minIntervalMillis: Long = DEFAULT_MIN_INTERVAL_MILLIS,
    private val maxIntervalMillis: Long = DEFAULT_MAX_INTERVAL_MILLIS,
    private val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MILLIS,
) {
    private var matched = 0
    private var lastPressAt: Long? = null
    private var blockedUntil: Long = 0

    /** Drops any partial attempt, e.g. when the session is re-armed. */
    fun reset() {
        matched = 0
        lastPressAt = null
    }

    fun onPress(step: VolumePattern.Step, atElapsedRealtimeMillis: Long): PatternProgress {
        // Still serving the penalty from a previous misfire. Dropping the press is
        // correct: honouring it would let a held button walk into the pattern.
        if (atElapsedRealtimeMillis < blockedUntil) return PatternProgress.Idle

        val previous = lastPressAt
        if (previous != null && atElapsedRealtimeMillis - previous < minIntervalMillis) {
            // Key repeat, not a press.
            return currentProgress()
        }
        if (previous != null && atElapsedRealtimeMillis - previous > maxIntervalMillis) {
            // The attempt went stale. Start again from this press.
            matched = 0
        }
        lastPressAt = atElapsedRealtimeMillis

        if (pattern.steps[matched] == step) {
            matched++
            if (matched == pattern.steps.size) {
                matched = 0
                lastPressAt = null
                return PatternProgress.Matched(pattern)
            }
            return PatternProgress.Partial(matched, pattern.steps.size)
        }

        matched = 0
        lastPressAt = atElapsedRealtimeMillis
        blockedUntil = atElapsedRealtimeMillis + retryDelayMillis
        return PatternProgress.Misfired(retryDelayMillis)
    }

    private fun currentProgress(): PatternProgress =
        if (matched == 0) PatternProgress.Idle else PatternProgress.Partial(matched, pattern.steps.size)

    companion object {
        /**
         * 120 ms.
         *
         * Below a deliberate double-press and above Android's key-repeat rate
         * (~40 ms), which is what makes a held button distinguishable at all.
         */
        const val DEFAULT_MIN_INTERVAL_MILLIS = 120L

        /** 2 s. Long enough to enter deliberately, short enough not to feel broken. */
        const val DEFAULT_MAX_INTERVAL_MILLIS = 2_000L

        /** 1.5 s penalty after a wrong press, so noise cannot be retried into a match. */
        const val DEFAULT_RETRY_DELAY_MILLIS = 1_500L
    }
}
