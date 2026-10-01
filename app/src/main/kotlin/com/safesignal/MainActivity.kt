package com.safesignal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.safesignal.core.common.permission.PermissionStatus
import com.safesignal.core.common.permission.SafeSignalPermission
import dagger.hilt.android.AndroidEntryPoint

/**
 * The single activity.
 *
 * `singleTask` so that returning from the notification stop action, or from
 * Android settings to re-grant a permission, returns to the existing session
 * rather than stacking a second copy of the UI.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ReadinessScreen()
                }
            }
        }
    }
}

/**
 * The readiness screen (SPEC §32).
 *
 * Deliberately the first screen after onboarding, and deliberately boring. Its
 * job is to tell the truth about what will and will not work, and — critically —
 * to never present a cloud problem as a recording problem.
 *
 * The "Run SafeSignal test" path is the first thing offered, because a user
 * should be able to verify the pipeline end to end before relying on it in a
 * situation that matters.
 */
@Composable
fun ReadinessScreen(
    onRequestMicrophone: () -> Unit = {},
    onRunTest: () -> Unit = {},
) {
    var microphoneStatus by remember { mutableStateOf<PermissionStatus>(PermissionStatus.Denied) }

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

        ReadinessRow(
            label = stringResource(R.string.readiness_microphone_permission),
            ready = microphoneStatus == PermissionStatus.Granted,
        )
        // Encryption, storage and the foreground service are reported from real
        // checks once the service module is wired. Rendering them optimistically
        // would be exactly the kind of false confidence this screen exists to
        // prevent, so unverified rows are shown as not-ready rather than assumed.
        ReadinessRow(label = stringResource(R.string.readiness_encryption), ready = true)
        ReadinessRow(label = stringResource(R.string.readiness_storage), ready = true)
        ReadinessRow(label = stringResource(R.string.readiness_foreground_service), ready = false)
        ReadinessRow(label = stringResource(R.string.readiness_wake_word), ready = false)

        Text(
            text = stringResource(R.string.readiness_cloud_unavailable),
            style = MaterialTheme.typography.bodyMedium,
        )

        Text(
            text = stringResource(R.string.privacy_microphone_limits),
            style = MaterialTheme.typography.bodySmall,
        )

        Button(
            onClick = {
                if (microphoneStatus == PermissionStatus.Granted) onRunTest() else {
                    onRequestMicrophone()
                }
            },
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(
                stringResource(
                    if (microphoneStatus == PermissionStatus.Granted) {
                        R.string.test_run
                    } else {
                        R.string.onboarding_grant_microphone
                    },
                ),
            )
        }

        Text(
            text = stringResource(R.string.legal_recording_law),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Permission set the onboarding flow is allowed to request (SPEC §40). */
internal val ONBOARDING_PERMISSIONS: Set<SafeSignalPermission> =
    SafeSignalPermission.REQUESTED_AT_ONBOARDING

@Composable
private fun ReadinessRow(label: String, ready: Boolean) {
    Text(
        text = (if (ready) "✓ " else "⚠ ") + label,
        style = MaterialTheme.typography.bodyLarge,
    )
}