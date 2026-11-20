package com.example.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlePipelineUnitTest {

    private val normalizer = SubtitleTextNormalizer()

    @Test
    fun `two-line subtitle normalization joins lines cleanly`() {
        val normalized = normalizer.normalize("WE NEED TO GO\nNOW")
        assertEquals("WE NEED TO GO NOW", normalized)
    }

    @Test
    fun `whitespace normalization collapses extra spaces and preserves word spacing`() {
        val normalized = normalizer.normalize("   Where   are\t\tyou   going?  ")
        assertEquals("Where are you going?", normalized)
    }

    @Test
    fun `empty punctuation and single char OCR results are rejected`() {
        assertNull(normalizer.normalize(null))
        assertNull(normalizer.normalize(""))
        assertNull(normalizer.normalize("   \n  "))
        assertNull(normalizer.normalize("a"))
        assertNull(normalizer.normalize("..."))
        assertNull(normalizer.normalize("---!?"))
        assertNull(normalizer.normalize("|||///___"))
    }

    @Test
    fun `duplicate subtitle detection reports isNewText false on repeated text`() {
        val deduplicator = SubtitleDeduplicator()

        val first = deduplicator.evaluate(
            normalizedText = "WE NEED TO GO",
            confidence = 0.92f,
            timestampElapsedMs = 1_000L,
            sourceFrameIndex = 10L,
            processingTimeMs = 35L
        )
        val second = deduplicator.evaluate(
            normalizedText = "WE NEED TO GO",
            confidence = 0.91f,
            timestampElapsedMs = 1_150L,
            sourceFrameIndex = 11L,
            processingTimeMs = 32L
        )

        assertTrue(first.isNewText)
        assertFalse(second.isNewText)
        assertEquals("WE NEED TO GO", second.text)
    }

    @Test
    fun `changed subtitle detection reports isNewText true and preserves timestamp`() {
        val deduplicator = SubtitleDeduplicator()

        val first = deduplicator.evaluate(
            normalizedText = "WE NEED TO GO",
            confidence = null,
            timestampElapsedMs = 2_000L,
            sourceFrameIndex = 20L,
            processingTimeMs = 40L
        )
        val changed = deduplicator.evaluate(
            normalizedText = "WE NEED TO GO NOW",
            confidence = null,
            timestampElapsedMs = 2_180L,
            sourceFrameIndex = 21L,
            processingTimeMs = 38L
        )

        assertTrue(first.isNewText)
        assertTrue(changed.isNewText)
        assertEquals(2_180L, changed.timestampElapsedMs)
        assertEquals(21L, changed.sourceFrameIndex)
        assertNull(changed.confidence)
    }

    @Test
    fun `subtitle reappearing after clear is treated as new text`() {
        val deduplicator = SubtitleDeduplicator()

        val first = deduplicator.evaluate("HELLO WORLD", null, 100L, 1L, 20L)
        val second = deduplicator.evaluate("HELLO WORLD", null, 250L, 2L, 22L)
        assertEquals("HELLO WORLD", deduplicator.activeText())

        deduplicator.clear()
        assertNull(deduplicator.activeText())

        val third = deduplicator.evaluate("HELLO WORLD", null, 400L, 3L, 21L)

        assertTrue(first.isNewText)
        assertFalse(second.isNewText)
        assertTrue(third.isNewText)
    }

    @Test
    fun `invalid ROI is rejected cleanly without crashing`() {
        val inverted = SubtitleRoi(left = 0.8f, top = 0.2f, right = 0.3f, bottom = 0.9f)
        val outOfBounds = SubtitleRoi(left = -0.2f, top = 0.7f, right = 1.2f, bottom = 0.95f)
        val tooTiny = SubtitleRoi(left = 0.5f, top = 0.5f, right = 0.51f, bottom = 0.51f)
        val nanRoi = SubtitleRoi(left = Float.NaN, top = 0.7f, right = 0.9f, bottom = 0.9f)

        assertFalse(inverted.isValid())
        assertFalse(outOfBounds.isValid())
        assertFalse(tooTiny.isValid())
        assertFalse(nanRoi.isValid())

        val clamped = outOfBounds.clamped()
        assertTrue(clamped.isValid())
        assertEquals(0f, clamped.left, 0.0001f)
        assertEquals(1f, clamped.right, 0.0001f)

        val clampedTiny = tooTiny.clamped()
        assertTrue(clampedTiny.isValid())
        assertTrue(clampedTiny.width >= SubtitleRoi.MIN_NORMALIZED_WIDTH - 1e-4f)
        assertTrue(clampedTiny.height >= SubtitleRoi.MIN_NORMALIZED_HEIGHT - 1e-4f)
        assertNotNull(clampedTiny)
    }
}
