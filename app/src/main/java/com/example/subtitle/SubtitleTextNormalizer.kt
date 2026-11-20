package com.example.subtitle

/**
 * Cleans raw ML Kit OCR output into reliable single-line subtitle text
 * while rejecting empty, punctuation-only, single-character, or noisy artifacts.
 */
class SubtitleTextNormalizer {

    fun normalize(rawText: String?): String? {
        if (rawText.isNullOrBlank()) return null

        val cleaned = rawText
            .replace("\u200B", "")
            .replace("\uFEFF", "")
            .replace(LINE_BREAK_REGEX, " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()

        if (cleaned.length < MIN_SUBTITLE_LENGTH) {
            return null
        }

        var letterCount = 0
        var alnumCount = 0
        var nonSpaceCount = 0

        for (ch in cleaned) {
            if (!ch.isWhitespace()) {
                nonSpaceCount++
                if (ch.isLetter()) letterCount++
                if (ch.isLetterOrDigit()) alnumCount++
            }
        }

        // Reject punctuation-only, symbol-only, or single-letter OCR noise
        if (letterCount < MIN_LETTER_COUNT || nonSpaceCount == 0) {
            return null
        }

        // Reject outputs dominated by non-alphanumeric symbol noise (< 35% alphanumeric)
        if (alnumCount * 100 < nonSpaceCount * MIN_ALNUM_PERCENT) {
            return null
        }

        return cleaned
    }

    companion object {
        private const val MIN_SUBTITLE_LENGTH = 2
        private const val MIN_LETTER_COUNT = 2
        private const val MIN_ALNUM_PERCENT = 35

        private val LINE_BREAK_REGEX = Regex("[\\r\\n]+")
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}
