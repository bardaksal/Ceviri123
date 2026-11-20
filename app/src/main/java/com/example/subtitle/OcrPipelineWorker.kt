package com.example.subtitle

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.example.capture.OcrState
import com.example.capture.OcrStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class PendingOcrFrame(
    val roiBitmap: Bitmap,
    val frameIndex: Long,
    val capturedElapsedMs: Long
)

/**
 * Decoupled, backpressured latest-frame-only OCR pipeline worker.
 * Never blocks MediaProjection frame capture; processes at most 1 active OCR job
 * while replacing any older queued ROI bitmap.
 */
class OcrPipelineWorker(
    private val ocrEngine: OcrEngine,
    private val normalizer: SubtitleTextNormalizer = SubtitleTextNormalizer(),
    private val deduplicator: SubtitleDeduplicator = SubtitleDeduplicator(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val elapsedClockMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val onStateChanged: (OcrState) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val pendingSlot = AtomicReference<PendingOcrFrame?>(null)
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)

    private val isRunning = AtomicBoolean(false)
    private val lastScheduledMs = AtomicLong(0L)
    private val recognitionCounter = AtomicLong(0L)

    @Volatile
    var minIntervalMs: Long = SubtitleRoiSettings.DEFAULT_OCR_INTERVAL_MS

    @Volatile
    var minConfidenceThreshold: Float? = null

    private var workerJob: Job? = null
    private var currentState: OcrState = OcrState()
    private var lastLoggedStatus: OcrStatus? = null

    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            deduplicator.reset()
            lastScheduledMs.set(0L)
            recognitionCounter.set(0L)
            lastLoggedStatus = null
            publishState(
                OcrState(
                    status = OcrStatus.WAITING_FOR_FRAME,
                    engineLabel = "On-device ML Kit"
                )
            )

            workerJob = scope.launch {
                for (signal in wakeSignal) {
                    if (!isRunning.get()) break
                    val next = pendingSlot.getAndSet(null) ?: continue
                    processFrame(next)
                }
            }
        }
    }

    /**
     * Checks whether the OCR frequency limiter permits cropping and submitting a new frame.
     */
    fun shouldAcceptFrame(nowElapsedMs: Long = elapsedClockMs()): Boolean {
        if (!isRunning.get()) return false
        val previous = lastScheduledMs.get()
        if (previous == 0L || (nowElapsedMs - previous) >= minIntervalMs) {
            return lastScheduledMs.compareAndSet(previous, nowElapsedMs)
        }
        return false
    }

    /**
     * Submits an already-cropped ROI bitmap to the latest-frame-only slot.
     * Ownership of [roiBitmap] transfers to [OcrPipelineWorker], which recycles it after use.
     */
    fun submitRoiBitmap(roiBitmap: Bitmap, frameIndex: Long, capturedElapsedMs: Long) {
        if (!isRunning.get()) {
            if (!roiBitmap.isRecycled) roiBitmap.recycle()
            return
        }
        val replaced = pendingSlot.getAndSet(
            PendingOcrFrame(
                roiBitmap = roiBitmap,
                frameIndex = frameIndex,
                capturedElapsedMs = capturedElapsedMs
            )
        )
        if (replaced != null && !replaced.roiBitmap.isRecycled) {
            replaced.roiBitmap.recycle()
        }
        wakeSignal.trySend(Unit)
    }

    fun resetDeduplication() {
        deduplicator.reset()
    }

    private suspend fun processFrame(pending: PendingOcrFrame) {
        try {
            if (currentState.status == OcrStatus.WAITING_FOR_FRAME) {
                publishState(currentState.copy(status = OcrStatus.RUNNING))
            }

            when (val engineResult = ocrEngine.recognize(pending.roiBitmap)) {
                is OcrEngineResult.ModelUnavailable -> {
                    publishState(
                        currentState.copy(
                            status = OcrStatus.MODEL_UNAVAILABLE,
                            errorMessage = engineResult.reason
                        )
                    )
                    logStatusIfChanged(OcrStatus.MODEL_UNAVAILABLE, engineResult.reason)
                }

                is OcrEngineResult.Failure -> {
                    publishState(
                        currentState.copy(
                            status = OcrStatus.ERROR,
                            errorMessage = engineResult.reason
                        )
                    )
                    logStatusIfChanged(OcrStatus.ERROR, engineResult.reason)
                }

                is OcrEngineResult.Success -> {
                    handleSuccess(engineResult.output, pending)
                }
            }
        } finally {
            if (!pending.roiBitmap.isRecycled) {
                pending.roiBitmap.recycle()
            }
        }
    }

    private fun handleSuccess(output: OcrOutput, pending: PendingOcrFrame) {
        val threshold = minConfidenceThreshold
        if (threshold != null && output.confidence != null && output.confidence < threshold) {
            publishState(
                currentState.copy(
                    status = OcrStatus.READY,
                    processingTimeMs = output.processingTimeMs,
                    sourceFrameIndex = pending.frameIndex,
                    errorMessage = null
                )
            )
            return
        }

        val normalized = normalizer.normalize(output.rawText)
        if (normalized == null) {
            val hadActiveSubtitle = currentState.latestText != null
            deduplicator.clear()
            publishState(
                currentState.copy(
                    status = OcrStatus.READY,
                    latestText = null,
                    confidence = null,
                    lastRecognitionElapsedMs = null,
                    processingTimeMs = output.processingTimeMs,
                    sourceFrameIndex = pending.frameIndex,
                    isNewText = false,
                    errorMessage = null
                )
            )
            if (hadActiveSubtitle) {
                lastLoggedStatus = OcrStatus.READY
                Log.i(
                    TAG,
                    "status=READY event=SUBTITLE_DISAPPEARED processingMs=${output.processingTimeMs} " +
                        "frameIndex=${pending.frameIndex}"
                )
            } else {
                logStatusIfChanged(OcrStatus.READY, "no-text")
            }
            return
        }

        val recognition = deduplicator.evaluate(
            normalizedText = normalized,
            confidence = output.confidence,
            timestampElapsedMs = elapsedClockMs(),
            sourceFrameIndex = pending.frameIndex,
            processingTimeMs = output.processingTimeMs
        )

        val totalRecognitions = if (recognition.isNewText) {
            recognitionCounter.incrementAndGet()
        } else {
            recognitionCounter.get()
        }

        publishState(
            currentState.copy(
                status = OcrStatus.READY,
                latestText = recognition.text,
                confidence = recognition.confidence,
                lastRecognitionElapsedMs = recognition.timestampElapsedMs,
                processingTimeMs = recognition.processingTimeMs,
                sourceFrameIndex = recognition.sourceFrameIndex,
                recognitionCount = totalRecognitions,
                isNewText = recognition.isNewText,
                errorMessage = null
            )
        )

        if (recognition.isNewText) {
            lastLoggedStatus = OcrStatus.READY
            Log.i(
                TAG,
                "status=READY text=\"${recognition.text}\" processingMs=${recognition.processingTimeMs} " +
                    "frameIndex=${recognition.sourceFrameIndex} isNewText=true"
            )
        }
    }

    private fun logStatusIfChanged(status: OcrStatus, detail: String) {
        if (lastLoggedStatus != status) {
            lastLoggedStatus = status
            Log.i(TAG, "status=$status detail=\"$detail\"")
        }
    }

    private fun publishState(newState: OcrState) {
        currentState = newState
        onStateChanged(newState)
    }

    fun stop() {
        isRunning.set(false)
        workerJob?.cancel()
        workerJob = null
        val leftover = pendingSlot.getAndSet(null)
        if (leftover != null && !leftover.roiBitmap.isRecycled) {
            leftover.roiBitmap.recycle()
        }
        deduplicator.clear()
        lastScheduledMs.set(0L)
        lastLoggedStatus = null
        publishState(
            currentState.copy(
                status = OcrStatus.IDLE,
                latestText = null,
                confidence = null,
                lastRecognitionElapsedMs = null,
                isNewText = false,
                errorMessage = null
            )
        )
    }

    fun release() {
        stop()
        scope.cancel()
        ocrEngine.close()
    }

    companion object {
        private const val TAG = "SUBCAST_OCR"
    }
}
