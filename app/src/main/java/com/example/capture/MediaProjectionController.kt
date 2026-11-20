package com.example.capture

import android.content.Context
import android.content.Intent
import android.hardware.HardwareBuffer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DisplayBounds(
    val width: Int,
    val height: Int,
    val densityDpi: Int
)

object CaptureStateHolder {
    private val _state = MutableStateFlow(CaptureSessionState())
    val state: StateFlow<CaptureSessionState> = _state.asStateFlow()

    fun update(transform: (CaptureSessionState) -> CaptureSessionState) {
        _state.update(transform)
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }
}

class MediaProjectionController(private val context: Context) {

    private val systemManager: MediaProjectionManager? by lazy {
        context.getSystemService(MediaProjectionManager::class.java)
    }

    fun isMediaProjectionSupported(): Boolean = systemManager != null

    fun createScreenCaptureIntent(): Intent? {
        return systemManager?.createScreenCaptureIntent()
    }

    fun acquireMediaProjection(resultCode: Int, resultData: Intent): MediaProjection? {
        return systemManager?.getMediaProjection(resultCode, resultData)
    }

    fun registerProjectionCallback(
        projection: MediaProjection,
        handler: Handler,
        onStopped: () -> Unit,
        onResized: (Int, Int) -> Unit,
        onVisibilityChanged: (Boolean) -> Unit
    ): MediaProjection.Callback {
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                onStopped()
            }

            override fun onCapturedContentResize(width: Int, height: Int) {
                if (width > 0 && height > 0) {
                    onResized(width, height)
                }
            }

            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                onVisibilityChanged(isVisible)
            }
        }
        projection.registerCallback(callback, handler)
        return callback
    }

    fun queryMaximumDisplayBounds(): DisplayBounds {
        val windowManager = context.getSystemService(WindowManager::class.java)
        val densityDpi = context.resources.displayMetrics.densityDpi.coerceAtLeast(DisplayMetrics.DENSITY_MEDIUM)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && windowManager != null) {
            val metrics = windowManager.maximumWindowMetrics
            val bounds = metrics.bounds
            val width = bounds.width().coerceAtLeast(1)
            val height = bounds.height().coerceAtLeast(1)
            return DisplayBounds(width = width, height = height, densityDpi = densityDpi)
        }

        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getRealMetrics(displayMetrics)
        val width = displayMetrics.widthPixels.takeIf { it > 0 }
            ?: context.resources.displayMetrics.widthPixels
        val height = displayMetrics.heightPixels.takeIf { it > 0 }
            ?: context.resources.displayMetrics.heightPixels
        return DisplayBounds(
            width = width.coerceAtLeast(1),
            height = height.coerceAtLeast(1),
            densityDpi = densityDpi
        )
    }

    fun inspectHardwareAcceleration(): HardwareAccelerationInfo {
        val api = Build.VERSION.SDK_INT
        val supportsHardwareBuffer = api >= Build.VERSION_CODES.P
        val supportsNnapi = api >= Build.VERSION_CODES.O_MR1
        val supportsResize = api >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

        val summary = buildString {
            append("API $api")
            append(if (supportsHardwareBuffer) " • HardwareBuffer" else " • ByteBuffer")
            if (supportsNnapi) append(" • NNAPI Ready")
        }

        return HardwareAccelerationInfo(
            apiLevel = api,
            supportsHardwareBuffer = supportsHardwareBuffer,
            supportsNnapiRuntime = supportsNnapi,
            supportsMediaProjectionResize = supportsResize,
            summaryLabel = summary
        )
    }

    companion object {
        fun isHardwareBufferAvailable(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                HardwareBuffer.isSupported(
                    1, 1,
                    HardwareBuffer.RGBA_8888,
                    1,
                    HardwareBuffer.USAGE_CPU_READ_OFTEN
                )
        }
    }
}
