package com.safesignal

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.safesignal.core.common.permission.SafeSignalPermission
import com.safesignal.ui.SafeSignalApp
import com.safesignal.ui.SafeSignalViewModel

/**
 * Hosts the app and keeps its state honest.
 *
 * Permission state is re-read on **every resume**, which is the only way it stays
 * correct: a user can grant or revoke the microphone from Android Settings while
 * this app sits in the background, and the platform never tells the app about it.
 * Without this the screen kept claiming "not granted" after a grant — which is what
 * the first real device run showed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafeSignalRoot(
    activity: ComponentActivity,
    viewModel: SafeSignalViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    var currentTab by remember { mutableIntStateOf(0) }

    // The permission checker is owned by the ViewModel, which needs it to rebuild
    // the readiness report. The Activity only supplies the current one for the
    // Activity-scoped rationale check.
    var resumeCount by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeCount++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(resumeCount) {
        viewModel.refresh(activity, context)
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHost.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    val permissionChecker = viewModel.permissionCheckerOrNull
    val requestPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // A grant made in Settings must not leave stale request history behind, or
        // the next revocation would be reported as permanently blocked.
        permissionChecker.forgetRequestsNowGranted(SafeSignalPermission.ALL)
        viewModel.refresh(activity, context)
    }

    // One Scaffold, not two. `SafeSignalApp` owns the top and bottom bars, so the
    // snackbar is handed to it rather than hosted in a nested Scaffold — nesting
    // would double the insets and drop the inner content padding.
    MaterialTheme {
        SafeSignalApp(
                state = state,
                currentTab = currentTab,
                onTabSelected = { currentTab = it },
            onArm = viewModel::arm,
            onDisarm = viewModel::disarm,
            onRecordNow = viewModel::recordNow,
            onPatternChange = viewModel::setVolumePattern,
            onPatternEnabledChange = viewModel::setVolumePatternEnabled,
            onRequestMicrophone = {
                    // Recorded before the dialog appears. `shouldShowRequestPermission-
                    // Rationale` is false both before the first request and after a
                    // block, so this flag is the only thing that tells them apart.
                    permissionChecker.recordRequest(SafeSignalPermission.REQUESTED_AT_ONBOARDING)
                    requestPermissions.launch(
                        SafeSignalPermission.REQUESTED_AT_ONBOARDING
                            .map { it.manifestPermission }
                            .toTypedArray(),
                    )
                },
            onOpenSettings = { context.openAppSettings() },
            snackbarHost = { SnackbarHost(snackbarHost) },
        )
    }
}

/** Sends the user to this app's settings page, for permissions Settings cannot grant. */
private fun Context.openAppSettings() {
    startActivity(
        android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", packageName, null),
        )
    )
}
