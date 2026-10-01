package com.safesignal.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safesignal.core.common.permission.AndroidPermissionChecker
import com.safesignal.core.common.permission.PermissionStatus
import com.safesignal.core.common.permission.SafeSignalPermission
import androidx.core.app.NotificationManagerCompat
import com.safesignal.core.common.readiness.Capability
import com.safesignal.core.common.readiness.ReadinessReport
import com.safesignal.core.common.readiness.microphoneOutcome
import com.safesignal.core.common.readiness.notificationOutcome
import com.safesignal.core.common.trigger.PatternAdvice
import com.safesignal.core.common.trigger.VolumePattern
import com.safesignal.core.common.trigger.VolumePatternPolicy
import com.safesignal.core.database.dao.RecordingDao
import com.safesignal.core.database.entity.RecordingEntity
import com.safesignal.service.ArmingController
import com.safesignal.service.ArmingState
import com.safesignal.di.ReadinessProbes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What the UI shows, decided in one place.
 *
 * The screens take this and nothing else. That is what keeps the "is SafeSignal
 * recording?" answer in a single class rather than reconstructed by each screen
 * from a slightly different combination of flows — the kind of drift that ends with
 * a screen showing "armed" while the microphone is closed.
 */
data class SafeSignalUiState(
    val readiness: ReadinessReport = ReadinessReport.build(emptyList()),
    val microphoneGranted: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val arming: ArmingState = ArmingState.Disarmed,
    val armedUntilElapsed: Long? = null,
    val recordings: List<RecordingEntity> = emptyList(),
    val volumePattern: VolumePattern = DEFAULT_PATTERN,
    val patternAdvice: PatternAdvice = VolumePatternPolicy.advise(DEFAULT_PATTERN),
    val volumePatternEnabled: Boolean = false,
    val wakeWordEnrolled: Boolean = false,
    val wakeWordAccurate: Boolean = false,
    val message: String? = null,
) {
    /** True when nothing is blocking arming. A capability that was never probed counts as blocking. */
    val canArm: Boolean get() = blockers.isEmpty()

    /**
     * The capabilities standing between the user and arming, phrased for display.
     *
     * Every capability is included rather than a hard-coded shortlist, so a new one
     * cannot be added without appearing here. An unprobed capability is blocking,
     * because "we did not check" must never read as "ready".
     */
    val blockers: List<String>
        get() = readiness.all.filterNot { it.isReady }.mapNotNull { blockerLabel(it.capability) }

    val isRecording: Boolean get() = arming is ArmingState.Recording

    val isArmed: Boolean get() = arming.isActive
}

/**
 * A starting pattern the policy accepts: three steps that change direction, so it
 * cannot be confused with skipping tracks.
 *
 * Offered as a starting point, not forced. The user picks their own — see
 * `VolumePatternPolicy` for why the rules exist.
 */
val DEFAULT_PATTERN = VolumePattern(
    listOf(VolumePattern.Step.UP, VolumePattern.Step.DOWN, VolumePattern.Step.UP),
)

/** Human labels for the capabilities that gate arming. */
private fun blockerLabel(capability: Capability): String? = when (capability) {
    Capability.MicrophonePermission -> "Microphone access"
    Capability.NotificationPermission -> "Notifications"
    Capability.Encryption -> "Encryption"
    Capability.Storage -> "Storage"
    Capability.ForegroundService -> "Background recording"
    Capability.WakeWord -> "Wake word"
    else -> null
}

@HiltViewModel
class SafeSignalViewModel @Inject constructor(
    private val arming: ArmingController,
    private val recordingDao: RecordingDao,
    private val permissionChecker: AndroidPermissionChecker,
    private val probes: ReadinessProbes,
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    private val _pattern = MutableStateFlow(DEFAULT_PATTERN)
    private val _patternEnabled = MutableStateFlow(false)
    private val _readiness = MutableStateFlow(ReadinessReport.build(emptyList()))
    private val _permissionState = MutableStateFlow(PermissionSnapshot())
    private val _wakeWord = MutableStateFlow(WakeWordSnapshot())

    val state: StateFlow<SafeSignalUiState> = combine(
        combine(_readiness, _permissionState, _message, _wakeWord, ::combineFour),
        arming.state,
        recordingDao.observeRecordings(),
        combine(_pattern, _patternEnabled, arming.armedUntil, ::combineTrigger),
    ) { head, armingState, recordings, trigger ->
        SafeSignalUiState(
            readiness = head.readiness,
            microphoneGranted = head.permissions.microphoneGranted,
            notificationsEnabled = head.permissions.notificationsEnabled,
            arming = armingState,
            armedUntilElapsed = trigger.armedUntil,
            recordings = recordings,
            volumePattern = trigger.pattern,
            patternAdvice = VolumePatternPolicy.advise(trigger.pattern),
            volumePatternEnabled = trigger.enabled,
            wakeWordEnrolled = head.wakeWord.enrolled,
            wakeWordAccurate = head.wakeWord.accuracyMeasured,
            message = head.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SafeSignalUiState())

    init {
        observeRecordings()
    }

    /** The checker, so the Activity can record a request before showing a dialog. */
    val permissionCheckerOrNull: AndroidPermissionChecker get() = permissionChecker

    /**
     * Re-probes capabilities.
     *
     * Called on every resume and after every permission change, because the platform
     * never notifies the app when a user grants or revokes a permission from
     * Android Settings. Reading it only at startup is how a screen ends up claiming
     * "not granted" after a grant — which is what the first device run showed.
     */
    fun refresh(activity: android.app.Activity, context: android.content.Context) {
        viewModelScope.launch {
            _permissionState.value = PermissionSnapshot(
                microphoneStatus = permissionChecker.status(
                    SafeSignalPermission.RECORD_AUDIO,
                    activity,
                ),
                notificationStatus = permissionChecker.status(
                    SafeSignalPermission.POST_NOTIFICATIONS,
                    activity,
                ),
                notificationsEnabled = NotificationManagerCompat.from(context)
                    .areNotificationsEnabled(),
            )
            _wakeWord.value = WakeWordSnapshot(
                enrolled = arming.wakeWordEnrolled,
                accuracyMeasured = false,
            )
            _readiness.value = ReadinessReport.build(
                listOf(
                    Capability.MicrophonePermission to {
                        microphoneOutcome(_permissionState.value.microphoneStatus)
                    },
                    Capability.NotificationPermission to {
                        notificationOutcome(
                            permissionGranted = _permissionState.value.notificationStatus ==
                                PermissionStatus.Granted,
                            notificationsEnabled = _permissionState.value.notificationsEnabled,
                        )
                    },
                    Capability.Encryption to { probes.encryption() },
                    Capability.Storage to { probes.storage() },
                    Capability.WakeWord to {
                        probes.wakeWord(detectorAvailable = arming.wakeWordEnrolled)
                    },
                ),
            )
        }
    }

    fun arm() {
        viewModelScope.launch {
            arming.arm().onSuccess {
                if (_patternEnabled.value) arming.enableVolumePattern(_pattern.value)
            }.onFailure {
                _message.value = it.message ?: "Could not arm"
            }
        }
    }

    fun disarm() {
        viewModelScope.launch { arming.disarm() }
    }

    /** The reliable trigger: a deliberate tap. */
    fun recordNow() {
        viewModelScope.launch {
            arming.trigger().onFailure {
                _message.value = it.message ?: "Could not start recording"
            }
        }
    }

    fun setVolumePattern(pattern: VolumePattern) {
        _pattern.value = pattern
    }

    fun setVolumePatternEnabled(enabled: Boolean) {
        _patternEnabled.value = enabled
        if (enabled && arming.isArmed) {
            viewModelScope.launch { arming.enableVolumePattern(_pattern.value) }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun observeRecordings() {
        // Nothing to do beyond the flow the state combines; kept as a hook so the
        // dependency is explicit rather than incidental.
    }

    private data class PermissionSnapshot(
        val microphoneStatus: PermissionStatus = PermissionStatus.Denied,
        val notificationStatus: PermissionStatus = PermissionStatus.Denied,
        val notificationsEnabled: Boolean = false,
    ) {
        val microphoneGranted: Boolean get() = microphoneStatus == PermissionStatus.Granted
    }

    private data class WakeWordSnapshot(
        val enrolled: Boolean = false,
        val accuracyMeasured: Boolean = false,
    )

    private data class Head(
        val readiness: ReadinessReport,
        val permissions: PermissionSnapshot,
        val message: String?,
        val wakeWord: WakeWordSnapshot,
    )

    private data class Trigger(
        val pattern: VolumePattern,
        val enabled: Boolean,
        val armedUntil: Long?,
    )

    private fun combineFour(
        readiness: ReadinessReport,
        permissions: PermissionSnapshot,
        message: String?,
        wakeWord: WakeWordSnapshot,
    ) = Head(readiness, permissions, message, wakeWord)

    private fun combineTrigger(
        pattern: VolumePattern,
        enabled: Boolean,
        armedUntil: Long?,
    ) = Trigger(pattern, enabled, armedUntil)

}
