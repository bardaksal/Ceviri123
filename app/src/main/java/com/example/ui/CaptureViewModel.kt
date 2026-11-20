package com.example.ui

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.capture.CaptureSessionState
import com.example.capture.CaptureStateHolder
import com.example.capture.CaptureStatus
import com.example.capture.MediaProjectionController
import com.example.capture.ScreenCaptureService
import com.example.speech.SherpaOnnxTurkishSpeechEngine
import com.example.speech.SpeechPipelineWorker
import com.example.subtitle.RoiPreferencesRepository
import com.example.subtitle.SubtitleRoi
import com.example.subtitle.SubtitleRoiSettings
import com.example.translation.MlKitTranslationEngine
import com.example.translation.TranslationPipelineWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class EnvironmentStatus(
    val isRemotePlayInstalled: Boolean = false,
    val isNotificationPermissionGranted: Boolean = true,
    val isMediaProjectionSupported: Boolean = true,
    val hardwareSummary: String = ""
)

data class CaptureDashboardUiState(
    val session: CaptureSessionState = CaptureSessionState(),
    val environment: EnvironmentStatus = EnvironmentStatus(),
    val roiSettings: SubtitleRoiSettings = SubtitleRoiSettings(),
    val isRoiEditorOpen: Boolean = false
)

class CaptureViewModel(application: Application) : AndroidViewModel(application) {

    private val projectionController = MediaProjectionController(application.applicationContext)
    private val roiRepository = RoiPreferencesRepository(application.applicationContext)
    private val modelHelperWorker = TranslationPipelineWorker(
        translationEngine = MlKitTranslationEngine(),
        onStateChanged = { translationState ->
            CaptureStateHolder.update { current ->
                // Only update from ViewModel helper when capture service is not actively driving translation
                if (current.status == CaptureStatus.ACTIVE) {
                    current
                } else {
                    current.copy(translationState = translationState)
                }
            }
        }
    )

    private val speechHelperWorker = SpeechPipelineWorker(
        speechEngine = SherpaOnnxTurkishSpeechEngine(application.applicationContext),
        onStateChanged = { speechState ->
            CaptureStateHolder.update { current ->
                if (current.status == CaptureStatus.ACTIVE || current.status == CaptureStatus.STARTING) {
                    current
                } else {
                    current.copy(speechState = speechState)
                }
            }
        }
    )

    private val environmentState = MutableStateFlow(readEnvironmentStatus())
    private val isRoiEditorOpen = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            modelHelperWorker.refreshModelState()
        }
        speechHelperWorker.start()
    }

    val uiState: StateFlow<CaptureDashboardUiState> = combine(
        CaptureStateHolder.state,
        environmentState,
        roiRepository.settingsFlow,
        isRoiEditorOpen
    ) { session, environment, roiSettings, editorOpen ->
        CaptureDashboardUiState(
            session = session.copy(
                hardwareInfo = session.hardwareInfo ?: projectionController.inspectHardwareAcceleration()
            ),
            environment = environment,
            roiSettings = roiSettings,
            isRoiEditorOpen = editorOpen
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = CaptureDashboardUiState(
            session = CaptureStateHolder.state.value,
            environment = environmentState.value
        )
    )

    fun refreshEnvironmentChecks() {
        environmentState.value = readEnvironmentStatus()
        if (CaptureStateHolder.state.value.status != CaptureStatus.ACTIVE) {
            viewModelScope.launch {
                modelHelperWorker.refreshModelState()
            }
            speechHelperWorker.start()
        }
    }

    fun prepareTranslationModel() {
        val currentStatus = CaptureStateHolder.state.value.status
        if (currentStatus == CaptureStatus.ACTIVE) {
            val appContext = getApplication<Application>().applicationContext
            appContext.startService(ScreenCaptureService.createPrepareTranslationIntent(appContext))
        } else {
            modelHelperWorker.prepareModel()
        }
    }

    fun prepareSpeechModel() {
        val currentStatus = CaptureStateHolder.state.value.status
        if (currentStatus == CaptureStatus.ACTIVE) {
            val appContext = getApplication<Application>().applicationContext
            appContext.startService(ScreenCaptureService.createPrepareTtsIntent(appContext))
        } else {
            speechHelperWorker.prepareModel()
        }
    }

    fun testSpeechVoice() {
        val currentStatus = CaptureStateHolder.state.value.status
        if (currentStatus == CaptureStatus.ACTIVE) {
            val appContext = getApplication<Application>().applicationContext
            appContext.startService(ScreenCaptureService.createTestTtsIntent(appContext))
        } else {
            speechHelperWorker.runDiagnosticTest()
        }
    }

    override fun onCleared() {
        modelHelperWorker.release()
        speechHelperWorker.release()
        super.onCleared()
    }

    fun openRoiEditor() {
        isRoiEditorOpen.value = true
    }

    fun closeRoiEditor() {
        isRoiEditorOpen.value = false
    }

    fun saveRoi(roi: SubtitleRoi) {
        viewModelScope.launch {
            roiRepository.saveRoi(roi)
            isRoiEditorOpen.value = false
        }
    }

    fun resetRoiToDefault() {
        viewModelScope.launch {
            roiRepository.resetRoi()
        }
    }

    fun updateOcrIntervalMs(intervalMs: Long) {
        viewModelScope.launch {
            roiRepository.updateOcrIntervalMs(intervalMs)
        }
    }

    fun createMediaProjectionIntent(): Intent? {
        return projectionController.createScreenCaptureIntent()
    }

    fun needsNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return ContextCompat.checkSelfPermission(
            getApplication(),
            Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        refreshEnvironmentChecks()
        if (!granted) {
            CaptureStateHolder.update {
                it.copy(
                    errorMessage = "Bildirim izni verilmedi; Android durum çubuğu çipi üzerinden yakalama yine de yönetilebilir."
                )
            }
        }
    }

    fun onMediaProjectionResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            CaptureStateHolder.update {
                it.copy(
                    status = CaptureStatus.IDLE,
                    errorMessage = "Ekran yakalama izni kullanıcı tarafından iptal edildi."
                )
            }
            return
        }

        // Release any idle ViewModel native ONNX TTS instance before service acquires its own
        speechHelperWorker.unloadEngineForHandoff()

        val appContext = getApplication<Application>().applicationContext
        val serviceIntent = ScreenCaptureService.createStartIntent(appContext, resultCode, data)
        ContextCompat.startForegroundService(appContext, serviceIntent)
    }

    fun stopCapture() {
        val appContext = getApplication<Application>().applicationContext
        appContext.startService(ScreenCaptureService.createStopIntent(appContext))
    }

    fun launchRemotePlayIfInstalled(): Boolean {
        val appContext = getApplication<Application>().applicationContext
        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(REMOTE_PLAY_PACKAGE)
            ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(launchIntent)
        return true
    }

    fun dismissError() {
        CaptureStateHolder.clearError()
    }

    private fun readEnvironmentStatus(): EnvironmentStatus {
        val appContext = getApplication<Application>().applicationContext
        val remotePlayInstalled = isPackageInstalled(appContext.packageManager, REMOTE_PLAY_PACKAGE)
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val hwInfo = projectionController.inspectHardwareAcceleration()
        return EnvironmentStatus(
            isRemotePlayInstalled = remotePlayInstalled,
            isNotificationPermissionGranted = notificationGranted,
            isMediaProjectionSupported = projectionController.isMediaProjectionSupported(),
            hardwareSummary = hwInfo.summaryLabel
        )
    }

    private fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    companion object {
        const val REMOTE_PLAY_PACKAGE = "com.playstation.remoteplay"
    }
}
