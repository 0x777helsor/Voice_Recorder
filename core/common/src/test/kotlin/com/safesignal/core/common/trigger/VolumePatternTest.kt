package com.safesignal.core.common.trigger

import com.safesignal.core.common.trigger.VolumePattern.Step
import com.safesignal.core.common.trigger.VolumePatternPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The volume-pattern trigger, with the emphasis on what it must *not* fire on.
 *
 * The dangerous test in this file is not "the pattern matches" — it is "ordinary
 * phone use does not match". Volume buttons get pressed constantly, and a false
 * accept records someone who never consented, which is the specific harm this
 * product exists to avoid.
 */
class VolumePatternTest {

    private val up = Step.UP
    private val down = Step.DOWN

    // ------------------------------------------------------------- policy

    @Test
    fun `a single press is rejected`() {
        val advice = VolumePatternPolicy.advise(VolumePattern(listOf(up)))
        assertFalse(advice.isAcceptable)
        assertTrue(advice.warnings.contains(PatternAdvice.Warning.TOO_SHORT))
    }

    @Test
    fun `an all-up pattern is rejected because skipping tracks looks identical`() {
        val advice = VolumePatternPolicy.advise(VolumePattern(listOf(up, up, up)))
        assertFalse(advice.isAcceptable)
        assertTrue(
            "the direction-change rule is the important one",
            advice.warnings.contains(PatternAdvice.Warning.NO_DIRECTION_CHANGE),
        )
    }

    @Test
    fun `a pattern with a direction change and three steps is accepted`() {
        val advice = VolumePatternPolicy.advise(VolumePattern(listOf(up, up, down)))
        assertTrue("expected acceptance, got ${advice.warnings}", advice.isAcceptable)
    }

    @Test
    fun `a repeated step is called out separately from an all-one-direction pattern`() {
        val advice = VolumePatternPolicy.advise(VolumePattern(listOf(up, down, down, down)))
        assertTrue(advice.warnings.contains(PatternAdvice.Warning.REPEATED_STEP))
        assertFalse(
            "this pattern does change direction, so that warning must not apply",
            advice.warnings.contains(PatternAdvice.Warning.NO_DIRECTION_CHANGE),
        )
    }

    @Test
    fun `an absurdly long pattern is rejected as unenterable under stress`() {
        val advice = VolumePatternPolicy.advise(VolumePattern(List(8) { up }))
        assertTrue(advice.warnings.contains(PatternAdvice.Warning.TOO_LONG))
    }

    // ------------------------------------------------------------- matching

    @Test
    fun `the pattern fires when entered in order`() {
        val matcher = VolumePatternMatcher(VolumePattern(listOf(up, up, down)))

        assertEquals(PatternProgress.Partial(1, 3), matcher.onPress(up, 0))
        assertEquals(PatternProgress.Partial(2, 3), matcher.onPress(up, 300))
        val result = matcher.onPress(down, 600)

        assertTrue(result is PatternProgress.Matched)
    }

    @Test
    fun `skipping tracks does not fire`() {
        // Up, up, up is what pressing volume-up between tracks produces. The
        // all-one-direction policy rejects it as a pattern, and this confirms even
        // a configured pattern is not matched by a run of the same direction
        // followed by an unrelated press.
        val matcher = VolumePatternMatcher(VolumePattern(listOf(up, down, up)))

        matcher.onPress(up, 0)
        matcher.onPress(up, 300)

        val result = matcher.onPress(up, 600)

        assertFalse("a run of identical presses must not match", result is PatternProgress.Matched)
    }

    @Test
    fun `the wrong press resets the attempt rather than sliding the window`() {
        val matcher = VolumePatternMatcher(VolumePattern(listOf(up, up, down)))

        matcher.onPress(up, 0)
        matcher.onPress(up, 300)
        // A third `up` where the pattern wants `down`. Genuinely a misfire.
        val misfire = matcher.onPress(up, 600)

        assertTrue("expected a misfire, got $misfire", misfire is PatternProgress.Misfired)

        // A sliding matcher would treat the `up` just pressed as the start of a new
        // attempt and only need one more `up` to fire. It must not: reuse of a
        // mistyped press is how button noise walks into a match.
        val next = matcher.onPress(up, 5_000)
        assertEquals(
            "the mistyped press must not count toward the new attempt",
            PatternProgress.Partial(1, 3),
            next,
        )
    }

    @Test
    fun `an intentional double press is not warned about`() {
        // `up, up, down` has a repeated step, and warning on every repeat would
        // push users toward longer, harder-to-enter patterns for no safety gain.
        val advice = VolumePatternPolicy.advise(VolumePattern(listOf(up, up, down)))
        assertTrue(advice.isAcceptable)
    }

    @Test
    fun `a triple run is warned about as indistinguishable from a held button`() {
        val advice = VolumePatternPolicy.advise(VolumePattern(listOf(up, up, up, down)))
        assertTrue(advice.warnings.contains(PatternAdvice.Warning.REPEATED_STEP))
    }

    @Test
    fun `a held button does not walk into the pattern`() {
        // Android delivers repeated key events while a key is held. If those were
        // treated as distinct presses, holding volume-up would eventually satisfy
        // any pattern of ups.
        val matcher = VolumePatternMatcher(VolumePattern(listOf(up, down, up)))

        matcher.onPress(up, 0)
        // Key repeat at ~40 ms: faster than a deliberate double-press.
        repeat(20) { index ->
            val result = matcher.onPress(up, 40L * (index + 1))
            assertFalse("key repeat must not match", result is PatternProgress.Matched)
        }
    }

    @Test
    fun `a slow gap abandons the attempt`() {
        val matcher = VolumePatternMatcher(VolumePattern(listOf(up, up, down)))

        matcher.onPress(up, 0)
        // Far beyond the 2 s window: this is not a pattern being entered slowly, it
        // is unrelated button use.
        val result = matcher.onPress(up, 30_000)

        assertTrue("the attempt should have restarted from this press", result is PatternProgress.Partial)
        assertEquals(PatternProgress.Partial(1, 3), result)
    }

    @Test
    fun `presses during the misfire penalty are ignored`() {
        val matcher = VolumePatternMatcher(VolumePattern(listOf(up, up, down)))

        matcher.onPress(down, 0) // misfire
        // Immediately try the real pattern, as someone mashing would.
        val ignored = matcher.onPress(up, 200)
        assertEquals(PatternProgress.Idle, ignored)

        // After the penalty expires it works again.
        val accepted = matcher.onPress(up, 2_000)
        assertEquals(PatternProgress.Partial(1, 3), accepted)
    }

    @Test
    fun `reset clears a partial attempt`() {
        val matcher = VolumePatternMatcher(VolumePattern(listOf(up, up, down)))

        matcher.onPress(up, 0)
        matcher.reset()

        assertEquals(PatternProgress.Partial(1, 3), matcher.onPress(up, 300))
    }

    @Test
    fun `a pattern round-trips through serialisation`() {
        val pattern = VolumePattern(listOf(up, down, up, down))
        assertEquals(pattern, VolumePattern.parse(pattern.serialise()))
    }

    @Test
    fun `garbage does not parse into a pattern`() {
        assertEquals(null, VolumePattern.parse("sideways"))
        assertEquals(null, VolumePattern.parse(""))
    }

    @Test
    fun `the pattern describes itself readably`() {
        assertEquals("up, up, down", VolumePattern(listOf(up, up, down)).describe())
    }
}
