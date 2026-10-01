package com.safesignal

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.safesignal.core.common.permission.AndroidPermissionChecker
import com.safesignal.core.common.permission.PermissionStatus
import com.safesignal.core.common.permission.SafeSignalPermission
import com.safesignal.core.common.readiness.Capability
import com.safesignal.core.common.readiness.ProbeOutcome
import com.safesignal.core.common.readiness.ReadinessReport
import com.safesignal.core.common.readiness.TestStartAction
import com.safesignal.core.common.readiness.microphoneOutcome
import com.safesignal.core.common.readiness.notificationOutcome
import com.safesignal.core.common.readiness.testStartAction
import com.safesignal.di.ReadinessProbes
import com.safesignal.service.EmergencyAudioService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The single activity.
 *
 * `singleTask` so that returning from the notification stop action, or from
 * Android settings to re-grant a permission, returns to the existing session
 * rather than stacking a second copy of the UI.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var permissionChecker: AndroidPermissionChecker

    @Inject
    lateinit var probes: ReadinessProbes

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ReadinessRoute(
                        permissionChecker = permissionChecker,
                        probes = probes,
                        activity = this,
                    )
                }
            }
        }
    }
}

/**
 * Wires the readiness screen to real permission state and real probes.
 *
 * Split from [MainActivity] so the composable takes its dependencies as
 * parameters and can be reasoned about without a running activity.
 */
@Composable
private fun ReadinessRoute(
    permissionChecker: AndroidPermissionChecker,
    probes: ReadinessProbes,
    activity: ComponentActivity,
) {
    val context = LocalContext.current

    // Re-probe on every resume, not only at startup.
    //
    // A user can grant the microphone from Android Settings while this app sits
    // in the background, and the platform never tells the app about it. Without
    // this, the screen kept claiming "not granted" after the permission had been
    // granted — which is exactly what a real device showed before this existed.
    var resumeCount by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeCount++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val microphoneStatus = permissionChecker.status(SafeSignalPermission.RECORD_AUDIO, activity)
    val notificationStatus = permissionChecker.status(SafeSignalPermission.POST_NOTIFICATIONS, activity)

    val requestPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // A grant made in Settings must not leave stale request history behind,
        // or the next revocation would be reported as permanently blocked.
        permissionChecker.forgetRequestsNowGranted(SafeSignalPermission.ALL)
    }

    // Whether a notification can actually be *seen*, which is what the recording
    // service requires. Re-evaluated on resume like everything else: the user can
    // disable notifications from the shade without leaving the app.
    val notificationsVisible = remember(resumeCount, context) {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    val report = remember(resumeCount, microphoneStatus, notificationStatus, notificationsVisible) {
        ReadinessReport.build(
            listOf(
                Capability.MicrophonePermission to { microphoneOutcome(microphoneStatus) },
                Capability.NotificationPermission to {
                    notificationOutcome(
                        permissionGranted = notificationStatus == PermissionStatus.Granted,
                        notificationsEnabled = notificationsVisible,
                    )
                },
                Capability.Encryption to { probes.encryption() },
                Capability.Storage to { probes.storage() },
                Capability.ForegroundService to {
                    probes.foregroundService(context.hasEmergencyAudioService())
                },
                Capability.WakeWord to { probes.wakeWord(detectorAvailable = false) },
            )
        )
    }

    ReadinessScreen(
        report = report,
        microphoneStatus = microphoneStatus,
        notificationsVisible = notificationsVisible,
        action = testStartAction(
            microphone = microphoneStatus,
            notification = notificationStatus,
            notificationsVisible = notificationsVisible,
            isNotificationPermissionRequestable = Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.TIRAMISU,
        ),
        onRequestMicrophone = {
            // Recorded before the dialog is shown. `shouldShowRequestPermission-
            // Rationale` is false both before the first request and after a block,
            // so this flag is the only thing that can tell those two cases apart.
            permissionChecker.recordRequest(SafeSignalPermission.REQUESTED_AT_ONBOARDING)
            requestPermissions.launch(
                SafeSignalPermission.REQUESTED_AT_ONBOARDING.map { it.manifestPermission }.toTypedArray()
            )
        },
        onRequestNotifications = {
            permissionChecker.recordRequest(setOf(SafeSignalPermission.POST_NOTIFICATIONS))
            requestPermissions.launch(
                arrayOf(SafeSignalPermission.POST_NOTIFICATIONS.manifestPermission),
            )
        },
        onOpenAppSettings = { context.openAppSettings() },
        onRunTest = { context.startTestRecording() },
    )
}

/**
 * Starts a bounded, labelled test recording.
 *
 * ### Why `startForegroundService` and not `startService`
 *
 * A microphone foreground service must be started as one, and must post its
 * notification within a short window or the platform kills the process. Starting
 * it as a plain background service would be rejected, and on API 26+ the plain
 * variant is forbidden outright for this kind of work.
 *
 * ### Failures are reported, not swallowed
 *
 * `ForegroundServiceStartNotAllowedException` (API 31+) means the app was not in
 * a state permitted to start a foreground service — backgrounded between the tap
 * and this call, or blocked by the platform. That is a real possibility from a UI
 * button, so it is caught and surfaced rather than allowed to crash the app.
 */
private fun Context.startTestRecording() {
    try {
        startForegroundService(
            Intent(this, EmergencyAudioService::class.java)
                .setAction(EmergencyAudioService.ACTION_START_TEST),
        )
    } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException is a subclass of this, and
        // was added in API 31.
        Log.w("SafeSignal", "could not start the recording service", e)
        Toast.makeText(this, R.string.test_start_blocked, Toast.LENGTH_LONG).show()
    } catch (e: SecurityException) {
        // Missing FOREGROUND_SERVICE_MICROPHONE, or RECORD_AUDIO revoked between
        // the readiness check and this call.
        Log.w("SafeSignal", "recording service start denied", e)
        Toast.makeText(this, R.string.test_start_blocked, Toast.LENGTH_LONG).show()
    }
}

/**
 * The readiness screen (SPEC §32).
 *
 * Deliberately the first screen after onboarding, and deliberately boring. Its
 * job is to tell the truth about what will and will not work, and — critically —
 * to never present a cloud problem as a recording problem.
 *
 * Every row is driven by [report]. There is no hardcoded `ready = true`: a
 * capability no probe checked is reported as not ready, because a tick the user
 * cannot trust is worse than a warning they can act on.
 */
@Composable
fun ReadinessScreen(
    report: ReadinessReport,
    microphoneStatus: PermissionStatus,
    notificationsVisible: Boolean = true,
    action: TestStartAction = TestStartAction.StartTest,
    onRequestMicrophone: () -> Unit = {},
    onRequestNotifications: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
    onRunTest: () -> Unit = {},
) {
    val blocked = microphoneStatus == PermissionStatus.PermanentlyDenied

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.readiness_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )

        Text(
            text = stringResource(R.string.onboarding_body),
            style = MaterialTheme.typography.bodyMedium,
        )

        CapabilityRow(
            label = stringResource(R.string.readiness_microphone_permission),
            status = report.outcomeOf(Capability.MicrophonePermission),
        )
        CapabilityRow(
            label = stringResource(R.string.readiness_notification),
            status = report.outcomeOf(Capability.NotificationPermission),
        )
        CapabilityRow(
            label = stringResource(R.string.readiness_encryption),
            status = report.outcomeOf(Capability.Encryption),
        )
        CapabilityRow(
            label = stringResource(R.string.readiness_storage),
            status = report.outcomeOf(Capability.Storage),
        )
        CapabilityRow(
            label = stringResource(R.string.readiness_foreground_service),
            status = report.outcomeOf(Capability.ForegroundService),
        )
        CapabilityRow(
            label = stringResource(R.string.readiness_wake_word),
            status = report.outcomeOf(Capability.WakeWord),
        )

        if (blocked) {
            Text(
                text = stringResource(R.string.readiness_permission_blocked),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onOpenAppSettings) {
                Text(stringResource(R.string.readiness_open_settings))
            }
        }

        // Said up front rather than only on failure, because the alternative is a
        // user who taps "test", watches nothing happen, and concludes the app is
        // broken — when in fact it is refusing to record without a visible
        // indicator. That is a deliberate refusal, not a defect, and it should be
        // explained before the tap rather than after.
        if (!notificationsVisible) {
            Text(
                text = stringResource(R.string.readiness_notification_required),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Text(
            text = stringResource(R.string.readiness_cloud_unavailable),
            style = MaterialTheme.typography.bodyMedium,
        )

        Text(
            text = stringResource(R.string.privacy_microphone_limits),
            style = MaterialTheme.typography.bodySmall,
        )

        // The label states what the tap will actually do, rather than always promising a
        // recording. Offering "Run SafeSignal test" when the only possible result is
        // another permission dialog is how a screen stops being read.
        val buttonLabel = when (action) {
            TestStartAction.RequestMicrophone -> R.string.onboarding_grant_microphone
            TestStartAction.RequestNotifications -> R.string.onboarding_grant_notifications
            TestStartAction.OpenSettings -> R.string.readiness_open_settings
            TestStartAction.StartTest -> R.string.test_run
        }

        Button(
            onClick = when (action) {
                TestStartAction.RequestMicrophone -> onRequestMicrophone
                TestStartAction.RequestNotifications -> onRequestNotifications
                TestStartAction.OpenSettings -> onOpenAppSettings
                TestStartAction.StartTest -> onRunTest
            },
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(stringResource(buttonLabel))
        }

        Text(
            text = stringResource(R.string.legal_recording_law),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * One readiness row.
 *
 * Only the blocked-permission case gets user-facing explanatory text, because
 * that is the one where knowing the reason changes what the user should do. The
 * other reasons are developer-facing and go to logs; an exception class name is
 * not an explanation anyone can act on.
 */
@Composable
private fun CapabilityRow(label: String, status: ProbeOutcome) {
    Text(
        text = (if (status == ProbeOutcome.Ready) "✓ " else "⚠ ") + label,
        style = MaterialTheme.typography.bodyLarge,
    )
}

/** Permission set the onboarding flow is allowed to request (SPEC §40). */
internal val ONBOARDING_PERMISSIONS: Set<SafeSignalPermission> =
    SafeSignalPermission.REQUESTED_AT_ONBOARDING

/**
 * Whether a microphone foreground service is actually declared.
 *
 * Read from the installed package rather than assumed, so this row flips to ready
 * on its own when `:service` lands, and a manifest edit that removed the service
 * would be detected instead of being reported as healthy.
 */
private fun Context.hasEmergencyAudioService(): Boolean = try {
    packageManager
        .getPackageInfo(packageName, PackageManager.GET_SERVICES)
        .services
        ?.any { it.name == EMERGENCY_AUDIO_SERVICE }
        ?: false
} catch (t: Throwable) {
    false
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )
    )
}

/** Fully qualified so a future package move cannot silently stop matching. */
private const val EMERGENCY_AUDIO_SERVICE = "com.safesignal.service.EmergencyAudioService"
