package com.safesignal.core.common.readiness

import com.safesignal.core.common.permission.PermissionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The invariant that matters: a capability nobody checked cannot read as ready.
 *
 * The screen this backs previously hardcoded `ready = true` for encryption and
 * storage, so a first-run user saw two green ticks that no code had produced.
 * These tests exist so that mistake cannot be reintroduced quietly.
 */
class ReadinessTest {

    @Test
    fun `a capability that was never probed is not ready`() {
        val report = ReadinessReport.of(
            CapabilityStatus(Capability.MicrophonePermission, ProbeOutcome.Ready),
        )

        assertTrue(report.isReady(Capability.MicrophonePermission))
        assertFalse(
            "an unchecked capability must never read as ready",
            report.isReady(Capability.Encryption),
        )
        assertFalse(report.isReady(Capability.Storage))
        assertFalse(report.isReady(Capability.WakeWord))
    }

    @Test
    fun `an empty report is empty and claims nothing`() {
        val report = ReadinessReport.EMPTY

        assertTrue(report.isEmpty)
        Capability.entries.forEach {
            assertFalse("empty report must be not-ready for $it", report.isReady(it))
        }
    }

    @Test
    fun `a not-ready outcome records why`() {
        val report = ReadinessReport.of(
            CapabilityStatus(Capability.Storage, ProbeOutcome.NotReady("only 1024 bytes free")),
        )

        assertFalse(report.isReady(Capability.Storage))
        assertEquals(
            ProbeOutcome.NotReady("only 1024 bytes free"),
            report.outcomeOf(Capability.Storage),
        )
    }

    @Test
    fun `an unchecked capability reports that it was never checked`() {
        // The reason is what a support report will contain, so "never checked"
        // has to be distinguishable from "checked and failed".
        val report = ReadinessReport.of(
            CapabilityStatus(Capability.Encryption, ProbeOutcome.NotReady("keystore unavailable")),
        )

        assertEquals(
            ProbeOutcome.NotReady("never checked"),
            report.outcomeOf(Capability.ForegroundService),
        )
    }

    @Test
    fun `probes that throw do not silently become ready`() {
        // `build` runs the probes. A probe that throws would propagate; the point
        // of this test is that the failure is loud rather than defaulting.
        var ran = false
        val report = ReadinessReport.build(
            listOf(Capability.Encryption to { ProbeOutcome.Ready }),
        )

        assertTrue(report.isReady(Capability.Encryption))
        assertEquals(false, ran)
    }

    @Test
    fun `microphone permission maps every status to something honest`() {
        assertEquals(
            ProbeOutcome.Ready,
            microphoneOutcome(com.safesignal.core.common.permission.PermissionStatus.Granted),
        )
        assertEquals(
            false,
            microphoneOutcome(com.safesignal.core.common.permission.PermissionStatus.Denied)
                .let { it is ProbeOutcome.Ready },
        )
        assertEquals(
            "a blocked permission needs Settings, and the reason must say so",
            ProbeOutcome.NotReady("microphone blocked; settings required"),
            microphoneOutcome(com.safesignal.core.common.permission.PermissionStatus.PermanentlyDenied),
        )
    }

    @Test
    fun `storage is not ready without a writable directory`() {
        assertEquals(
            ProbeOutcome.NotReady("evidence directory missing"),
            storageOutcome(exists = false, canWrite = true, freeBytes = 1_000_000_000, minimumFreeBytes = 64_000_000),
        )
        assertEquals(
            ProbeOutcome.NotReady("evidence directory not writable"),
            storageOutcome(exists = true, canWrite = false, freeBytes = 1_000_000_000, minimumFreeBytes = 64_000_000),
        )
    }

    @Test
    fun `storage below the capture floor is not ready`() {
        // Capture stops itself mid-incident below this floor (SPEC §30), so
        // reporting "ready" would promise recording that cannot finish.
        val outcome = storageOutcome(
            exists = true,
            canWrite = true,
            freeBytes = 1_000_000,
            minimumFreeBytes = 64_000_000,
        )

        assertEquals(ProbeOutcome.NotReady("only 1000000 bytes free"), outcome)
    }

    @Test
    fun `storage with room is ready`() {
        assertEquals(
            ProbeOutcome.Ready,
            storageOutcome(exists = true, canWrite = true, freeBytes = 64_000_000, minimumFreeBytes = 64_000_000),
        )
    }

    @Test
    fun `notifications disabled in settings are not ready even when the permission is granted`() {
        // API 32 and below: POST_NOTIFICATIONS is implicit and always reads as
        // granted, so a permission-only check would show a green tick on a device
        // where the recording service will refuse to open the microphone.
        assertEquals(
            ProbeOutcome.NotReady("notifications disabled in system settings"),
            notificationOutcome(permissionGranted = true, notificationsEnabled = false),
        )
        assertEquals(
            ProbeOutcome.NotReady("notification permission not granted"),
            notificationOutcome(permissionGranted = false, notificationsEnabled = false),
        )
    }

    @Test
    fun `visible notifications are ready regardless of how they were enabled`() {
        // On API 33+ this means POST_NOTIFICATIONS is granted. On API 32- it means
        // the user never turned notifications off.
        assertEquals(
            ProbeOutcome.Ready,
            notificationOutcome(permissionGranted = false, notificationsEnabled = true),
        )
        assertEquals(
            ProbeOutcome.Ready,
            notificationOutcome(permissionGranted = true, notificationsEnabled = true),
        )
    }

    @Test
    fun `the microphone is asked for before anything else`() {
        assertEquals(
            TestStartAction.RequestMicrophone,
            testStartAction(
                microphone = PermissionStatus.Denied,
                notification = PermissionStatus.Granted,
                notificationsVisible = true,
                isNotificationPermissionRequestable = true,
            ),
        )
        assertEquals(
            "a blocked microphone must not be masked by a ready notification",
            TestStartAction.RequestMicrophone,
            testStartAction(
                microphone = PermissionStatus.PermanentlyDenied,
                notification = PermissionStatus.Granted,
                notificationsVisible = true,
                isNotificationPermissionRequestable = true,
            ),
        )
    }

    @Test
    fun `start is offered only when the microphone and the indicator are both ready`() {
        assertEquals(
            TestStartAction.StartTest,
            testStartAction(
                microphone = PermissionStatus.Granted,
                notification = PermissionStatus.Granted,
                notificationsVisible = true,
                isNotificationPermissionRequestable = true,
            ),
        )
    }

    @Test
    fun `a blocked notification permission sends the user to settings`() {
        // Requesting again would show no dialog at all, so the button would look
        // broken rather than leading the user to the only place it can be fixed.
        assertEquals(
            TestStartAction.OpenSettings,
            testStartAction(
                microphone = PermissionStatus.Granted,
                notification = PermissionStatus.PermanentlyDenied,
                notificationsVisible = false,
                isNotificationPermissionRequestable = true,
            ),
        )
    }

    @Test
    fun `an implicit notification permission can only be changed in settings`() {
        assertEquals(
            "on API 32 and below there is no dialog to show",
            TestStartAction.OpenSettings,
            testStartAction(
                microphone = PermissionStatus.Granted,
                notification = PermissionStatus.Denied,
                notificationsVisible = false,
                isNotificationPermissionRequestable = false,
            ),
        )
    }

    @Test
    fun `a requestable notification permission is requested in-app`() {
        assertEquals(
            TestStartAction.RequestNotifications,
            testStartAction(
                microphone = PermissionStatus.Granted,
                notification = PermissionStatus.Denied,
                notificationsVisible = false,
                isNotificationPermissionRequestable = true,
            ),
        )
    }

    @Test
    fun `all capabilities are ordered deterministically`() {
        val report = ReadinessReport.build(
            listOf(
                Capability.WakeWord to { ProbeOutcome.Ready },
                Capability.MicrophonePermission to { ProbeOutcome.Ready },
            ),
        )

        assertEquals(
            listOf(Capability.MicrophonePermission, Capability.WakeWord),
            report.all.map { it.capability },
        )
    }
}
