package com.example

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.CaptureViewModel
import com.example.ui.MainScreen
import com.example.ui.RoiEditorScreen
import com.example.ui.theme.SubCastTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            SubCastTheme {
                val viewModel: CaptureViewModel = viewModel()
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val lifecycleOwner = LocalLifecycleOwner.current

                val projectionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    viewModel.onMediaProjectionResult(result.resultCode, result.data)
                }

                val launchMediaProjectionConsent = {
                    val intent = viewModel.createMediaProjectionIntent()
                    if (intent != null) {
                        projectionLauncher.launch(intent)
                    }
                }

                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { granted ->
                    viewModel.onNotificationPermissionResult(granted)
                    launchMediaProjectionConsent()
                }

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            viewModel.refreshEnvironmentChecks()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                if (uiState.isRoiEditorOpen) {
                    RoiEditorScreen(
                        initialRoi = uiState.roiSettings.roi,
                        previewBitmap = uiState.session.previewBitmap,
                        onSaveRoi = { savedRoi -> viewModel.saveRoi(savedRoi) },
                        onCancel = { viewModel.closeRoiEditor() }
                    )
                } else {
                    MainScreen(
                        uiState = uiState,
                        onStartCaptureClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                viewModel.needsNotificationPermission()
                            ) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                launchMediaProjectionConsent()
                            }
                        },
                        onStopCaptureClick = { viewModel.stopCapture() },
                        onOpenRoiEditorClick = { viewModel.openRoiEditor() },
                        onUpdateOcrIntervalMs = { intervalMs -> viewModel.updateOcrIntervalMs(intervalMs) },
                        onPrepareTranslationClick = { viewModel.prepareTranslationModel() },
                        onPrepareSpeechClick = { viewModel.prepareSpeechModel() },
                        onTestVoiceClick = { viewModel.testSpeechVoice() },
                        onLaunchRemotePlayClick = { viewModel.launchRemotePlayIfInstalled() },
                        onRefreshChecksClick = { viewModel.refreshEnvironmentChecks() },
                        onDismissErrorClick = { viewModel.dismissError() }
                    )
                }
            }
        }
    }
}
