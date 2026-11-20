package com.example.capture

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Latest-frame-only pipeline coordinator.
 * Updates live capture telemetry, produces throttled UI preview thumbnails,
 * and invokes [onBeforeBitmapRecycle] so downstream ROI cropping can run
 * before the full-resolution frame bitmap is recycled.
 */
class FrameProcessor(
    private val onBeforeBitmapRecycle: ((CapturedFrame) -> Unit)? = null
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val latestPendingFrame = AtomicReference<CapturedFrame?>(null)
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)

    private var workerJob: Job? = null
    private var fpsWindowStartMs: Long = 0L
    private var fpsWindowFrameCount: Int = 0
    private var lastMeasuredFps: Float = 0f
    private var lastPreviewPublishMs: Long = 0L

    fun start() {
        if (workerJob?.isActive == true) return
        fpsWindowStartMs = SystemClock.elapsedRealtime()
        fpsWindowFrameCount = 0
        lastMeasuredFps = 0f
        lastPreviewPublishMs = 0L

        workerJob = scope.launch {
            for (signal in wakeSignal) {
                val frame = latestPendingFrame.getAndSet(null) ?: continue
                processLatestFrame(frame)
            }
        }
    }

    fun submitFrame(frame: CapturedFrame) {
        val replaced = latestPendingFrame.getAndSet(frame)
        if (replaced != null && !replaced.bitmap.isRecycled) {
            replaced.bitmap.recycle()
        }
        wakeSignal.trySend(Unit)
    }

    private fun processLatestFrame(frame: CapturedFrame) {
        val nowMs = SystemClock.elapsedRealtime()
        if (fpsWindowStartMs == 0L) {
            fpsWindowStartMs = nowMs
        }
        fpsWindowFrameCount++
        val elapsedWindow = nowMs - fpsWindowStartMs
        if (elapsedWindow >= FPS_WINDOW_MS) {
            lastMeasuredFps = (fpsWindowFrameCount * 1000f) / elapsedWindow.coerceAtLeast(1L)
            fpsWindowFrameCount = 0
            fpsWindowStartMs = nowMs
        }

        val shouldPublishPreview = (nowMs - lastPreviewPublishMs) >= PREVIEW_THROTTLE_MS || frame.frameIndex == 1L
        var previewThumbnail: Bitmap? = null
        if (shouldPublishPreview) {
            lastPreviewPublishMs = nowMs
            previewThumbnail = createPreviewThumbnail(frame.bitmap)
        }

        val telemetry = FrameTelemetry(
            frameIndex = frame.frameIndex,
            width = frame.width,
            height = frame.height,
            captureTimestampMs = frame.timestampElapsedMs,
            conversionTimeMs = frame.conversionTimeMs,
            captureFps = lastMeasuredFps,
            droppedFrames = frame.droppedFrames,
            pixelFormatLabel = "RGBA_8888",
            usedHardwareBuffer = frame.usedHardwareBuffer,
            stridePaddingBytes = frame.stridePaddingBytes
        )

        CaptureStateHolder.update { current ->
            current.copy(
                status = CaptureStatus.ACTIVE,
                telemetry = telemetry,
                previewBitmap = previewThumbnail ?: current.previewBitmap,
                errorMessage = null
            )
        }

        // Allow downstream consumer (e.g. ROI crop -> decoupled OCR worker) to extract its slice
        // without blocking FrameCapture and before releasing the full-resolution bitmap.
        runCatching {
            onBeforeBitmapRecycle?.invoke(frame)
        }.onFailure { err ->
            Log.e(TAG, "Downstream frame hook failed", err)
        }

        if (frame.frameIndex == 1L || frame.frameIndex % 60L == 0L) {
            Log.d(
                TAG,
                "SUBCAST_CAPTURE frame=${frame.frameIndex} res=${frame.width}x${frame.height} " +
                    "convMs=${frame.conversionTimeMs} fps=${"%.1f".format(lastMeasuredFps)} " +
                    "dropped=${frame.droppedFrames}"
            )
        }

        if (!frame.bitmap.isRecycled) {
            frame.bitmap.recycle()
        }
    }

    private fun createPreviewThumbnail(source: Bitmap): Bitmap {
        if (source.isRecycled) return source
        val maxTargetWidth = MAX_PREVIEW_WIDTH_PX
        if (source.width <= maxTargetWidth) {
            return source.copy(Bitmap.Config.ARGB_8888, false)
        }
        val ratio = maxTargetWidth.toFloat() / source.width.toFloat()
        val targetHeight = (source.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, maxTargetWidth, targetHeight, true)
    }

    fun stop() {
        workerJob?.cancel()
        workerJob = null
        val leftover = latestPendingFrame.getAndSet(null)
        if (leftover != null && !leftover.bitmap.isRecycled) {
            leftover.bitmap.recycle()
        }
    }

    fun release() {
        stop()
        scope.cancel()
    }

    companion object {
        private const val TAG = "FrameProcessor"
        private const val FPS_WINDOW_MS = 1000L
        private const val PREVIEW_THROTTLE_MS = 160L
        private const val MAX_PREVIEW_WIDTH_PX = 640
    }
}
