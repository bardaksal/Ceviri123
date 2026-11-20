package com.example.subtitle

import android.graphics.Bitmap

data class OcrOutput(
    val rawText: String,
    val confidence: Float?,
    val processingTimeMs: Long
)

sealed class OcrEngineResult {
    data class Success(val output: OcrOutput) : OcrEngineResult()
    data class ModelUnavailable(val reason: String) : OcrEngineResult()
    data class Failure(val reason: String) : OcrEngineResult()
}

interface OcrEngine {
    suspend fun recognize(roiBitmap: Bitmap): OcrEngineResult
    fun close()
}
