package com.safesignal.core.common.permission

/**
 * The complete set of permissions SafeSignal may ever request.
 *
 * SPEC §95 forbids collecting anything the features do not need. Making the
 * set an exhaustive enum means "we added a permission" is a compile-visible
 * event that a reviewer must confront, rather than a string buried in a
 * manifest merge.
 *
 * Anything not in this enum must not be added to AndroidManifest.xml. That
 * includes, permanently: contacts, SMS, location, camera, accessibility and
 * device administrator.
 */
enum class SafeSignalPermission(val manifestPermission: String) {
    RECORD_AUDIO(manifestPermission = "android.permission.RECORD_AUDIO"),
    POST_NOTIFICATIONS(manifestPermission = "android.permission.POST_NOTIFICATIONS"),
    ;

    companion object {
        /**
         * Permissions requested *before* any feature is enabled.
         *
         * Deliberately only [RECORD_AUDIO]: the specification requires contextual
         * requests, and the microphone is the one permission without which the
         * app cannot do anything at all.
         */
        val REQUESTED_AT_ONBOARDING: Set<SafeSignalPermission> = setOf(RECORD_AUDIO)

        /**
         * Permissions requested when the user actually arms Emergency Listening,
         * because the notification is part of the contract that listening is visible.
         */
        val REQUESTED_WHEN_ARMING: Set<SafeSignalPermission> = setOf(POST_NOTIFICATIONS)

        val ALL: Set<SafeSignalPermission> = entries.toSet()
    }
}

/** User-facing reason a permission is needed. Shown verbatim in the rationale dialog. */
enum class PermissionRationale(val userMessage: String) {
    MICROPHONE(
        userMessage = "SafeSignal needs the microphone to listen for your activation phrase and to " +
            "record after activation. Recording only ever happens after you have armed Emergency " +
            "Listening, and Android shows its own microphone indicator while it does.",
    ),
    NOTIFICATIONS(
        userMessage = "SafeSignal shows a permanent notification while Emergency Listening is " +
            "active. The notification is how you — and Android — can tell that the microphone is in " +
            "use, and it carries the stop control.",
    ),
}

/** Result of a permission check, including the "don't ask again" case. */
sealed interface PermissionStatus {
    data object Granted : PermissionStatus
    data object Denied : PermissionStatus
    /** Denied with "don't ask again", or blocked by policy. Only Settings can fix it. */
    data object PermanentlyDenied : PermissionStatus
}