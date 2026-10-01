package com.safesignal.core.common.readiness

import com.safesignal.core.common.permission.PermissionStatus

/**
 * What SafeSignal is capable of right now, verified rather than assumed.
 *
 * ### The rule this type exists to enforce
 *
 * A capability that has not been checked is **not ready**. There is no
 * `Assumed`, no `Unknown`, no default-true. If a probe was never run, the
 * capability is absent from the report and reads as not ready.
 *
 * That is not pedantry. This screen is the one a user consults before relying on
 * the app in an emergency, and the earlier version of it hardcoded two green
 * ticks for capabilities no code had ever verified. A tick the user cannot trust
 * is worse than a warning they can act on.
 */

/** The things the app claims it can do. */
enum class Capability {
    /** Microphone access has been granted. */
    MicrophonePermission,

    /** The ongoing-recording notification may be posted. */
    NotificationPermission,

    /** A device key can be created, and a recording key wrapped and unwrapped by it. */
    Encryption,

    /** Evidence can be written, and there is room to write it. */
    Storage,

    /** A foreground service exists to hold the microphone. */
    ForegroundService,

    /** A wake word can actually be detected locally. */
    WakeWord,
}

/** The result of checking one [Capability]. */
sealed interface ProbeOutcome {
    data object Ready : ProbeOutcome

    /**
     * Not ready.
     *
     * @property reason developer-facing detail, for logs and tests. Never shown
     *   to a user, because exception text is not an explanation.
     */
    data class NotReady(val reason: String) : ProbeOutcome
}

/** One capability's result. */
data class CapabilityStatus(
    val capability: Capability,
    val outcome: ProbeOutcome,
) {
    val isReady: Boolean get() = outcome == ProbeOutcome.Ready
}

/**
 * An immutable snapshot of everything the app checked.
 *
 * Constructed only from probes that actually ran, so absence means "not
 * verified" rather than "forgot to check".
 */
class ReadinessReport private constructor(
    private val statuses: Map<Capability, CapabilityStatus>,
) {
    val all: List<CapabilityStatus> get() = statuses.values.sortedBy { it.capability.ordinal }

    /** [isReady] is false for a capability that was never probed. */
    fun isReady(capability: Capability): Boolean = statuses[capability]?.isReady == true

    fun outcomeOf(capability: Capability): ProbeOutcome =
        statuses[capability]?.outcome ?: ProbeOutcome.NotReady("never checked")

    /** True when nothing at all was probed — a programming error, surfaced explicitly. */
    val isEmpty: Boolean get() = statuses.isEmpty()

    companion object {
        fun of(vararg statuses: CapabilityStatus): ReadinessReport =
            ReadinessReport(statuses.associateBy { it.capability })

        fun build(probes: List<Pair<Capability, () -> ProbeOutcome>>): ReadinessReport =
            ReadinessReport(
                probes.associate { (capability, probe) ->
                    capability to CapabilityStatus(capability, probe())
                }
            )

        val EMPTY: ReadinessReport = ReadinessReport(emptyMap())
    }
}

/**
 * Turns a raw permission status into a capability outcome.
 *
 * [PermissionStatus.PermanentlyDenied] and [PermissionStatus.Denied] are both
 * "not ready", but they are not the same thing to the user: one can be fixed by
 * tapping a button and the other requires a trip to Android Settings. The
 * distinction is preserved in [reason] so the UI can offer the right route.
 */
fun microphoneOutcome(status: PermissionStatus): ProbeOutcome = when (status) {
    PermissionStatus.Granted -> ProbeOutcome.Ready
    PermissionStatus.Denied -> ProbeOutcome.NotReady("microphone not granted yet")
    PermissionStatus.PermanentlyDenied -> ProbeOutcome.NotReady("microphone blocked; settings required")
}

/**
 * Turns notification state into a capability outcome.
 *
 * Deliberately takes **two** inputs rather than just the permission. The recording
 * service refuses to hold the microphone unless its ongoing notification can
 * actually be seen, and on API 32 and below `POST_NOTIFICATIONS` is an implicit
 * permission that is always reported granted — even when the user has switched
 * notifications off entirely in Settings. Reading the permission alone would show
 * a green tick on a device where recording is impossible.
 *
 * @param notificationsEnabled `NotificationManager.areNotificationsEnabled()`,
 *   which accounts for the user's system setting as well as the permission.
 */
fun notificationOutcome(
    permissionGranted: Boolean,
    notificationsEnabled: Boolean,
): ProbeOutcome = when {
    notificationsEnabled -> ProbeOutcome.Ready
    permissionGranted -> ProbeOutcome.NotReady("notifications disabled in system settings")
    else -> ProbeOutcome.NotReady("notification permission not granted")
}

/** What the primary readiness button should do when tapped. */
enum class TestStartAction {
    /** Ask for the microphone. */
    RequestMicrophone,

    /** Ask for notification permission. */
    RequestNotifications,

    /**
     * Send the user to system settings.
     *
     * Needed when the permission is implicit (API 32 and below) or permanently
     * denied: no in-app dialog can grant it, so pretending otherwise would leave
     * a button that does nothing.
     */
    OpenSettings,

    /** Start the bounded test recording. */
    StartTest,
}

/**
 * Decides the primary button's behaviour.
 *
 * Pure, because this is the fork between "the user's evidence gets recorded" and
 * "the app claims it is ready and nothing happens", and both failure directions
 * are invisible to a crash log.
 *
 * @param isNotificationPermissionRequestable false on API 32 and below, where
 *   `POST_NOTIFICATIONS` is not a runtime permission and can only be changed in
 *   Settings.
 */
fun testStartAction(
    microphone: PermissionStatus,
    notification: PermissionStatus,
    notificationsVisible: Boolean,
    isNotificationPermissionRequestable: Boolean,
): TestStartAction = when {
    microphone != PermissionStatus.Granted -> TestStartAction.RequestMicrophone

    // The microphone is available but the indicator is not. Offering "start"
    // here would reach a service that refuses to record, which reads as a bug.
    notificationsVisible -> TestStartAction.StartTest

    notification == PermissionStatus.PermanentlyDenied -> TestStartAction.OpenSettings

    isNotificationPermissionRequestable -> TestStartAction.RequestNotifications

    else -> TestStartAction.OpenSettings
}

/**
 * Checks that evidence storage is genuinely writable with room to spare.
 *
 * @param minimumFreeBytes the floor. Below it, capture will stop itself
 *   mid-incident (SPEC §30), so "ready" would be misleading.
 */
fun storageOutcome(
    exists: Boolean,
    canWrite: Boolean,
    freeBytes: Long,
    minimumFreeBytes: Long,
): ProbeOutcome = when {
    !exists -> ProbeOutcome.NotReady("evidence directory missing")
    !canWrite -> ProbeOutcome.NotReady("evidence directory not writable")
    freeBytes < minimumFreeBytes -> ProbeOutcome.NotReady("only $freeBytes bytes free")
    else -> ProbeOutcome.Ready
}
