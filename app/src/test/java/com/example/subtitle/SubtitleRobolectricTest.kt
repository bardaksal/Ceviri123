package com.example.subtitle

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.capture.CaptureStateHolder
import com.example.capture.CaptureStatus
import com.example.capture.OcrState
import com.example.capture.OcrStatus
import com.example.capture.ScreenCaptureService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SubtitleRobolectricTest {

    @Test
    fun `roi normalized to pixel conversion and resize recalculation`() {
        val roi = SubtitleRoi(left = 0.10f, top = 0.75f, right = 0.90f, bottom = 0.95f)

        val rect1080p = roi.toPixelRectOrNull(1920, 1080)
        assertNotNull(rect1080p)
        assertEquals(192, rect1080p!!.left)
        assertEquals(810, rect1080p.top)
        assertEquals(1728, rect1080p.right)
        assertEquals(1026, rect1080p.bottom)

        // Screen resize (e.g. 1920x1080 -> 1280x720) recalculates proportionally
        val rect720p = roi.toPixelRectOrNull(1280, 720)
        assertNotNull(rect720p)
        assertEquals(128, rect720p!!.left)
        assertEquals(540, rect720p.top)
        assertEquals(1152, rect720p.right)
        assertEquals(684, rect720p.bottom)

        // Invalid dimensions or invalid ROI yield null safely
        assertNull(roi.toPixelRectOrNull(0, 720))
        assertNull(SubtitleRoi(0.9f, 0.9f, 0.2f, 0.1f).toPixelRectOrNull(1920, 1080))
    }

    @Test
    fun `roi crops only subtitle slice bitmap`() {
        val fullBitmap = Bitmap.createBitmap(1000, 500, Bitmap.Config.ARGB_8888)
        val roi = SubtitleRoi(left = 0.10f, top = 0.80f, right = 0.90f, bottom = 0.96f)

        val cropped = roi.cropBitmap(fullBitmap)
        assertNotNull(cropped)
        assertEquals(800, cropped!!.width)
        assertEquals(80, cropped.height)

        cropped.recycle()
        fullBitmap.recycle()
    }

    @Test
    fun `roi preferences repository persists normalized ROI in DataStore`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = RoiPreferencesRepository(context)
        val custom = SubtitleRoi(left = 0.15f, top = 0.70f, right = 0.85f, bottom = 0.92f, referenceAspectRatio = 16f / 9f)

        repo.saveRoi(custom)
        val loaded = repo.settingsFlow.first()

        assertTrue(loaded.isUserConfigured)
        assertEquals(0.15f, loaded.roi.left, 0.001f)
        assertEquals(0.70f, loaded.roi.top, 0.001f)
        assertEquals(0.85f, loaded.roi.right, 0.001f)
        assertEquals(0.92f, loaded.roi.bottom, 0.001f)
    }

    @Test
    fun `ocr worker applies latest-frame-only backpressure and records timestamp`() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val processedFrameIndices = mutableListOf<Long>()

        val fakeEngine = object : OcrEngine {
            override suspend fun recognize(roiBitmap: Bitmap): OcrEngineResult {
                if (processedFrameIndices.isEmpty()) {
                    processedFrameIndices.add(roiBitmap.width.toLong())
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                    return OcrEngineResult.Success(
                        OcrOutput(rawText = "FIRST LINE", confidence = null, processingTimeMs = 25L)
                    )
                }
                processedFrameIndices.add(roiBitmap.width.toLong())
                return OcrEngineResult.Success(
                    OcrOutput(rawText = "LATEST\nSUBTITLE", confidence = 0.94f, processingTimeMs = 19L)
                )
            }

            override fun close() {}
        }

        var fakeClockMs = 5_000L
        val states = mutableListOf<OcrState>()
        val worker = OcrPipelineWorker(
            ocrEngine = fakeEngine,
            elapsedClockMs = { fakeClockMs },
            onStateChanged = { state -> synchronized(states) { states.add(state) } }
        )
        worker.start()

        // Submit frame 1 (width 100) and wait until engine is busy inside recognize()
        worker.submitRoiBitmap(Bitmap.createBitmap(100, 40, Bitmap.Config.ARGB_8888), 1L, fakeClockMs)
        firstStarted.await()

        // While engine is busy, submit frames 2..5 (widths 200..500); only latest (500) should survive
        val b2 = Bitmap.createBitmap(200, 40, Bitmap.Config.ARGB_8888)
        val b3 = Bitmap.createBitmap(300, 40, Bitmap.Config.ARGB_8888)
        val b4 = Bitmap.createBitmap(400, 40, Bitmap.Config.ARGB_8888)
        val b5 = Bitmap.createBitmap(500, 40, Bitmap.Config.ARGB_8888)

        worker.submitRoiBitmap(b2, 2L, fakeClockMs)
        worker.submitRoiBitmap(b3, 3L, fakeClockMs)
        worker.submitRoiBitmap(b4, 4L, fakeClockMs)
        worker.submitRoiBitmap(b5, 5L, fakeClockMs)

        // Replaced pending bitmaps should be recycled immediately
        assertTrue(b2.isRecycled)
        assertTrue(b3.isRecycled)
        assertTrue(b4.isRecycled)

        fakeClockMs = 5_240L
        releaseFirst.complete(Unit)

        var attempts = 0
        while (synchronized(states) { states.lastOrNull()?.latestText != "LATEST SUBTITLE" } && attempts < 40) {
            delay(25L)
            attempts++
        }

        val latestState = synchronized(states) { states.last() }
        assertEquals(listOf(100L, 500L), processedFrameIndices)
        assertEquals("LATEST SUBTITLE", latestState.latestText)
        assertEquals(5_240L, latestState.lastRecognitionElapsedMs)
        assertEquals(5L, latestState.sourceFrameIndex)
        assertEquals(OcrStatus.READY, latestState.status)

        worker.release()
    }

    @Test
    fun `stopping worker and ScreenCaptureService stops OCR cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        CaptureStateHolder.update {
            it.copy(
                status = CaptureStatus.ACTIVE,
                ocrState = OcrState(status = OcrStatus.READY, latestText = "TEST LINE")
            )
        }

        val stopIntent = Intent(context, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP_CAPTURE
        }
        val controller = Robolectric.buildService(ScreenCaptureService::class.java, stopIntent)
            .create()
            .startCommand(0, 0)

        val session = CaptureStateHolder.state.value
        assertEquals(CaptureStatus.IDLE, session.status)
        assertEquals(OcrStatus.IDLE, session.ocrState.status)

        controller.destroy()
    }
}
