package com.safesignal.core.common.permission

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exhaustive coverage of the four inputs [PermissionDecision] accepts.
 *
 * The failure this guards against is specific and user-visible: reporting a
 * permission as permanently blocked when the user has never been asked sends
 * them to Android Settings for a permission the app has not requested, and
 * reads as a broken install.
 */
class PermissionDecisionTest {

    @Test
    fun `granted wins over every other signal`() {
        assertEquals(
            PermissionStatus.Granted,
            PermissionDecision.resolve(granted = true, shouldShowRationale = false, hasAskedBefore = false),
        )
        assertEquals(
            "granted stays granted even when the system says show no rationale",
            PermissionStatus.Granted,
            PermissionDecision.resolve(granted = true, shouldShowRationale = false, hasAskedBefore = true),
        )
        assertEquals(
            PermissionStatus.Granted,
            PermissionDecision.resolve(granted = true, shouldShowRationale = true, hasAskedBefore = true),
        )
    }

    @Test
    fun `never asked is Denied, not PermanentlyDenied`() {
        // shouldShowRequestPermissionRationale returns false before the first
        // request, so without hasAskedBefore this is indistinguishable from a
        // blocked permission. It must not be reported as blocked.
        assertEquals(
            PermissionStatus.Denied,
            PermissionDecision.resolve(granted = false, shouldShowRationale = false, hasAskedBefore = false),
        )
    }

    @Test
    fun `asked once and the system will still ask is Denied`() {
        assertEquals(
            PermissionStatus.Denied,
            PermissionDecision.resolve(granted = false, shouldShowRationale = true, hasAskedBefore = true),
        )
        assertEquals(
            PermissionStatus.Denied,
            PermissionDecision.resolve(granted = false, shouldShowRationale = true, hasAskedBefore = false),
        )
    }

    @Test
    fun `asked before and the system refuses to ask again is PermanentlyDenied`() {
        // The exact combination Android produces after "don't ask again", or
        // after a policy block.
        assertEquals(
            PermissionStatus.PermanentlyDenied,
            PermissionDecision.resolve(granted = false, shouldShowRationale = false, hasAskedBefore = true),
        )
    }

    @Test
    fun `an unknown rationale signal is treated as askable`() {
        // Callers with no Activity pass shouldShowRationale = true precisely so
        // that "I cannot tell" never becomes "this is blocked".
        assertEquals(
            PermissionStatus.Denied,
            PermissionDecision.resolve(granted = false, shouldShowRationale = true, hasAskedBefore = true),
        )
    }

    @Test
    fun `a revocation after granting is Denied, not PermanentlyDenied`() {
        // Regression guard for the stale-history bug: the request log must be
        // cleared once the permission is granted, or revoking it in Settings
        // would immediately look like a block.
        val log = InMemoryPermissionRequestLog()
        assertEquals(false, log.hasAsked(SafeSignalPermission.RECORD_AUDIO))

        log.markAsked(SafeSignalPermission.RECORD_AUDIO)
        assertEquals(true, log.hasAsked(SafeSignalPermission.RECORD_AUDIO))

        // Granted, then the app forgets the history.
        assertEquals(
            PermissionStatus.Granted,
            PermissionDecision.resolve(true, shouldShowRationale = false, hasAskedBefore = log.hasAsked(SafeSignalPermission.RECORD_AUDIO)),
        )
        log.forget(SafeSignalPermission.RECORD_AUDIO)

        // Revoked by the user: still askable, because we did not ask again yet.
        assertEquals(
            PermissionStatus.Denied,
            PermissionDecision.resolve(false, shouldShowRationale = false, hasAskedBefore = log.hasAsked(SafeSignalPermission.RECORD_AUDIO)),
        )
    }

    @Test
    fun `the request log is per permission`() {
        val log = InMemoryPermissionRequestLog()
        log.markAsked(SafeSignalPermission.RECORD_AUDIO)

        assertEquals(true, log.hasAsked(SafeSignalPermission.RECORD_AUDIO))
        assertEquals(
            "requesting the microphone must not mark notifications as asked",
            false,
            log.hasAsked(SafeSignalPermission.POST_NOTIFICATIONS),
        )
    }

    @Test
    fun `only the microphone is requested at onboarding`() {
        // SPEC §40: the microphone is the only permission requested before any
        // feature is enabled. Notifications are requested when listening is armed.
        assertEquals(setOf(SafeSignalPermission.RECORD_AUDIO), SafeSignalPermission.REQUESTED_AT_ONBOARDING)
        assertEquals(setOf(SafeSignalPermission.POST_NOTIFICATIONS), SafeSignalPermission.REQUESTED_WHEN_ARMING)
    }

    @Test
    fun `the permission set is exhaustive and contains nothing forbidden`() {
        // SPEC §95: contacts, SMS, location, camera, accessibility and device
        // administrator are permanently out. This test fails the moment one is
        // added, which is the point.
        val forbidden = listOf(
            "CONTACTS", "READ_SMS", "SEND_SMS", "READ_CALL_LOG",
            "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION",
            "CAMERA", "BIND_ACCESSIBILITY_SERVICE", "BIND_DEVICE_ADMIN",
        )
        val declared = SafeSignalPermission.entries.map { it.name }
        forbidden.forEach {
            assertEquals("forbidden permission $it must never be declared", false, declared.contains(it))
        }
        assertEquals(
            "the enum is the single source of truth for requestable permissions",
            declared.toSet(),
            SafeSignalPermission.ALL.map { it.name }.toSet(),
        )
    }
}
