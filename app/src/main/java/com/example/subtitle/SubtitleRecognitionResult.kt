package com.example.subtitle

data class SubtitleRecognitionResult(
    val text: String,
    val confidence: Float?,
    val timestampElapsedMs: Long,
    val isNewText: Boolean,
    val sourceFrameIndex: Long,
    val processingTimeMs: Long
)

/**
 * Compares normalized OCR text across consecutive frames so static subtitles
 * produce a single subtitle event until the text genuinely changes.
 */
class SubtitleDeduplicator {

    private var lastNormalizedText: String? = null

    @Synchronized
    fun evaluate(
        normalizedText: String,
        confidence: Float?,
        timestampElapsedMs: Long,
        sourceFrameIndex: Long,
        processingTimeMs: Long
    ): SubtitleRecognitionResult {
        val isNew = normalizedText != lastNormalizedText
        if (isNew) {
            lastNormalizedText = normalizedText
        }
        return SubtitleRecognitionResult(
            text = normalizedText,
            confidence = confidence,
            timestampElapsedMs = timestampElapsedMs,
            isNewText = isNew,
            sourceFrameIndex = sourceFrameIndex,
            processingTimeMs = processingTimeMs
        )
    }

    @Synchronized
    fun reset() {
        lastNormalizedText = null
    }
}
