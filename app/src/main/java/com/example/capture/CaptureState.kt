package com.example.capture

import android.graphics.Bitmap
import com.example.speech.SpeechDiagnosticReport
import com.example.speech.SpeechErrorType
import com.example.speech.SpeechModelState
import com.example.speech.SpeechStatus
import com.example.translation.TranslationModelState
import com.example.translation.TranslationStatus

enum class CaptureStatus {
    IDLE,
    STARTING,
    ACTIVE,
    STOPPED_BY_SYSTEM,
    ERROR
}

enum class OcrStatus {
    IDLE,
    WAITING_FOR_FRAME,
    RUNNING,
    READY,
    MODEL_UNAVAILABLE,
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

data class OcrState(
    val status: OcrStatus = OcrStatus.IDLE,
    val latestText: String? = null,
    val confidence: Float? = null,
    val lastRecognitionElapsedMs: Long? = null,
    val processingTimeMs: Long? = null,
    val sourceFrameIndex: Long = 0L,
    val recognitionCount: Long = 0L,
    val isNewText: Boolean = false,
    val errorMessage: String? = null,
    val engineLabel: String = "On-device ML Kit"
)

data class TranslationState(
    val modelState: TranslationModelState = TranslationModelState.NOT_READY,
    val status: TranslationStatus = TranslationStatus.MODEL_NOT_READY,
    val sourceText: String? = null,
    val translatedText: String? = null,
    val sourceFrameIndex: Long = 0L,
    val processingTimeMs: Long? = null,
    val isTranslating: Boolean = false,
    val errorMessage: String? = null,
    val engineLabel: String = "On-device ML Kit (EN → TR)"
)

data class SpeechState(
    val modelState: SpeechModelState = SpeechModelState.MODEL_NOT_READY,
    val status: SpeechStatus = SpeechStatus.MODEL_NOT_READY,
    val languageCode: String = "tr-TR",
    val currentText: String? = null,
    val sourceFrameIndex: Long = 0L,
    val preparationTimeMs: Long? = null,
    val firstAudioLatencyMs: Long? = null,
    val synthesisDurationMs: Long? = null,
    val audioPlaybackDurationMs: Long? = null,
    val downloadProgressPercent: Int? = null,
    val errorType: SpeechErrorType = SpeechErrorType.NONE,
    val errorMessage: String? = null,
    val engineLabel: String = "Sherpa-ONNX Piper (tr-TR)",
    val diagnosticReport: SpeechDiagnosticReport? = null
)

data class CaptureSessionState(
    val status: CaptureStatus = CaptureStatus.IDLE,
    val telemetry: FrameTelemetry? = null,
    val previewBitmap: Bitmap? = null,
    val errorMessage: String? = null,
    val isContentVisible: Boolean = true,
    val hardwareInfo: HardwareAccelerationInfo? = null,
    val ocrState: OcrState = OcrState(),
    val translationState: TranslationState = TranslationState(),
    val speechState: SpeechState = SpeechState()
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
