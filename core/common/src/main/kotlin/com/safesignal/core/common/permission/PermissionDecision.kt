package com.safesignal.core.common.permission

/**
 * Decides what SafeSignal may honestly claim about a permission.
 *
 * ### Why this is not a one-liner
 *
 * Android gives two signals, and neither alone is sufficient:
 *
 *  * `checkSelfPermission` says granted or not.
 *  * `shouldShowRequestPermissionRationale` says whether asking again is allowed.
 *
 * The trap is that `shouldShowRequestPermissionRationale` returns **false before
 * the user has ever been asked**, exactly as it does after "don't ask again".
 * Reading that flag alone tells a first-run user to go and open Settings for a
 * permission the app has not yet requested — which reads as broken, and teaches
 * the user not to trust the screen.
 *
 * The third input, [hasAskedBefore], disambiguates it. It is tracked by the app
 * rather than inferred, because the platform deliberately refuses to distinguish
 * "never asked" from "blocked".
 *
 * ### Why pure
 *
 * This logic is the part that decides whether the app tells a user it is ready.
 * Getting it wrong produces either a false green tick or a dead end the user
 * cannot escape, and neither failure is visible in a crash log. So it is a plain
 * function with no Android types, tested exhaustively by
 * `PermissionDecisionTest`.
 */
object PermissionDecision {

    /**
     * @param granted result of `checkSelfPermission`.
     * @param shouldShowRationale result of `shouldShowRequestPermissionRationale`.
     *   Pass `true` when it is unknown — for instance when no Activity is
     *   available to ask — because wrongly reporting [PermissionStatus.PermanentlyDenied]
     *   sends a user to Settings who could simply have been asked.
     * @param hasAskedBefore whether this app has already requested the
     *   permission at least once in this install.
     */
    fun resolve(
        granted: Boolean,
        shouldShowRationale: Boolean,
        hasAskedBefore: Boolean,
    ): PermissionStatus = when {
        granted -> PermissionStatus.Granted
        shouldShowRationale -> PermissionStatus.Denied
        hasAskedBefore -> PermissionStatus.PermanentlyDenied
        else -> PermissionStatus.Denied
    }
}

/**
 * Remembers which permissions have already been requested in this install.
 *
 * Separated from the checker so that the decision logic has no storage
 * dependency, and so a test can supply a fixed history.
 */
interface PermissionRequestLog {
    fun hasAsked(permission: SafeSignalPermission): Boolean

    /** Records that a request was issued. Must be called when the dialog is shown. */
    fun markAsked(permission: SafeSignalPermission)

    /**
     * Clears the record for [permission].
     *
     * Called when the user grants the permission from Android Settings while the
     * app was not running, so that a later revocation does not immediately
     * report "blocked" for a permission that is currently granted.
     */
    fun forget(permission: SafeSignalPermission)
}

/** In-memory history, for tests and for previews. */
class InMemoryPermissionRequestLog(
    private val asked: MutableSet<SafeSignalPermission> = mutableSetOf(),
) : PermissionRequestLog {
    override fun hasAsked(permission: SafeSignalPermission): Boolean = permission in asked

    override fun markAsked(permission: SafeSignalPermission) {
        asked += permission
    }

    override fun forget(permission: SafeSignalPermission) {
        asked -= permission
    }
}
