package com.example.subtitle

import android.graphics.Bitmap
import android.os.SystemClock
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RealMlKitOcrEngine : OcrEngine {

    private var recognizer: TextRecognizer? = null

    private fun getOrCreateRecognizer(): TextRecognizer {
        return recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            .also { recognizer = it }
    }

    override suspend fun recognize(roiBitmap: Bitmap): OcrEngineResult {
        if (roiBitmap.isRecycled || roiBitmap.width <= 0 || roiBitmap.height <= 0) {
            return OcrEngineResult.Failure("Geçersiz ROI bitmap")
        }

        val startNs = SystemClock.elapsedRealtimeNanos()
        return try {
            val client = getOrCreateRecognizer()
            val inputImage = InputImage.fromBitmap(roiBitmap, 0)
            val visionText = client.process(inputImage).awaitResult()
            val processingMs = ((SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000L)
                .coerceAtLeast(0L)

            val realConfidence = extractAverageConfidence(visionText)
            OcrEngineResult.Success(
                OcrOutput(
                    rawText = visionText.text,
                    confidence = realConfidence,
                    processingTimeMs = processingMs
                )
            )
        } catch (mlEx: MlKitException) {
            if (isModelUnavailableCode(mlEx.errorCode)) {
                OcrEngineResult.ModelUnavailable(
                    mlEx.localizedMessage ?: "ML Kit metin tanıma modeli henüz hazır değil."
                )
            } else {
                OcrEngineResult.Failure(
                    mlEx.localizedMessage ?: "ML Kit OCR hatası (kod: ${mlEx.errorCode})"
                )
            }
        } catch (t: Throwable) {
            OcrEngineResult.Failure(
                t.localizedMessage ?: "OCR işlemi başarısız oldu."
            )
        }
    }

    private fun extractAverageConfidence(visionText: Text): Float? {
        var sum = 0f
        var count = 0
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val conf = line.confidence
                if (conf.isFinite() && conf in 0f..1f) {
                    sum += conf
                    count++
                }
            }
        }
        return if (count > 0) (sum / count) else null
    }

    private fun isModelUnavailableCode(errorCode: Int): Boolean {
        return errorCode == MlKitException.UNAVAILABLE ||
            errorCode == MlKitException.NOT_FOUND ||
            errorCode == MlKitException.MODEL_INCOMPATIBLE_WITH_TFLITE
    }

    override fun close() {
        recognizer?.close()
        recognizer = null
    }

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result ->
            if (cont.isActive) cont.resume(result)
        }
        addOnFailureListener { exception ->
            if (cont.isActive) cont.resumeWithException(exception)
        }
        addOnCanceledListener {
            if (cont.isActive) cont.cancel()
        }
    }
}
