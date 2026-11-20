package com.example.subtitle

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.runtime.Immutable
import kotlin.math.roundToInt

/**
 * Normalized subtitle Region of Interest in [0.0, 1.0] coordinates.
 * Resolution-independent so ROI remains valid across 720p, 1080p, 1440p, or rotation changes.
 */
@Immutable
data class SubtitleRoi(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val referenceAspectRatio: Float? = null
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun isValid(): Boolean {
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) {
            return false
        }
        if (left < 0f || top < 0f || right > 1f || bottom > 1f) {
            return false
        }
        if (width < MIN_NORMALIZED_WIDTH - FLOAT_EPSILON || height < MIN_NORMALIZED_HEIGHT - FLOAT_EPSILON) {
            return false
        }
        return true
    }

    /**
     * Clamps coordinates safely inside [0.0, 1.0] and guarantees minimum dimensions.
     */
    fun clamped(): SubtitleRoi {
        val safeLeft = (if (left.isFinite()) left else DEFAULT.left).coerceIn(0f, 1f - MIN_NORMALIZED_WIDTH)
        val safeTop = (if (top.isFinite()) top else DEFAULT.top).coerceIn(0f, 1f - MIN_NORMALIZED_HEIGHT)
        val safeRight = (if (right.isFinite()) right else DEFAULT.right)
            .coerceIn(safeLeft + MIN_NORMALIZED_WIDTH, 1f)
        val safeBottom = (if (bottom.isFinite()) bottom else DEFAULT.bottom)
            .coerceIn(safeTop + MIN_NORMALIZED_HEIGHT, 1f)

        val finalLeft = (safeRight - MIN_NORMALIZED_WIDTH).coerceAtMost(safeLeft).coerceAtLeast(0f)
        val finalTop = (safeBottom - MIN_NORMALIZED_HEIGHT).coerceAtMost(safeTop).coerceAtLeast(0f)

        return SubtitleRoi(
            left = finalLeft,
            top = finalTop,
            right = safeRight,
            bottom = safeBottom,
            referenceAspectRatio = referenceAspectRatio?.takeIf { it.isFinite() && it > 0f }
        )
    }

    /**
     * Converts normalized ROI to integer pixel Rect for the given frame dimensions.
     * Returns null when frame dimensions or ROI coordinates are invalid.
     */
    fun toPixelRectOrNull(frameWidth: Int, frameHeight: Int): Rect? {
        if (frameWidth <= 0 || frameHeight <= 0 || !isValid()) {
            return null
        }
        val pxLeft = (left * frameWidth).roundToInt().coerceIn(0, frameWidth - 1)
        val pxTop = (top * frameHeight).roundToInt().coerceIn(0, frameHeight - 1)
        val pxRight = (right * frameWidth).roundToInt().coerceIn(pxLeft + 1, frameWidth)
        val pxBottom = (bottom * frameHeight).roundToInt().coerceIn(pxTop + 1, frameHeight)

        if (pxRight <= pxLeft || pxBottom <= pxTop) {
            return null
        }
        return Rect(pxLeft, pxTop, pxRight, pxBottom)
    }

    /**
     * Crops only the subtitle region from [source]. Returns null on invalid ROI or recycled bitmap.
     */
    fun cropBitmap(source: Bitmap): Bitmap? {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) {
            return null
        }
        val rect = toPixelRectOrNull(source.width, source.height) ?: return null
        return try {
            Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height())
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    companion object {
        const val MIN_NORMALIZED_WIDTH = 0.08f
        const val MIN_NORMALIZED_HEIGHT = 0.04f
        private const val FLOAT_EPSILON = 1e-4f

        val DEFAULT = SubtitleRoi(
            left = 0.10f,
            top = 0.72f,
            right = 0.90f,
            bottom = 0.94f,
            referenceAspectRatio = 16f / 9f
        )
    }
}
