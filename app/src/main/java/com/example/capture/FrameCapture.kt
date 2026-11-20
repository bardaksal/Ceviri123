package com.example.capture

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class FrameCapture {

    private var handlerThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var reusableScratchBitmap: Bitmap? = null
    private var currentWidth: Int = 0
    private var currentHeight: Int = 0
    private var currentDensityDpi: Int = 0

    private val isProcessing = AtomicBoolean(false)
    private val isStopped = AtomicBoolean(true)
    private val frameCounter = AtomicLong(0L)
    private val droppedCounter = AtomicLong(0L)

    private var frameListener: ((CapturedFrame) -> Unit)? = null
    private var errorListener: ((String) -> Unit)? = null

    fun ensureHandler(): Handler {
        val existing = captureHandler
        if (existing != null && handlerThread?.isAlive == true) {
            return existing
        }
        val thread = HandlerThread("SubCastFrameCapture").apply { start() }
        handlerThread = thread
        return Handler(thread.looper).also { captureHandler = it }
    }

    fun start(
        mediaProjection: MediaProjection,
        width: Int,
        height: Int,
        densityDpi: Int,
        onFrameCaptured: (CapturedFrame) -> Unit,
        onError: (String) -> Unit
    ) {
        stopCaptureResourcesOnly()

        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        currentWidth = safeWidth
        currentHeight = safeHeight
        currentDensityDpi = densityDpi.coerceAtLeast(120)
        frameListener = onFrameCaptured
        errorListener = onError
        frameCounter.set(0L)
        droppedCounter.set(0L)
        isProcessing.set(false)
        isStopped.set(false)

        val handler = ensureHandler()
        val reader = createImageReader(safeWidth, safeHeight, handler)
        imageReader = reader

        virtualDisplay = mediaProjection.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            safeWidth,
            safeHeight,
            currentDensityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            object : VirtualDisplay.Callback() {
                override fun onStopped() {
                    Log.i(TAG, "VirtualDisplay stopped by system")
                }
            },
            handler
        )

        Log.i(TAG, "Started VirtualDisplay ${safeWidth}x${safeHeight} @ ${currentDensityDpi}dpi")
    }

    fun resize(width: Int, height: Int) {
        if (isStopped.get()) return
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        if (safeWidth == currentWidth && safeHeight == currentHeight) return

        val display = virtualDisplay ?: return
        val handler = captureHandler ?: return

        val oldReader = imageReader
        val newReader = createImageReader(safeWidth, safeHeight, handler)
        imageReader = newReader
        currentWidth = safeWidth
        currentHeight = safeHeight

        display.resize(safeWidth, safeHeight, currentDensityDpi)
        display.surface = newReader.surface
        oldReader?.close()

        Log.i(TAG, "Resized VirtualDisplay to ${safeWidth}x${safeHeight}")
    }

    private fun createImageReader(width: Int, height: Int, handler: Handler): ImageReader {
        return ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            MAX_IMAGES_LATEST_ONLY
        ).apply {
            setOnImageAvailableListener({ reader ->
                handleImageAvailable(reader)
            }, handler)
        }
    }

    private fun handleImageAvailable(reader: ImageReader) {
        if (isStopped.get()) {
            runCatching { reader.acquireLatestImage()?.close() }
            return
        }

        if (!isProcessing.compareAndSet(false, true)) {
            val dropped = runCatching { reader.acquireLatestImage() }.getOrNull()
            if (dropped != null) {
                dropped.close()
                droppedCounter.incrementAndGet()
            }
            return
        }

        var image: Image? = null
        try {
            image = reader.acquireLatestImage()
            if (image == null) {
                return
            }

            val startNs = SystemClock.elapsedRealtimeNanos()
            val timestampMs = SystemClock.elapsedRealtime()
            val plane = image.planes.firstOrNull() ?: return
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val imgWidth = image.width
            val imgHeight = image.height
            val rowPaddingBytes = (rowStride - pixelStride * imgWidth).coerceAtLeast(0)
            val rowPaddingPixels = if (pixelStride > 0) rowPaddingBytes / pixelStride else 0

            val usedHwBuffer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val hwBuf = image.hardwareBuffer
                val hasHw = hwBuf != null
                hwBuf?.close()
                hasHw
            } else {
                false
            }

            val scratchWidth = imgWidth + rowPaddingPixels
            val scratch = obtainReusableScratchBitmap(scratchWidth, imgHeight)
            buffer.rewind()
            scratch.copyPixelsFromBuffer(buffer)

            val outputBitmap = if (rowPaddingPixels == 0) {
                scratch.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                Bitmap.createBitmap(scratch, 0, 0, imgWidth, imgHeight)
            }

            val conversionMs = ((SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000L)
                .coerceAtLeast(0L)
            val index = frameCounter.incrementAndGet()

            frameListener?.invoke(
                CapturedFrame(
                    bitmap = outputBitmap,
                    frameIndex = index,
                    width = imgWidth,
                    height = imgHeight,
                    timestampElapsedMs = timestampMs,
                    conversionTimeMs = conversionMs,
                    droppedFrames = droppedCounter.get(),
                    usedHardwareBuffer = usedHwBuffer,
                    stridePaddingBytes = rowPaddingBytes
                )
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Frame capture error", t)
            errorListener?.invoke(t.message ?: "Ekran karesi okunurken hata oluştu.")
        } finally {
            image?.close()
            isProcessing.set(false)
        }
    }

    private fun obtainReusableScratchBitmap(width: Int, height: Int): Bitmap {
        val current = reusableScratchBitmap
        if (current != null && !current.isRecycled && current.width == width && current.height == height) {
            return current
        }
        current?.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            reusableScratchBitmap = it
        }
    }

    private fun stopCaptureResourcesOnly() {
        isStopped.set(true)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        reusableScratchBitmap?.recycle()
        reusableScratchBitmap = null
    }

    fun stop() {
        stopCaptureResourcesOnly()
        frameListener = null
        errorListener = null
        handlerThread?.quitSafely()
        handlerThread = null
        captureHandler = null
    }

    companion object {
        private const val TAG = "FrameCapture"
        private const val VIRTUAL_DISPLAY_NAME = "SubCastVirtualDisplay"
        private const val MAX_IMAGES_LATEST_ONLY = 2
    }
}
