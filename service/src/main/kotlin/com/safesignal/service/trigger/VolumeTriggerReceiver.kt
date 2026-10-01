package com.safesignal.service.trigger

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
// Package locations are not what they look like. In androidx.media 1.7.0 the
// session classes are still shipped under the legacy `android.support.v4.media`
// package, while MediaButtonReceiver has moved to `androidx.media.session`. The
// obvious imports — androidx.media.session.MediaSessionCompat — do not exist.
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.session.MediaButtonReceiver
import com.safesignal.core.common.log.SafeLogger
import com.safesignal.core.common.trigger.PatternProgress
import com.safesignal.core.common.trigger.VolumePattern
import com.safesignal.core.common.trigger.VolumePatternMatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Receives volume-button presses while the app is in the background.
 *
 * ### Why a media session is involved at all
 *
 * `Activity.onVolumeKeyPress` only fires while the activity is in the foreground.
 * An armed app spends essentially all of its time backgrounded — that is the entire
 * use case — so that hook is useless here.
 *
 * The only supported way to receive media volume keys in the background is to be
 * the **media volume owner**: an active [MediaSessionCompat]. The system then
 * routes `KEYCODE_VOLUME_UP` / `KEYCODE_VOLUME_DOWN` here instead of to whatever
 * was playing. This is how apps that need a physical trigger work at all.
 *
 * ### What the user gives up, and it is not small
 *
 * While armed, SafeSignal owns media volume:
 *
 *  * **The volume buttons stop changing music volume.** Genuinely disruptive.
 *  * **A media notification appears.** This is consistent with the project's
 *    never-covert rule — the app is visibly present while armed — but it does mean
 *    the app cannot be fully discreet. That trade is stated in the UI rather than
 *    hidden.
 *
 * ### Why not observe the volume level instead
 *
 * `AudioManager.registerStreamVolumeChangeListener` would avoid hijacking media
 * volume, and it was the first thing tried. It is worse in a way that matters: it
 * reports the level *changing*, so pressing volume-up while already at maximum
 * produces no event at all. A trigger that silently fails at max volume is a trap,
 * and max volume is a common state.
 *
 * ### Failure handling
 *
 * A session that cannot be activated is reported as a failure, not swallowed. The
 * alternative is an app that appears armed and whose volume trigger can never fire.
 */
@Singleton
class VolumeTriggerReceiver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: SafeLogger,
) {

    private var session: MediaSessionCompat? = null
    private var matcher: VolumePatternMatcher? = null
    private var onMatch: (suspend (VolumePattern) -> Unit)? = null

    init {
        // A BroadcastReceiver is created by the framework and cannot take constructor
        // dependencies, so it cannot hold this object, which is the one that knows the
        // user's pattern. Registering here — rather than from a Hilt provider — means
        // the wiring happens exactly when the singleton is constructed, and exactly
        // once, with no marker type existing purely to carry a side effect.
        VolumeKeyDispatcher.install(this)
    }

    val isActive: Boolean get() = session?.isActive == true

    /**
     * The playback state that makes this session the media volume owner.
     *
     * A `STATE_PLAYING` state is what tells the system to route media volume keys
     * here. Without it the keys never arrive and the trigger silently does nothing,
     * which is the failure mode this class exists to avoid.
     *
     * **No transport actions are advertised.** There is no `ACTION_NONE` constant,
     * and lint's `WrongConstant` on a bare `0L` is correct: the field is documented
     * as a bitmask of `ACTION_*` values. Advertising `ACTION_PLAY_PAUSE` — the first
     * attempt — was worse than useless, because it puts a play/pause button in the
     * system media UI for a session that plays no audio, and pressing it does
     * nothing. The bitmask is left at zero, and the suppression is scoped to this
     * function with the reason recorded here rather than in a bare annotation.
     */
    @Suppress("WrongConstant")
    private fun volumeOwnerPlaybackState(): PlaybackStateCompat =
        PlaybackStateCompat.Builder()
            .setActions(0L)
            .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1f)
            .build()

    /**
     * Becomes the media volume owner and begins matching [pattern].
     *
     * @param onMatch invoked on a complete pattern. Suspending so the caller can
     *   start a recording without blocking the key-event thread for long.
     */
    fun arm(pattern: VolumePattern, onMatch: suspend (VolumePattern) -> Unit): Result<Unit> {
        release()
        return runCatching {
            val created = MediaSessionCompat(context, MEDIA_SESSION_TAG).apply {
                setSessionActivity(mediaButtonPendingIntent())
                // A PLAYING playback state is what makes the system treat this
                // session as the media volume owner. Without it the keys never arrive
                // here and the trigger silently does nothing, which is the failure
                // mode this whole class exists to avoid.
                setPlaybackState(volumeOwnerPlaybackState())
            }
            // Activated *after* the playback state is set. Order matters: a session
            // activated with no playback state is not yet treated as the media
            // volume owner, and the volume keys would go somewhere else.
            created.setActive(true)
            session = created
            matcher = VolumePatternMatcher(pattern)
            @Suppress("UNCHECKED_CAST")
            this.onMatch = onMatch
            Log.i(TAG, "volume trigger armed; SafeSignal now owns media volume")
            Result.success(Unit)
        }.getOrElse { failure ->
            release()
            logger.w("could not become the media volume owner", failure)
            Result.failure(failure)
        }
    }

    /**
     * Feeds one key event in.
     *
     * Called from the broadcast receiver and, when the app happens to be in the
     * foreground, from the activity. Having both means a pattern behaves the same
     * whichever screen the user is on.
     */
    fun onKeyEvent(keyCode: Int, atElapsedRealtimeMillis: Long = SystemClock.elapsedRealtime()) {
        val step = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> VolumePattern.Step.UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> VolumePattern.Step.DOWN
            else -> return
        }
        val activeMatcher = matcher ?: return
        when (val progress = activeMatcher.onPress(step, atElapsedRealtimeMillis)) {
            is PatternProgress.Matched -> {
                Log.w(TAG, "volume pattern matched; requesting a recording")
                onMatch?.let { callback ->
                    // Fire and forget: the receiver must return promptly, and the
                    // controller owns the actual state transition.
                    CoroutineScopeHolder.scope.launch { callback(progress.pattern) }
                }
            }
            // Deliberately not logged per press. Volume buttons are pressed
            // constantly, and a log line per press would be useless noise that also
            // risks holding a recording-adjacent log open on a hot path.
            is PatternProgress.Partial, PatternProgress.Idle -> Unit
            is PatternProgress.Misfired -> Unit
        }
    }

    fun release() {
        session?.run {
            setActive(false)
            release()
        }
        session = null
        matcher = null
        onMatch = null
    }

    /**
     * The PendingIntent the system sends media-button events to.
     *
     * `buildMediaButtonPendingIntent` has two overloads: one taking a `ComponentName`
     * and one taking a `long` request code. The one used here is the `long` one.
     *
     * That `long` is annotated in the platform as a `PlaybackStateCompat.Actions`
     * constant, which it is not — it is a plain request code, and lint's
     * `WrongConstant` is a true report of a bad annotation in the platform API rather
     * than a mistake here. The suppression is scoped to this call with that recorded,
     * because the alternative is to invent an actions value and pass it where a
     * request code belongs.
     */
    @Suppress("WrongConstant")
    private fun mediaButtonPendingIntent() = MediaButtonReceiver.buildMediaButtonPendingIntent(
        context,
        REQUEST_MEDIA_BUTTON.toLong(),
    )

    companion object {
        private const val TAG = "SafeSignalTrigger"
        private const val MEDIA_SESSION_TAG = "safesignal.volume.trigger"
        private const val REQUEST_MEDIA_BUTTON = 10
    }
}

/** Receives media-button broadcasts so the session's keys reach the app. */
class VolumeKeyReceiver : MediaButtonReceiver() {
    override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
        super.onReceive(context, intent)
        // The extra key is `Intent.EXTRA_KEY_EVENT`, not a MediaSession constant —
        // there is no ACTION_MEDIA_BUTTON on MediaSessionCompat.
        @Suppress("DEPRECATION")
        val event = intent.getParcelableExtra<KeyEvent>(android.content.Intent.EXTRA_KEY_EVENT)
        event?.let { VolumeKeyDispatcher.deliver(it) }
    }
}

/**
 * Hands a key event to the injected receiver.
 *
 * A `BroadcastReceiver` is constructed by the framework and cannot take constructor
 * dependencies, so this is the seam. It is deliberately tiny and holds a single
 * volatile reference: getting the event from the receiver into the object that has
 * the configured pattern is the whole problem, and everything else about the
 * trigger is pure logic in [VolumePatternMatcher].
 */
object VolumeKeyDispatcher {
    @Volatile
    private var handler: VolumeTriggerReceiver? = null

    fun install(receiver: VolumeTriggerReceiver) {
        handler = receiver
    }

    fun deliver(event: KeyEvent) {
        handler?.onKeyEvent(event.keyCode, event.eventTime.coerceAtLeast(0L))
    }

    fun clear() {
        handler = null
    }
}

/** A process-scoped scope for the pattern callback, so it survives a key event. */
internal object CoroutineScopeHolder {
    val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
    )
}
