package com.example

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.capture.CaptureStateHolder
import com.example.capture.CaptureStatus
import com.example.capture.CapturedFrame
import com.example.capture.FrameProcessor
import com.example.capture.MediaProjectionController
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("SubCast TR", appName)
    }

    @Test
    fun `controller inspects hardware acceleration and display bounds`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = MediaProjectionController(context)
        val hwInfo = controller.inspectHardwareAcceleration()
        val bounds = controller.queryMaximumDisplayBounds()

        assertTrue(hwInfo.apiLevel >= 24)
        assertTrue(bounds.width > 0)
        assertTrue(bounds.height > 0)
        assertTrue(bounds.densityDpi > 0)
    }

    @Test
    fun `frame processor updates telemetry state and generates preview`() = runBlocking {
        CaptureStateHolder.update {
            it.copy(status = CaptureStatus.IDLE, telemetry = null, previewBitmap = null)
        }

        val processor = FrameProcessor()
        processor.start()

        val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        processor.submitFrame(
            CapturedFrame(
                bitmap = bitmap,
                frameIndex = 1L,
                width = 1280,
                height = 720,
                timestampElapsedMs = 100L,
                conversionTimeMs = 4L,
                droppedFrames = 0L,
                usedHardwareBuffer = true,
                stridePaddingBytes = 0
            )
        )

        var attempts = 0
        while (CaptureStateHolder.state.value.telemetry == null && attempts < 20) {
            delay(25L)
            attempts++
        }

        val state = CaptureStateHolder.state.value
        assertEquals(CaptureStatus.ACTIVE, state.status)
        assertNotNull(state.telemetry)
        assertEquals(1280, state.telemetry?.width)
        assertEquals(720, state.telemetry?.height)
        assertNotNull(state.previewBitmap)

        processor.release()
    }
}
