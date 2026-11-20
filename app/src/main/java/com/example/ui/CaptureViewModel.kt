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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class EnvironmentStatus(
    val isRemotePlayInstalled: Boolean = false,
    val isNotificationPermissionGranted: Boolean = true,
    val isMediaProjectionSupported: Boolean = true,
    val hardwareSummary: String = ""
)

data class CaptureDashboardUiState(
    val session: CaptureSessionState = CaptureSessionState(),
    val environment: EnvironmentStatus = EnvironmentStatus()
)

class CaptureViewModel(application: Application) : AndroidViewModel(application) {

    private val projectionController = MediaProjectionController(application.applicationContext)
    private val environmentState = MutableStateFlow(readEnvironmentStatus())

    val uiState: StateFlow<CaptureDashboardUiState> = combine(
        CaptureStateHolder.state,
        environmentState
    ) { session, environment ->
        CaptureDashboardUiState(
            session = session.copy(
                hardwareInfo = session.hardwareInfo ?: projectionController.inspectHardwareAcceleration()
            ),
            environment = environment
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
