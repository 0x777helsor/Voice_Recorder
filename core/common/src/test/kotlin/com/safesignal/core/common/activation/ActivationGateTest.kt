package com.safesignal.core.common.activation

import com.safesignal.core.common.state.ActivationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * False-positive protection tests (SPEC §10, §61).
 *
 * The gate is the component standing between "a detector said something" and
 * "a recording starts", so every suppression rule gets its own test.
 */
class ActivationGateTest {

    private var now = 1_000L
    private val clock = { now }

    private fun gate(config: ActivationGateConfig = ActivationGateConfig()) =
        ActivationGate(config, clock)

    private fun voice(confidence: Float, engineVersion: String = "test-1.0") =
        ActivationCandidate(
            source = ActivationSource.VOICE,
            confidence = confidence,
            engineVersion = engineVersion,
            phraseId = "default-phrase",
        )

    private fun physical() = ActivationCandidate(
        source = ActivationSource.PHYSICAL_BUTTON,
        confidence = ActivationCandidate.PHYSICAL_TRIGGER_CONFIDENCE,
        engineVersion = "trigger-1.0",
    )

    @Test
    fun `a confident voice activation is accepted`() {
        val decision = gate().evaluate(voice(0.90f))
        assertTrue(decision is ActivationDecision.Accepted)
        assertEquals(ActivationSource.VOICE, (decision as ActivationDecision.Accepted).source)
    }

    @Test
    fun `a low confidence detection is rejected`() {
        val decision = gate().evaluate(voice(0.40f))
        assertTrue(decision is ActivationDecision.RejectedLowConfidence)
        assertEquals(0.75f, (decision as ActivationDecision.RejectedLowConfidence).threshold, 0.0001f)
    }

    @Test
    fun `confidence exactly at the threshold is accepted`() {
        val decision = gate(ActivationGateConfig(confidenceThreshold = 0.75f)).evaluate(voice(0.75f))
        assertTrue(decision is ActivationDecision.Accepted)
    }

    @Test
    fun `physical triggers bypass the confidence threshold`() {
        assertTrue(gate().evaluate(physical()) is ActivationDecision.Accepted)
    }

    @Test
    fun `a second activation inside the duplicate window is suppressed`() {
        val gate = gate()
        assertTrue(gate.evaluate(physical()) is ActivationDecision.Accepted)
        now += 500
        val decision = gate.evaluate(physical())
        assertTrue(decision is ActivationDecision.RejectedDuplicate)
        assertTrue((decision as ActivationDecision.RejectedDuplicate).remainingMillis > 0)
    }

    @Test
    fun `a second activation inside the cooldown is rejected`() {
        val gate = gate(ActivationGateConfig(cooldownMillis = 10_000, duplicateSuppressionWindowMillis = 0))
        assertTrue(gate.evaluate(physical()) is ActivationDecision.Accepted)
        now += 6_000
        val decision = gate.evaluate(physical())
        assertTrue(decision is ActivationDecision.RejectedCooldown)
        assertEquals(4_000, (decision as ActivationDecision.RejectedCooldown).remainingMillis)
    }

    @Test
    fun `an activation after the cooldown is accepted`() {
        val gate = gate()
        assertTrue(gate.evaluate(physical()) is ActivationDecision.Accepted)
        now += 11_000
        assertTrue(gate.evaluate(physical()) is ActivationDecision.Accepted)
    }

    @Test
    fun `a trigger during an active recording cannot restart it`() {
        val gate = gate()
        gate.onRecordingStarted(now)
        now += 2_000
        val decision = gate.evaluate(physical())
        assertTrue(decision is ActivationDecision.RejectedAlreadyRecording)
        assertEquals(2_000, (decision as ActivationDecision.RejectedAlreadyRecording).elapsedMillis)
    }

    @Test
    fun `stopping a recording clears the cooldown so the user can record again immediately`() {
        val gate = gate()
        assertTrue(gate.evaluate(physical()) is ActivationDecision.Accepted)
        gate.onRecordingStarted(now)
        gate.onRecordingStopped()
        // No time advanced: a deliberate stop must not be rate-limited.
        assertTrue(gate.evaluate(physical()) is ActivationDecision.Accepted)
    }

    @Test
    fun `re-arming resets all suppression state`() {
        val gate = gate()
        gate.onRecordingStarted(now)
        gate.reset()
        assertTrue(gate.evaluate(physical()) is ActivationDecision.Accepted)
    }

    @Test
    fun `duplicate and already-recording rejections are hidden from the UI`() {
        // The UI should not nag a user about duplicate suppression.
        val gate = gate()
        gate.onRecordingStarted(now)
        assertTrue(!gate.evaluate(physical()).shouldReportToUi)
    }

    @Test
    fun `low confidence rejections stay visible for recognition test mode`() {
        assertTrue(gate().evaluate(voice(0.1f)).shouldReportToUi)
    }

    @Test
    fun `invalid configuration is rejected at construction`() {
        var threw = false
        try {
            ActivationGateConfig(confidenceThreshold = 1.5f)
        } catch (expected: IllegalArgumentException) {
            threw = true
        }
        assertTrue("out-of-range threshold must throw", threw)
    }
}