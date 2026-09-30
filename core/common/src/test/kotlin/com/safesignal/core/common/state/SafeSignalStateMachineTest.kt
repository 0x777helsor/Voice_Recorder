package com.safesignal.core.common.state

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transition-table tests (SPEC §6, §61).
 *
 * The state machine is the component that keeps "network is not activation" and
 * "a failed upload is not a failed recording" true at runtime, so its illegal
 * edges are asserted explicitly rather than assumed.
 */
class SafeSignalStateMachineTest {

    private fun machine() = SafeSignalStateMachine()

    @Test
    fun `starts idle`() = runTest {
        assertEquals(SafeSignalPhase.IDLE, machine().currentPhase())
    }

    @Test
    fun `the documented happy path is accepted`() = runTest {
        val machine = machine()
        val path = listOf(
            SafeSignalPhase.ARMING,
            SafeSignalPhase.LISTENING,
            SafeSignalPhase.ACTIVATING,
            SafeSignalPhase.RECORDING,
            SafeSignalPhase.FINALIZING,
            SafeSignalPhase.STOPPED,
        )
        path.forEach { phase ->
            val result = machine.transitionTo(phase)
            assertTrue("$phase should be accepted from ${machine.currentPhase()}", result is TransitionResult.Accepted)
        }
        assertEquals(SafeSignalPhase.STOPPED, machine.currentPhase())
    }

    @Test
    fun `RECORDING to UPLOADING is not a legal transition`() = runTest {
        // Sync phases are modelled separately on purpose; if a UPLOADING phase is
        // ever added to SafeSignalPhase this test must fail loudly.
        val phases = SafeSignalPhase.entries.toSet()
        // Synchronization states must live in SyncPhase, never in SafeSignalPhase.
        assertEquals(SafeSignalPhase.entries.size, phases.size)
        assertFalse(phases.any { it.name.contains("UPLOAD") || it.name.contains("SYNC") })
        // A recording may only ever be followed by finalization.
        assertEquals(
            setOf(SafeSignalPhase.FINALIZING, SafeSignalPhase.FAILED),
            SafeSignalStateMachine.ALLOWED_TRANSITIONS.getValue(SafeSignalPhase.RECORDING),
        )
    }

    @Test
    fun `illegal transitions are rejected and recorded`() = runTest {
        val machine = machine()
        val result = machine.transitionTo(SafeSignalPhase.RECORDING)
        assertTrue(result is TransitionResult.Rejected)
        assertEquals(SafeSignalPhase.IDLE, machine.currentPhase())
        assertEquals(1, machine.rejections.value.size)
        assertEquals(SafeSignalPhase.RECORDING, machine.rejections.value.first().to)
    }

    @Test
    fun `LISTENING cannot jump straight to RECORDING`() = runTest {
        val machine = machine()
        machine.transitionTo(SafeSignalPhase.ARMING)
        machine.transitionTo(SafeSignalPhase.LISTENING)
        assertTrue(machine.transitionTo(SafeSignalPhase.RECORDING) is TransitionResult.Rejected)
        assertEquals(SafeSignalPhase.LISTENING, machine.currentPhase())
    }

    @Test
    fun `a rejected activation returns to LISTENING so the session continues`() = runTest {
        val machine = machine()
        machine.transitionTo(SafeSignalPhase.ARMING)
        machine.transitionTo(SafeSignalPhase.LISTENING)
        machine.transitionTo(SafeSignalPhase.ACTIVATING)

        // SPEC §10: a low-confidence detection must not end the armed session.
        val result = machine.transitionTo(SafeSignalPhase.LISTENING)
        assertTrue(result is TransitionResult.Accepted)
        assertEquals(SafeSignalPhase.LISTENING, machine.currentPhase())
    }

    @Test
    fun `duplicate activation while recording is rejected`() = runTest {
        val machine = machine()
        machine.transitionTo(SafeSignalPhase.ARMING)
        machine.transitionTo(SafeSignalPhase.LISTENING)
        machine.transitionTo(SafeSignalPhase.ACTIVATING)
        machine.transitionTo(SafeSignalPhase.RECORDING)

        assertTrue(machine.transitionTo(SafeSignalPhase.ACTIVATING) is TransitionResult.Rejected)
        assertEquals(SafeSignalPhase.RECORDING, machine.currentPhase())
    }

    @Test
    fun `a failure while recording still finalizes when evidence is preserved`() = runTest {
        val machine = machine()
        machine.transitionTo(SafeSignalPhase.ARMING)
        machine.transitionTo(SafeSignalPhase.LISTENING)
        machine.transitionTo(SafeSignalPhase.ACTIVATING)
        machine.transitionTo(SafeSignalPhase.RECORDING)

        val failure = SafeSignalFailure(
            stage = FailureStage.STORAGE,
            code = "low_storage",
            userMessage = "Storage is low, so the recording was stopped and sealed.",
            recoverable = true,
            preservedEvidence = true,
        )
        machine.reportFailure(failure, evidencePreserved = true)

        assertEquals(SafeSignalPhase.FINALIZING, machine.currentPhase())
        assertEquals(failure, machine.current.lastError)
    }

    @Test
    fun `a failure with no evidence captured fails without inventing a recording`() = runTest {
        val machine = machine()
        machine.transitionTo(SafeSignalPhase.ARMING)

        machine.reportFailure(
            SafeSignalFailure(
                stage = FailureStage.MICROPHONE,
                code = "mic_unavailable",
                userMessage = "Another app is using the microphone.",
                recoverable = true,
                preservedEvidence = false,
            ),
            evidencePreserved = false,
        )

        assertEquals(SafeSignalPhase.FAILED, machine.currentPhase())
    }

    @Test
    fun `sync state updates never change the recorder phase`() = runTest {
        val machine = machine()
        machine.transitionTo(SafeSignalPhase.ARMING)
        machine.transitionTo(SafeSignalPhase.LISTENING)
        machine.transitionTo(SafeSignalPhase.ACTIVATING)
        machine.transitionTo(SafeSignalPhase.RECORDING)

        machine.updateSync("rec-1", SyncPhase.UPLOADING)
        machine.updateSync("rec-1", SyncPhase.SYNCED)

        assertEquals(SafeSignalPhase.RECORDING, machine.currentPhase())
        assertEquals(SyncPhase.SYNCED, machine.current.syncPhaseByRecording["rec-1"])
    }

    @Test
    fun `a network failure never produces a FAILED recorder phase`() = runTest {
        val machine = machine()
        machine.transitionTo(SafeSignalPhase.ARMING)
        machine.transitionTo(SafeSignalPhase.LISTENING)
        machine.transitionTo(SafeSignalPhase.ACTIVATING)
        machine.transitionTo(SafeSignalPhase.RECORDING)
        machine.updateSync("rec-1", SyncPhase.FAILED)

        assertEquals(SafeSignalPhase.RECORDING, machine.currentPhase())
        assertEquals(SyncPhase.FAILED, machine.current.syncPhaseByRecording["rec-1"])
    }

    @Test
    fun `microphone-holding phases are exactly the armed ones`() {
        assertTrue(SafeSignalPhase.LISTENING.holdsMicrophone)
        assertTrue(SafeSignalPhase.ACTIVATING.holdsMicrophone)
        assertTrue(SafeSignalPhase.RECORDING.holdsMicrophone)
        assertFalse(SafeSignalPhase.IDLE.holdsMicrophone)
        assertFalse(SafeSignalPhase.STOPPED.holdsMicrophone)
        assertFalse(SafeSignalPhase.FINALIZING.holdsMicrophone)
    }

    @Test
    fun `every transition in the table is reachable from IDLE`() {
        // Guards against an orphaned phase: a state nothing can reach is dead code.
        val reachable = mutableSetOf(SafeSignalPhase.IDLE)
        var changed: Boolean
        do {
            changed = false
            for (from in reachable.toList()) {
                for (to in SafeSignalStateMachine.ALLOWED_TRANSITIONS.getValue(from)) {
                    if (reachable.add(to)) changed = true
                }
            }
        } while (changed)

        assertEquals(SafeSignalPhase.entries.toSet(), reachable)
    }
}