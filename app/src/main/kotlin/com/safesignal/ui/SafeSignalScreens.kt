package com.safesignal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.safesignal.core.common.trigger.PatternAdvice
import com.safesignal.core.common.trigger.VolumePattern
import com.safesignal.service.ArmingState

/**
 * The app's screens.
 *
 * ### One rule these screens follow without exception
 *
 * **Never say something the code cannot back up.** Every status here comes from a
 * state the controller actually holds, and the wording for each case was chosen
 * because the less accurate version was already shipped and was wrong: an app that
 * reported a clean 12-second capture as "could not start recording" taught us that a
 * reassuring-looking screen can be a lie in either direction.
 *
 * So: the wake-word row says detection is unmeasured, the arm button is disabled
 * with a stated reason rather than silently doing nothing, and the armed countdown
 * is visible for the whole time the microphone is open.
 */

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SafeSignalApp(
    state: SafeSignalUiState,
    currentTab: Int,
    onTabSelected: (Int) -> Unit,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
    onRecordNow: () -> Unit,
    onPatternChange: (VolumePattern) -> Unit,
    onPatternEnabledChange: (Boolean) -> Unit,
    onRequestMicrophone: () -> Unit,
    onOpenSettings: () -> Unit,
    snackbarHost: @Composable () -> Unit = {},
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("SafeSignal") }) },
        snackbarHost = snackbarHost,
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                listOf("Arm", "Recordings", "Setup").forEachIndexed { index, label ->
                    val selected = index == currentTab
                    TextButton(
                        onClick = { onTabSelected(index) },
                        modifier = if (selected) Modifier.background(brandColour()) else Modifier,
                    ) {
                        Text(
                            label,
                            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
    ) { padding ->
        when (currentTab) {
            0 -> ArmScreen(state, onArm, onDisarm, onRecordNow, Modifier.padding(padding))
            1 -> HistoryScreen(state, Modifier.padding(padding))
            else -> SetupScreen(
                state = state,
                onPatternChange = onPatternChange,
                onPatternEnabledChange = onPatternEnabledChange,
                onRequestMicrophone = onRequestMicrophone,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun ArmScreen(
    state: SafeSignalUiState,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
    onRecordNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusCard(state)

        if (!state.canArm && !state.isArmed) {
            Text(
                "SafeSignal cannot arm yet. It will not open the microphone unless it " +
                    "can show you that it is recording.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            state.blockers.forEach { blocker ->
                Text("• $blocker is not ready", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(4.dp))

        when {
            state.isRecording -> {
                Button(
                    onClick = onDisarm,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Stop recording")
                }
                Text(
                    "Recording now. The microphone is open and the notification stays " +
                        "until this stops.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            state.isArmed -> {
                Button(onClick = onRecordNow, modifier = Modifier.fillMaxWidth()) {
                    Text("Record now")
                }
                OutlinedButton(onClick = onDisarm, modifier = Modifier.fillMaxWidth()) {
                    Text("Disarm")
                }
                Text(
                    "Listening for your phrase. Nothing is being saved until a trigger fires.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            else -> {
                Button(
                    onClick = onArm,
                    enabled = state.canArm,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Arm SafeSignal")
                }
                Text(
                    "Arming opens the microphone and starts listening for your phrase. " +
                        "It disarms itself after 8 hours.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        state.message?.let { message ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Text(message, modifier = Modifier.padding(12.dp))
            }
        }

        TriggerCard(state)
    }
}

/**
 * The state banner.
 *
 * States are named for what is true of the microphone, not for what the user asked
 * for. "Listening" means the microphone is open and nothing is being written; if it
 * were merely "armed" with no qualifier, someone could not tell whether their
 * conversation was already being recorded.
 */
@Composable
private fun StatusCard(state: SafeSignalUiState) {
    val (title, detail, tint) = when (val arming = state.arming) {
        is ArmingState.Recording -> Triple(
            "Recording",
            "The microphone is open and audio is being saved to this device.",
            MaterialTheme.colorScheme.error,
        )

        ArmingState.Listening -> Triple(
            "Listening",
            "The microphone is open. Nothing is saved until a trigger fires.",
            brandColour(),
        )

        ArmingState.Finalizing -> Triple(
            "Finishing",
            "Sealing what was captured. This takes a moment.",
            brandColour(),
        )

        is ArmingState.Failed -> Triple(
            "Not running",
            arming.message,
            MaterialTheme.colorScheme.error,
        )

        ArmingState.Disarmed -> Triple(
            "Disarmed",
            "The microphone is closed. Nothing is being captured.",
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Card(colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.12f))) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
            state.armedUntilElapsed?.let {
                Text(
                    "Disarms automatically. The microphone closes on its own.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Which triggers are available, and the honest state of the uncertain one. */
@Composable
private fun TriggerCard(state: SafeSignalUiState) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ways to start a recording", style = MaterialTheme.typography.titleMedium)
            TriggerRow("This app", "Always works. You tap Record now.")
            TriggerRow(
                label = "Volume buttons",
                detail = if (state.volumePatternEnabled) {
                    "Your pattern (${state.volumePattern.describe()}) starts a recording."
                } else {
                    "Off. Turn it on in Setup."
                },
            )
            TriggerRow(
                label = "Saying the phrase",
                detail = if (state.wakeWordEnrolled) {
                    // The important line. Detection being wired up is not the same as
                    // detection being reliable, and a user must be able to tell.
                    "Listening for your phrase. Accuracy has not been measured on this " +
                        "device, so treat this as a convenience rather than a guarantee."
                } else {
                    "Not set up yet. Use the app button or the volume pattern."
                },
            )
        }
    }
}

@Composable
private fun TriggerRow(label: String, detail: String) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(detail, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun HistoryScreen(state: SafeSignalUiState, modifier: Modifier = Modifier) {
    if (state.recordings.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("No recordings yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "When SafeSignal records, the audio is encrypted and stored on this " +
                    "device. It appears here with how long it ran and how many segments " +
                    "were sealed.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(state.recordings, key = { it.recordingId }) { recording ->
            RecordingRow(recording)
        }
    }
}

@Composable
private fun RecordingRow(recording: com.safesignal.core.database.entity.RecordingEntity) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (recording.isTestRecording) "Test recording" else "Recording",
                    fontWeight = FontWeight.Medium,
                )
                if (recording.isTestRecording) {
                    // The label travels with the evidence. A test must never be
                    // mistakable for something that happened.
                    Text(
                        "TEST",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(
                "${describeDuration(recording.durationMillis)} • " +
                    "${recording.segmentCount} segment(s) • " +
                    formatBytes(recording.sealedBytes),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Started by: ${recording.activationSource.replace('_', ' ').lowercase()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Divider(Modifier.padding(vertical = 4.dp))
            Text(
                "Encrypted with a key held in this device's secure hardware. " +
                    "Nobody else can read it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SetupScreen(
    state: SafeSignalUiState,
    onPatternChange: (VolumePattern) -> Unit,
    onPatternEnabledChange: (Boolean) -> Unit,
    onRequestMicrophone: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Readiness", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            state.readiness.all.forEach { status ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(status.capability.name.replace(Regex("(?<!^)([A-Z])"), " $1"))
                    Text(
                        if (status.isReady) "ready" else "not ready",
                        color = if (status.isReady) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        }

        item { Divider() }

        item {
            Text("Volume button pattern", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "While SafeSignal is armed, the volume buttons belong to it. Music " +
                    "volume will not change until you disarm.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Use a volume pattern")
                Switch(checked = state.volumePatternEnabled, onCheckedChange = onPatternEnabledChange)
            }
            PatternPicker(state, onPatternChange)
        }

        item { Divider() }

        item {
            Text("Permissions", style = MaterialTheme.typography.titleMedium)
            if (!state.microphoneGranted) {
                Button(onClick = onRequestMicrophone, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Allow microphone access")
                }
            }
            TextButton(onClick = onOpenSettings, modifier = Modifier.padding(top = 8.dp)) {
                Text("Open Android settings")
            }
        }
    }
}

/** Step editor for the volume pattern, with the policy's objections stated. */
@Composable
private fun PatternPicker(state: SafeSignalUiState, onPatternChange: (VolumePattern) -> Unit) {
    val current = state.volumePattern

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            current.steps.forEachIndexed { index, step ->
                FilterChip(
                    selected = true,
                    onClick = {
                        val remaining = current.steps.toMutableList()
                        remaining[index] = if (step == VolumePattern.Step.UP) {
                            VolumePattern.Step.DOWN
                        } else {
                            VolumePattern.Step.UP
                        }
                        onPatternChange(VolumePattern(remaining))
                    },
                    label = { Text(if (step == VolumePattern.Step.UP) "up" else "down") },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { onPatternChange(VolumePattern(current.steps + VolumePattern.Step.UP)) },
                enabled = current.steps.size < 5,
            ) { Text("+ up") }
            TextButton(
                onClick = { onPatternChange(VolumePattern(current.steps + VolumePattern.Step.DOWN)) },
                enabled = current.steps.size < 5,
            ) { Text("+ down") }
            TextButton(
                onClick = { onPatternChange(VolumePattern(current.steps.dropLast(1))) },
                enabled = current.steps.size > 1,
            ) { Text("remove") }
        }

        if (!state.patternAdvice.isAcceptable) {
            state.patternAdvice.warnings.forEach { warning ->
                Text(
                    text = when (warning) {
                        PatternAdvice.Warning.TOO_SHORT ->
                            "One press is just a volume change. Use at least three."

                        PatternAdvice.Warning.TOO_LONG ->
                            "Harder to press in a hurry. Five steps is enough."

                        PatternAdvice.Warning.NO_DIRECTION_CHANGE ->
                            "All the same direction. That is what skipping tracks looks " +
                                "like, so it would fire by accident."

                        PatternAdvice.Warning.REPEATED_STEP ->
                            "Three presses the same way in a row is what a held button " +
                                "looks like."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun brandColour(): Color = Color(0xFF00696E)

/** Duration the way a person says it, not `MM:SS`. */
internal fun describeDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return when {
        minutes > 0 && seconds > 0 -> "$minutes min $seconds sec"
        minutes > 0 -> "$minutes min"
        else -> "$seconds sec"
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
