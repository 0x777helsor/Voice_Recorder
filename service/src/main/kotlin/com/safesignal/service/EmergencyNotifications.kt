package com.safesignal.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.safesignal.audio.capture.RecordingState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The ongoing notification that must accompany any microphone foreground service.
 *
 * ### Why this is not decoration
 *
 * On API 33+ the platform will happily run a microphone foreground service even
 * when `POST_NOTIFICATIONS` is denied — the notification is simply suppressed.
 * That would give SafeSignal the ability to record with no visible indicator,
 * which is exactly the covert-capture behaviour the project refuses to build, and
 * it would also be undetectable to the person being recorded.
 *
 * So [notificationsVisible] is checked before the microphone is opened and the
 * service refuses to record without it. Losing a recording is bad. Recording
 * someone without their being able to see that it is happening is worse, and it is
 * the thing that would make this app dangerous rather than protective.
 */
@Singleton
class EmergencyNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Whether a notification can actually be seen by the user.
     *
     * True is required before the microphone is opened. Two things must both hold,
     * and neither implies the other:
     *
     *  - the app-wide notification setting, which the user can turn off in Settings
     *    without revoking any permission;
     *  - the `POST_NOTIFICATIONS` runtime permission, which exists only from API 33.
     *
     * Checking the setting alone is the bug Android 13 made easy: a user who denied
     * `POST_NOTIFICATIONS` still reads as "enabled", so readiness showed a green
     * tick on a device where posting is impossible. That is a false assurance about
     * the one indicator this app must never get wrong.
     */
    fun notificationsVisible(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() && mayPost()

    /**
     * Whether `POST_NOTIFICATIONS` is held.
     *
     * Kept separate because lint's `MissingPermission` check does not follow the
     * guard through a helper, and unsatisfying it here is a crash rather than a
     * cosmetic problem.
     */
    private fun mayPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * Creates the notification channels.
     *
     * Must run before `startForeground`, or the platform throws: posting to a
     * channel that does not exist is not a silent no-op. Idempotent, so it is
     * safe to call from both application startup and the service itself.
     */
    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val recording = NotificationChannel(
            CHANNEL_RECORDING,
            context.getString(R.string.safesignal_notification_channel_recording),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.safesignal_notification_channel_recording_description)
            setShowBadge(false)
            // Silent by design. An ongoing indicator that buzzes every time it
            // updates teaches the user to disable notifications, and this is the
            // one notification that must stay visible.
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val listening = NotificationChannel(
            CHANNEL_LISTENING,
            context.getString(R.string.safesignal_notification_channel_listening),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.safesignal_notification_channel_listening_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        manager.createNotificationChannel(recording)
        manager.createNotificationChannel(listening)
    }

    /** The ongoing notification shown for as long as the microphone is open. */
    fun ongoing(state: RecordingState): Notification {
        val text = context.getString(R.string.safesignal_notification_recording_text)
        return NotificationCompat.Builder(context, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_stat_safesignal)
            .setContentTitle(context.getString(R.string.safesignal_notification_recording_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openAppIntent())
            .addAction(
                R.drawable.ic_stat_safesignal,
                context.getString(R.string.safesignal_notification_action_stop),
                stopIntent(),
            )
            .build()
    }

    /** Reposts the ongoing notification, e.g. after a state change. */
    fun postOngoing(state: RecordingState) {
        // The permission check is written out inline rather than delegated to
        // [mayPost]: lint's MissingPermission analysis only recognises a guard it can
        // see on the path to the call, and `notify` throws SecurityException rather
        // than degrading if the guard is wrong.
        if (!mayPost()) return
        @Suppress("MissingPermission") // Guarded immediately above.
        NotificationManagerCompat.from(context).notify(ONGOING_ID, ongoing(state))
    }

    /**
     * A one-shot summary of what a test recording actually produced.
     *
     * Carries the real segment count and size rather than "recording works", so
     * that a run which captured silence is visibly different from one that did
     * not.
     */
    fun postSummary(text: String) {
        if (!mayPost()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_stat_safesignal)
            .setContentTitle(context.getString(R.string.safesignal_notification_summary_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        @Suppress("MissingPermission") // Guarded by mayPost() at the top.
        NotificationManagerCompat.from(context).notify(SUMMARY_ID, notification)
    }

    /**
     * Removes the ongoing "recording" notification, leaving any summary in place.
     *
     * These are separate methods on purpose. `cancelAll` used to cancel both ids,
     * and the service called it immediately after posting the summary — so the
     * summary was destroyed in the same breath it was created, and the persistent
     * record that a microphone had been used lasted about a second. The only thing
     * left telling the user was a Toast, which vanishes on its own and is missed
     * entirely if the app is in the background.
     *
     * Cancelling the summary is still correct when a *new* session starts: the old
     * summary has been seen by then, and leaving several to accumulate would be
     * noise. That is what [cancelSummary] is for.
     */
    fun cancelOngoing() {
        NotificationManagerCompat.from(context).cancel(ONGOING_ID)
    }

    /** Dismisses a previous session's summary. */
    fun cancelSummary() {
        NotificationManagerCompat.from(context).cancel(SUMMARY_ID)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN_APP,
        context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        context,
        REQUEST_STOP,
        Intent(context, EmergencyAudioService::class.java).setAction(EmergencyAudioService.ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_RECORDING = "safesignal.recording.v1"
        const val CHANNEL_LISTENING = "safesignal.listening.v1"

        const val ONGOING_ID = 1001
        const val SUMMARY_ID = 1002

        private const val REQUEST_OPEN_APP = 1
        private const val REQUEST_STOP = 2
    }
}