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
 * Tracks active normalized subtitle text across consecutive frames.
 * Lifecycle:
 * - NEW_SUBTITLE -> isNewText = true
 * - SAME_SUBTITLE -> isNewText = false
 * - SUBTITLE_DISAPPEARED -> clear()/reset() clears active text so reappearance is NEW_SUBTITLE
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
    fun activeText(): String? = lastNormalizedText

    @Synchronized
    fun clear() {
        lastNormalizedText = null
    }

    @Synchronized
    fun reset() {
        clear()
    }
}
