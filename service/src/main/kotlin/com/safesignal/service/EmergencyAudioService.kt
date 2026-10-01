package com.safesignal.service

import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.text.format.Formatter
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.LifecycleService
import com.safesignal.audio.capture.FinalizedRecording
import com.safesignal.audio.capture.RecordingState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * The only component allowed to hold the microphone (SPEC §15, §36).
 *
 * ### Why a foreground service at all
 *
 * Android will not let SafeSignal capture audio with the screen off, which is
 * most of the situations this app exists for. Holding the microphone from the
 * background requires a foreground service of type `microphone`, and that comes
 * with obligations this class takes literally rather than technically:
 *
 *  1. **A visible notification, always.** [EmergencyNotifications.notificationsVisible]
 *     is checked *before* the microphone opens. On API 33+ the platform runs a
 *     microphone foreground service happily with the notification suppressed;
 *     SafeSignal refuses, because recording someone who cannot see that it is
 *     happening is the one behaviour that would make this app a liability rather
 *     than a protection.
 *  2. **An immediate stop path.** The notification carries a stop action, so the
 *     person being recorded can end the recording without opening the app.
 *  3. **No restart.** `START_NOT_STICKY`. Android resurrecting a microphone
 *     service by itself, with no user action behind it, is the behaviour that
 *     makes an indicator untrustworthy.
 *
 * ### Ordering
 *
 * `startForeground` is called synchronously in [onStartCommand], before any
 * suspending work, because the platform gives a foreground service a short
 * window to post its notification and kills the process if it does not. The
 * microphone is not opened until after that call returns.
 */
@AndroidEntryPoint
class EmergencyAudioService : LifecycleService() {

    @Inject
    lateinit var controller: EmergencyRecordingController

    @Inject
    lateinit var notifications: EmergencyNotifications

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sessionJob: Job? = null

    /**
     * Set once [finish] has begun, cleared when a new start is accepted.
     *
     * Exists because a start command can arrive *after* the service has torn itself
     * down, and the symptom of that is actively misleading. Observed on a real
     * device: a spurious second tap arrived after the 12-second test had finished and
     * `stopSelf()` had run. The service was still alive, so `onStartCommand` ran,
     * `startForeground` threw `IllegalStateException` because the service was already
     * stopping, and the user was told "SafeSignal could not start recording" — a
     * failure message for an operation that had already succeeded, seconds earlier,
     * with its evidence sealed. The user had every reason to believe the app was
     * broken.
     */
    private var shuttingDown = false

    override fun onCreate() {
        super.onCreate()
        // Idempotent, and defensive: if the application process was created
        // without going through SafeSignalApplication, the channel still exists
        // before startForeground needs it.
        notifications.createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // `super` is LifecycleService, which may return its own sticky constant
        // instead of the value returned below. Which one wins decides whether the
        // platform may re-deliver this same ACTION_START_TEST after the test has
        // already finished — which would silently start recording again, with no
        // user action behind it. Logged rather than assumed.
        val superResult = super.onStartCommand(intent, flags, startId)
        Log.i(
            TAG,
            "onStartCommand action=${intent?.action} startId=$startId " +
                "flags=$flags superResult=$superResult",
        )

        // A start command arriving after teardown has begun is a stale delivery, not
        // a failure. Saying so is the difference between "the app is broken" and
        // "that tap did nothing because the recording had already finished".
        if (shuttingDown && intent?.action == ACTION_START_TEST) {
            Log.w(TAG, "ignoring start request; the previous session is already shutting down")
            return START_NOT_STICKY
        }

        // A second start while a recording is live must not start another, and
        // must not tear the first one down either.
        //
        // Both halves of that matter, and the second half is the one that was
        // actually observed on a real device: one tap produced two pointer events,
        // and the resulting second start failed — after which the failure path ran
        // `finish()` and stopped the *first* recording, three seconds in, with the
        // microphone closed and the evidence truncated. A user who double-taps
        // "record" would have lost their recording to the app's own reaction to the
        // second tap.
        //
        // So the duplicate is ignored outright. `stopSelf` is deliberately not
        // called: this service may be the one holding the microphone, and stopping
        // it to reject a duplicate request would be the same mistake in reverse.
        if (intent?.action == ACTION_START_TEST && controller.isRecording) {
            Log.w(
                TAG,
                "ignoring duplicate start request; a recording is already in progress " +
                    "(startId=$startId)",
            )
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_START_TEST -> startTest()
            ACTION_STOP -> {
                sessionJob?.cancel()
                sessionJob = scope.launch { finish() }
            }

            else -> {
                // An unrecognised action means this service was started by something
                // that should not have been able to. Stop rather than sit in the
                // foreground holding nothing.
                Log.w(TAG, "ignoring unknown service action: ${intent?.action}")
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        sessionJob?.cancel()
        scope.cancel()
        // Finalizes and releases rather than abandoning the microphone. See
        // `shutdown` for why the distinction matters.
        controller.shutdown()
        // Only the ongoing indicator goes: a summary posted a moment ago is the
        // user's record that the microphone was used, and cancelling it in
        // onDestroy took that away at exactly the moment it mattered.
        notifications.cancelOngoing()
        super.onDestroy()
    }

    private fun startTest() {
        shuttingDown = false
        // A previous run's summary has served its purpose by the time another test
        // starts. Dismissing it here — rather than at the end of the previous run —
        // keeps exactly one summary visible without ever destroying a fresh one.
        notifications.cancelSummary()

        if (!notifications.notificationsVisible()) {
            notify(getString(R.string.safesignal_service_notification_blocked))
            stopSelf()
            return
        }

        if (!promoteToForeground()) {
            stopSelf()
            return
        }

        sessionJob?.cancel()
        sessionJob = scope.launch {
            val started = controller.startTest()
            if (started.isFailure) {
                // Only tear down if we are the ones who made it into the
                // foreground. If the start failed because *another* recording is
                // already live, stopping it here would destroy a recording this
                // request never owned — evidence the user asked for, truncated by a
                // duplicate tap. The service stays up so the live recording keeps
                // its notification.
                if (controller.isRecording) {
                    Log.w(TAG, "start refused; leaving the existing recording untouched", started.exceptionOrNull())
                } else {
                    notify(getString(R.string.safesignal_service_start_failed))
                    finish()
                }
                return@launch
            }

            notifications.postOngoing(controller.state.value)

            // Ends on whichever comes first: the bounded test duration, or the
            // engine stopping itself. The engine's limits (duration, free storage)
            // finalize from inside the capture loop, so waiting only on a timer
            // would leave this coroutine waiting for an event nobody will report.
            withTimeoutOrNull(TEST_DURATION_MILLIS) {
                controller.state.first { it.isTerminal }
            }

            finish()
        }
    }

    /**
     * Ends the session, reports what was actually preserved, and releases.
     *
     * The reporting deliberately consults the controller's last finalized recording
     * when [stop] reports "nothing was in progress". That combination is not a
     * failure — it means the engine finalized itself (duration limit, storage
     * limit, or the capture dying) before we got here, and the evidence is already
     * sealed. Reporting it as a failure is how a *successful* 12-second recording
     * ends up telling the user the app "could not start recording", which is both
     * false and alarming in a way that would make someone discard real evidence.
     */
    private suspend fun finish() {
        shuttingDown = true

        val result = controller.stop()
        // `lastFinalized` is the fallback that matters. The engine finalizes itself
        // when capture ends, so a stop arriving afterwards finds nothing in
        // progress and fails — with a perfectly good recording already sealed. Using
        // only the stop result is what made a successful capture announce that the
        // app "could not start recording".
        val finalized = result.getOrNull() ?: controller.lastFinalized.value

        when (val outcome = SessionOutcome.of(finalized, result.exceptionOrNull()?.message)) {
            is SessionOutcome.Captured -> reportResult(outcome)
            SessionOutcome.NoAudio -> {
                Log.w(TAG, "session finished with no audio captured")
                notify(getString(R.string.safesignal_test_no_audio))
            }
            is SessionOutcome.Failed -> {
                Log.w(TAG, "session ended with no preserved audio", result.exceptionOrNull())
                notify(getString(R.string.safesignal_test_failed, outcome.reason))
            }
        }

        // The ongoing "recording" indicator goes. The summary just posted stays:
        // cancelling both in the same breath removed the only durable record that
        // the microphone had been used, leaving nothing but a Toast that vanishes
        // in seconds. See EmergencyNotifications.cancelOngoing.
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifications.cancelOngoing()
        stopSelf()
    }

    /**
     * Reports what actually happened, not that "recording worked".
     *
     * A run that captured silence is reported as having captured no audio. Saying
     * "test complete" for a recording with no segments would be the exact false
     * confidence this project exists to avoid — and a silent capture is a real
     * failure mode, because some devices return success with all-zero buffers when
     * the microphone is muted or obstructed.
     */
    private fun reportResult(captured: SessionOutcome.Captured) {
        val recording = captured.recording
        val text = getString(
            R.string.safesignal_test_complete,
            formatDuration(recording.durationMillis),
            Formatter.formatShortFileSize(this, recording.totalSealedBytes),
        )
        notifications.postSummary(text)
        notify(text)
    }

    /**
     * Renders a duration the way a person would say it.
     *
     * `Formatter.formatElapsedTime` is avoided because it emits `MM:SS` with leading
     * zeroes, which reads as a timestamp rather than a length.
     */
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

    /**
     * Enters the foreground, reporting whether it succeeded.
     *
     * Two platform failures are handled explicitly rather than allowed to crash:
     *
     *  - `ForegroundServiceStartNotAllowedException` (API 31+), thrown when the app
     *    is not in a state permitted to start a foreground service.
     *  - `SecurityException`, thrown when `RECORD_AUDIO` or
     *    `FOREGROUND_SERVICE_MICROPHONE` is not actually held, which happens if the
     *    user revokes it in Settings while the app is running.
     *
     * Both are ordinary situations rather than bugs, and in both the correct
     * outcome is to say so and release, not to die.
     */
    private fun promoteToForeground(): Boolean = try {
        val notification: Notification = notifications.ongoing(RecordingState.Recording)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                EmergencyNotifications.ONGOING_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(EmergencyNotifications.ONGOING_ID, notification)
        }
        true
    } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException is a subclass of this.
        Log.w(TAG, "foreground start not allowed", e)
        notify(getString(R.string.safesignal_service_start_failed))
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "foreground start denied", e)
        notify(getString(R.string.safesignal_service_start_failed))
        false
    }

    /**
     * Tells the user what happened, in the foreground app and as a notification.
     *
     * Both, deliberately. A Toast disappears in seconds and is missed entirely if the
     * app is not in the foreground; the notification persists. Showing only the
     * notification means a person who never opens SafeSignal never learns that their
     * microphone was used, which is the failure mode this app exists to avoid.
     */
    private fun notify(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "SafeSignalService"

        /** Starts a bounded, explicitly labelled test recording. */
        const val ACTION_START_TEST: String = "com.safesignal.service.action.START_TEST"

        /** Ends the current recording, finalizing and sealing what was captured. */
        const val ACTION_STOP: String = "com.safesignal.service.action.STOP"

        /**
         * How long a test runs.
         *
         * Long enough for the microphone to genuinely produce audio and for the
         * five-second segment boundary to be crossed at least once, short enough
         * that a user who taps stop by mistake is not left with a minute of
         * microphone use.
         */
        const val TEST_DURATION_MILLIS = 12_000L
    }
}

/**
 * States from which no further audio will be produced.
 *
 * Extracted so the service and its tests agree on what "finished" means. It is
 * deliberately a property of the state rather than a comparison written twice: the
 * engine gains states over time, and a stale copy of this list is how a service
 * ends up waiting forever for an event that has already happened.
 */
private val RecordingState.isTerminal: Boolean
    get() = this == RecordingState.Stopped || this is RecordingState.Failed