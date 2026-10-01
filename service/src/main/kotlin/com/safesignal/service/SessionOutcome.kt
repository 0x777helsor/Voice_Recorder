package com.safesignal.service

import com.safesignal.audio.capture.FinalizedRecording

/**
 * What a finished session actually did, decided before any of it is worded.
 *
 * This exists because the decision *was* being made, implicitly, by whichever branch
 * ran first — and it got it wrong in the worst possible direction. Observed on a
 * Tecno BF7: a clean 12-second capture sealed three segments, and then reported
 * "SafeSignal could not start recording".
 *
 * The cause was ordering. The engine finalizes itself when capture ends, so by the
 * time the service asked the controller to stop, there was nothing in progress and
 * the stop returned a failure. The code treated that failure as the outcome and
 * threw away a finalized recording that was sitting right there.
 *
 * So the rule is now explicit and lives in one place:
 *
 * **A finalized recording outranks a stop failure.** If audio was sealed, the session
 * succeeded, whatever the stop call said. There is no arrangement in which sealed
 * evidence and "could not start recording" are both true.
 *
 * The three outcomes are also kept distinct rather than collapsed, because
 * "captured nothing" and "captured something" are opposite facts and a single
 * success/failure flag loses that. A test that captured silence is a real failure
 * mode — some devices return success with all-zero buffers when the microphone is
 * muted or obstructed — and it must not be reported as a pass.
 */
sealed interface SessionOutcome {

    /** Audio was captured and sealed. The reason the stop call failed is irrelevant. */
    data class Captured(val recording: FinalizedRecording) : SessionOutcome

    /** The session ran to completion and sealed nothing at all. */
    data object NoAudio : SessionOutcome

    /** The session ended badly and no audio was preserved. */
    data class Failed(val reason: String) : SessionOutcome

    companion object {

        /**
         * Decides the outcome from what is known, preferring preserved evidence.
         *
         * @param record the finalized recording, if one exists — either returned by
         *   the stop call or already held by the controller from a self-finalizing
         *   engine. Null means nothing was sealed.
         * @param stopFailure why the stop call failed, if it did. Recorded for the
         *   log and used only when there is no recording to report.
         */
        fun of(record: FinalizedRecording?, stopFailure: String?): SessionOutcome = when {
            // Evidence first. Never the other way round.
            record != null && record.segments.isNotEmpty() -> Captured(record)
            record != null -> NoAudio
            else -> Failed(stopFailure ?: "unknown reason")
        }
    }
}
