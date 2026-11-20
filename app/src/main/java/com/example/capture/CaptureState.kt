package com.example.capture

import android.graphics.Bitmap

enum class CaptureStatus {
    IDLE,
    STARTING,
    ACTIVE,
    STOPPED_BY_SYSTEM,
    ERROR
}

data class HardwareAccelerationInfo(
    val apiLevel: Int,
    val supportsHardwareBuffer: Boolean,
    val supportsNnapiRuntime: Boolean,
    val supportsMediaProjectionResize: Boolean,
    val summaryLabel: String
)

data class FrameTelemetry(
    val frameIndex: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val captureTimestampMs: Long = 0L,
    val conversionTimeMs: Long = 0L,
    val captureFps: Float = 0f,
    val droppedFrames: Long = 0L,
    val pixelFormatLabel: String = "RGBA_8888",
    val usedHardwareBuffer: Boolean = false,
    val stridePaddingBytes: Int = 0
)

data class CaptureSessionState(
    val status: CaptureStatus = CaptureStatus.IDLE,
    val telemetry: FrameTelemetry? = null,
    val previewBitmap: Bitmap? = null,
    val errorMessage: String? = null,
    val isContentVisible: Boolean = true,
    val hardwareInfo: HardwareAccelerationInfo? = null
)

data class CapturedFrame(
    val bitmap: Bitmap,
    val frameIndex: Long,
    val width: Int,
    val height: Int,
    val timestampElapsedMs: Long,
    val conversionTimeMs: Long,
    val droppedFrames: Long,
    val usedHardwareBuffer: Boolean,
    val stridePaddingBytes: Int
)
