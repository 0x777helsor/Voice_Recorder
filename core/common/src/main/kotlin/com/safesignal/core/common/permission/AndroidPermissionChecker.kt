package com.safesignal.core.common.permission

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager

/**
 * `SharedPreferences`-backed [PermissionRequestLog].
 *
 * Deliberately private, non-backed-up storage. The record is a UI aid — it stops
 * SafeSignal telling a first-run user to open Settings for a permission it never
 * asked for — and losing it on restore from backup costs nothing beyond one
 * possibly-mis-worded prompt.
 */
class SharedPreferencesPermissionRequestLog(context: Context) : PermissionRequestLog {

    private val preferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    override fun hasAsked(permission: SafeSignalPermission): Boolean =
        preferences.getBoolean(permission.name, false)

    override fun markAsked(permission: SafeSignalPermission) {
        preferences.edit().putBoolean(permission.name, true).apply()
    }

    override fun forget(permission: SafeSignalPermission) {
        preferences.edit().remove(permission.name).apply()
    }

    private companion object {
        const val NAME = "safesignal.permission_requests"
    }
}

/**
 * Reads real permission state and turns it into a [PermissionStatus].
 *
 * ### A note on `Activity`
 *
 * The blocked/never-asked distinction needs [Activity.shouldShowRequestPermissionRationale],
 * so callers that have an Activity should pass it. Without one, the checker
 * reports [PermissionStatus.Denied] and never
 * [PermissionStatus.PermanentlyDenied]: "I cannot tell" must not be reported as
 * "this is blocked", because that sends a user to Settings for nothing.
 */
class AndroidPermissionChecker(
    private val context: Context,
    private val requestLog: PermissionRequestLog = SharedPreferencesPermissionRequestLog(context),
) {

    /** Status without the rationale signal. Never reports [PermissionStatus.PermanentlyDenied]. */
    fun status(permission: SafeSignalPermission): PermissionStatus = resolve(permission, activity = null)

    fun status(permission: SafeSignalPermission, activity: Activity?): PermissionStatus =
        resolve(permission, activity)

    private fun resolve(permission: SafeSignalPermission, activity: Activity?): PermissionStatus =
        PermissionDecision.resolve(
            granted = context.isGranted(permission),
            shouldShowRationale = activity?.let {
                it.shouldShowRequestPermissionRationale(permission.manifestPermission)
            } ?: true,
            hasAskedBefore = requestLog.hasAsked(permission),
        )

    /** Records that a request dialog was issued for [permissions]. */
    fun recordRequest(permissions: Set<SafeSignalPermission>) {
        permissions.forEach(requestLog::markAsked)
    }

    /**
     * Forgets the request history for permissions that are now granted.
     *
     * A user who grants from Settings while the app was closed would otherwise
     * leave a stale "asked" flag behind, and the first revocation would be
     * reported as permanently blocked.
     */
    fun forgetRequestsNowGranted(permissions: Set<SafeSignalPermission>) {
        permissions.filter { context.isGranted(it) }.forEach(requestLog::forget)
    }

    private fun Context.isGranted(permission: SafeSignalPermission): Boolean =
        checkSelfPermission(permission.manifestPermission) == PackageManager.PERMISSION_GRANTED
}
