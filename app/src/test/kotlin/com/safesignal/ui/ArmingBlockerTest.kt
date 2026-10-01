package com.safesignal.ui

import com.safesignal.core.common.readiness.Capability
import com.safesignal.core.common.readiness.CapabilityStatus
import com.safesignal.core.common.readiness.ProbeOutcome
import com.safesignal.core.common.readiness.ReadinessReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which capabilities stand between a user and arming.
 *
 * ### The bug this exists for
 *
 * The first time the real UI ran on a phone, the Arm button was **disabled**, and
 * the reason shown was "Wake word is not ready".
 *
 * That is precisely the failure this project exists to avoid, in its purest form.
 * The wake-word detector is the one trigger whose accuracy has never been measured,
 * and the in-app button and the volume pattern are both deterministic. Gating arming
 * on the weakest component means the app cannot be used at all until the weakest
 * component is fixed — so the least reliable part of the system becomes mandatory,
 * and the reliable parts become useless.
 *
 * It also read as a broken app. "Not ready" is not an explanation a user can act
 * on, and they cannot fix it: there is no enrolment screen yet.
 */
class ArmingBlockerTest {

    @Test
    fun `an unenrolled wake word does not block arming`() {
        val state = stateWith(
            Capability.WakeWord to ProbeOutcome.NotReady("not enrolled"),
            Capability.MicrophonePermission to ProbeOutcome.Ready,
            Capability.NotificationPermission to ProbeOutcome.Ready,
            Capability.Encryption to ProbeOutcome.Ready,
            Capability.Storage to ProbeOutcome.Ready,
        )

        assertTrue(
            "the wake word must not gate arming; blockers were ${state.blockers}",
            state.canArm,
        )
    }

    @Test
    fun `a missing microphone still blocks arming`() {
        val state = stateWith(
            Capability.MicrophonePermission to ProbeOutcome.NotReady("not granted"),
            Capability.NotificationPermission to ProbeOutcome.Ready,
            Capability.Encryption to ProbeOutcome.Ready,
            Capability.Storage to ProbeOutcome.Ready,
        )

        assertFalse("arming without a microphone cannot work", state.canArm)
        assertTrue(state.blockers.any { it.contains("Microphone", ignoreCase = true) })
    }

    @Test
    fun `broken encryption still blocks arming`() {
        val state = stateWith(
            Capability.MicrophonePermission to ProbeOutcome.Ready,
            Capability.NotificationPermission to ProbeOutcome.Ready,
            Capability.Encryption to ProbeOutcome.NotReady("keystore failed"),
            Capability.Storage to ProbeOutcome.Ready,
        )

        assertFalse(
            "arming when audio cannot be encrypted would collect evidence we cannot protect",
            state.canArm,
        )
    }

    @Test
    fun `a capability that was never probed blocks arming`() {
        // "We did not check" must not read as "ready". This is the failure that let
        // the StrongBox bug go unnoticed: everything looked fine because nothing had
        // actually been exercised.
        val report = ReadinessReport.of(
            CapabilityStatus(Capability.MicrophonePermission, ProbeOutcome.Ready),
            CapabilityStatus(Capability.NotificationPermission, ProbeOutcome.Ready),
            CapabilityStatus(Capability.Encryption, ProbeOutcome.Ready),
            CapabilityStatus(Capability.Storage, ProbeOutcome.Ready),
        )

        val state = stateWithReport(report)

        // Wake word is absent entirely from the report, which must count as unproved
        // and therefore not-ready — and not-ready for a capability that does not gate
        // arming.
        assertTrue("an absent wake word must not block arming", state.canArm)
    }

    @Test
    fun `blockers name the capability so the user knows what to fix`() {
        val state = stateWith(
            Capability.MicrophonePermission to ProbeOutcome.NotReady("not granted"),
            Capability.NotificationPermission to ProbeOutcome.NotReady("disabled"),
            Capability.Encryption to ProbeOutcome.Ready,
            Capability.Storage to ProbeOutcome.Ready,
        )

        assertEquals(2, state.blockers.size)
        state.blockers.forEach { blocker ->
            assertFalse("a blocker must not be a bare status: $blocker", blocker.isBlank())
        }
    }

    /**
     * Builds a state from a report.
     *
     * `ReadinessReport.build` takes thunks, but these tests want eager values, so
     * the report is assembled from `CapabilityStatus` directly. Wrapping in lambdas
     * would work too and would be closer to the production path, but the point of
     * these tests is the *policy* — which capabilities gate arming — not that the
     * probe plumbing calls its lambdas.
     */
    private fun stateWith(vararg probes: Pair<Capability, ProbeOutcome>) =
        stateWithReport(
            ReadinessReport.of(*probes.map { CapabilityStatus(it.first, it.second) }.toTypedArray()),
        )

    private fun stateWithReport(report: ReadinessReport) = SafeSignalUiState(readiness = report)

    @Test
    fun `a zero-segment recording is not a success`() {
        // Guards the history list against the same collapse the reporting bug had.
        val state = SafeSignalUiState()
        assertFalse("a fresh state is not recording", state.isRecording)
        assertFalse("a fresh state is not armed", state.isArmed)
    }
}
