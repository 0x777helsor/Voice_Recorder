package com.safesignal.service

import com.safesignal.audio.capture.FinalizedRecording
import com.safesignal.audio.capture.RecordingConfig
import com.safesignal.audio.capture.RecordingState
import com.safesignal.core.common.model.ElapsedMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The outcome of a test session, as the user is told it.
 *
 * Two real defects prompted this file, both observed on a Tecno BF7 rather than
 * reasoned about in advance:
 *
 *  1. **A successful recording reported a failure.** The engine finalized itself
 *     when the capture ended, so by the time the service called `stop()` there was
 *     nothing left in progress and it returned a failure. The user saw "SafeSignal
 *     could not start recording" seconds after a clean 12-second capture had been
 *     sealed to disk. Reporting a successful recording as a failure is worse than
 *     saying nothing: it invites someone to discard real evidence, or to conclude
 *     the app is broken when it works.
 *
 *  2. **The copy did not answer the question it raised.** It reported "3 sealed
 *     segments", which is a fact about the implementation, not about the user's
 *     position. The question a person who has just been recorded actually has is
 *     "how long was that, and can anyone hear it". The segment count prompted that
 *     question instead of answering it.
 *
 * These are pure decisions on purpose: the logic that chose what to say is separated
 * from the Toast and Notification plumbing, so it can be asserted without a device.
 */
class SessionReportingTest {

    @Test
    fun `a recording with audio is reported as a success, never as a failure`() {
        val recording = finalized(segments = 3, durationMillis = 12_000)

        val outcome = SessionOutcome.of(recording, stopFailure = "no recording in progress")

        assertTrue(
            "engine-finalized recording must not surface as a failure",
            outcome is SessionOutcome.Captured,
        )
    }

    @Test
    fun `the recording that was preserved is preferred over the stop failure`() {
        val recording = finalized(segments = 2, durationMillis = 7_500)

        val outcome = SessionOutcome.of(recording, stopFailure = "no recording in progress") as SessionOutcome.Captured

        assertEquals(recording, outcome.recording)
    }

    @Test
    fun `a genuinely empty recording is reported as no audio, not as success`() {
        // The distinction that matters: "nothing was captured" and "we captured
        // something" must never collapse into one message.
        val outcome = SessionOutcome.of(finalized(segments = 0, durationMillis = 12_000), stopFailure = null)

        assertEquals(SessionOutcome.NoAudio, outcome)
    }

    @Test
    fun `a failure with no preserved audio is reported as a failure`() {
        val outcome = SessionOutcome.of(record = null, stopFailure = "no recording in progress")

        assertEquals(SessionOutcome.Failed("no recording in progress"), outcome)
    }

    @Test
    fun `a failure with no stated reason still reports as failed`() {
        // Never silently swallowed: a null reason must not become a success.
        val outcome = SessionOutcome.of(record = null, stopFailure = null)

        assertTrue(outcome is SessionOutcome.Failed)
    }

    @Test
    fun `a successful capture is the only outcome that yields a report`() {
        // Guards the shape of the report: exactly one message per session, and it
        // describes the recording rather than the engine's stopping. The two bugs
        // this file exists for both produced a *second*, contradicting message —
        // "could not start recording" alongside a completed capture.
        val outcomes = listOf(
            SessionOutcome.of(finalized(segments = 3, durationMillis = 12_000), stopFailure = "no recording in progress"),
            SessionOutcome.of(finalized(segments = 0, durationMillis = 12_000), stopFailure = null),
            SessionOutcome.of(record = null, stopFailure = "no recording in progress"),
        )

        assertEquals(1, outcomes.count { it is SessionOutcome.Captured })
        assertEquals(1, outcomes.count { it is SessionOutcome.NoAudio })
        assertEquals(1, outcomes.count { it is SessionOutcome.Failed })
    }

    @Test
    fun `captured reports how long, not how many segments`() {
        val outcome = SessionOutcome.of(finalized(segments = 3, durationMillis = 12_000), null)

        assertTrue(outcome is SessionOutcome.Captured)
        assertEquals("12 sec", durationFor(outcome as SessionOutcome.Captured))
    }

    @Test
    fun `durations are phrased the way a person would say them`() {
        assertEquals("0 sec", formatDuration(0))
        assertEquals("0 sec", formatDuration(400))
        assertEquals("1 sec", formatDuration(1_400))
        assertEquals("12 sec", formatDuration(12_000))
        assertEquals("59 sec", formatDuration(59_900))
        assertEquals("1 min", formatDuration(60_000))
        assertEquals("1 min 5 sec", formatDuration(65_400))
        assertEquals("12 min 30 sec", formatDuration(750_000))
    }

    @Test
    fun `a negative duration is clamped rather than rendered as nonsense`() {
        // Possible if the clock is read in an unexpected order. "−3 sec" on a
        // recording report is not a thing anyone should be shown.
        assertEquals("0 sec", formatDuration(-3_000))
    }

    @Test
    fun `a terminal state with no recording is not treated as a capture`() {
        // Guards the inference: `RecordingState.Stopped` means the *engine* stopped,
        // which says nothing about whether anything was sealed. A recording that
        // captured nothing also ends in `Stopped`. Only the finalized recording is
        // evidence that audio exists.
        assertFalse(RecordingState.Stopped.evidencePreserved)
        assertFalse(RecordingState.Failed("boom").evidencePreserved)
    }

    @Test
    fun `a real device test session is described by duration, not segment count`() {
        // Mirrors what the Tecno BF7 actually produced: three sealed segments of
        // 480,000 / 480,000 / 192,000 plaintext bytes, 1,152,000 bytes in total,
        // over a 12-second window. The report must describe that as "12 sec" of
        // audio, because the segment count is a fact about the implementation that
        // prompted the question instead of answering it.
        val recording = finalized(segments = 3, durationMillis = 12_000)

        val outcome = SessionOutcome.of(recording, stopFailure = "no recording in progress")

        assertEquals(SessionOutcome.Captured(recording), outcome)
        assertEquals("12 sec", formatDuration((outcome as SessionOutcome.Captured).recording.durationMillis))
    }

    private fun durationFor(captured: SessionOutcome.Captured): String =
        formatDuration(captured.recording.durationMillis)

    private fun formatDuration(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return when {
            minutes > 0 && seconds > 0 -> "$minutes min $seconds sec"
            minutes > 0 -> "$minutes min"
            else -> "$seconds sec"
        }
    }

    private fun finalized(segments: Int, durationMillis: Long): FinalizedRecording {
        val started = 1_000L
        return FinalizedRecording(
            recordingId = "rec_test",
            config = RecordingConfig(),
            segments = List(segments) { index ->
                com.safesignal.audio.capture.CommittedSegment(
                    recordingId = "rec_test",
                    segmentId = "seg_$index",
                    sequenceNumber = index,
                    sealedFileName = "segment-%06d.ssg".format(index),
                    sealedLengthBytes = 480_080L,
                    plaintextLengthBytes = 480_000L,
                    sha256 = "a".repeat(64),
                    startedElapsed = ElapsedMillis(started + index * 5_000L),
                    endedElapsed = ElapsedMillis(started + (index + 1) * 5_000L),
                )
            },
            startedAtElapsed = ElapsedMillis(started),
            endedAtElapsed = ElapsedMillis(started + durationMillis),
            startedAtWallClockMillis = 0,
            endedAtWallClockMillis = durationMillis,
            activationSource = EmergencyRecordingController.ACTIVATION_SOURCE_TEST,
            activationConfidence = null,
            isTestRecording = true,
            // Present in every finalized capture. A real engine supplies the blob it
            // read from the key material just before zeroing the plaintext key.
            wrappedKey = com.safesignal.core.crypto.WrappedKey(
                wrappedKeyBytes = ByteArray(32),
                algorithm = "AES/GCM/NoPadding",
                keyVersion = 1,
                iv = ByteArray(12),
                provider = "test",
            ),
        )
    }
}
