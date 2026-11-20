package com.example.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.example.MainActivity
import com.example.R
import com.example.subtitle.OcrPipelineWorker
import com.example.subtitle.RealMlKitOcrEngine
import com.example.subtitle.RoiPreferencesRepository
import com.example.subtitle.SubtitleRoi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ScreenCaptureService : Service() {

    private lateinit var projectionController: MediaProjectionController
    private lateinit var roiRepository: RoiPreferencesRepository
    private val ocrEngine = RealMlKitOcrEngine()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var activeRoi: SubtitleRoi = SubtitleRoi.DEFAULT

    private lateinit var ocrPipelineWorker: OcrPipelineWorker
    private lateinit var frameProcessor: FrameProcessor
    private val frameCapture = FrameCapture()

    private var roiObserverJob: Job? = null
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null

    override fun onCreate() {
        super.onCreate()
        projectionController = MediaProjectionController(applicationContext)
        roiRepository = RoiPreferencesRepository(applicationContext)

        ocrPipelineWorker = OcrPipelineWorker(
            ocrEngine = ocrEngine,
            onStateChanged = { ocrState ->
                CaptureStateHolder.update { current ->
                    current.copy(ocrState = ocrState)
                }
            }
        )

        frameProcessor = FrameProcessor(
            onBeforeBitmapRecycle = { capturedFrame ->
                dispatchRoiToOcrIfReady(capturedFrame)
            }
        )

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAPTURE -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
                val resultData = IntentCompat.getParcelableExtra(
                    intent,
                    EXTRA_RESULT_DATA,
                    Intent::class.java
                )
                if (resultCode == Int.MIN_VALUE || resultData == null) {
                    reportError("MediaProjection izin verisi alınamadı.")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startCaptureSession(resultCode, resultData)
            }

            ACTION_STOP_CAPTURE -> {
                stopCaptureSession(stoppedBySystem = false)
                stopSelf()
            }

            else -> {
                Log.w(TAG, "Unknown or null action received: ${intent?.action}")
            }
        }
        return START_NOT_STICKY
    }

    private fun startCaptureSession(resultCode: Int, resultData: Intent) {
        try {
            // Android 14+ requires startForeground with MEDIA_PROJECTION type BEFORE getMediaProjection
            val notification = buildNotification()
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)

            CaptureStateHolder.update {
                it.copy(
                    status = CaptureStatus.STARTING,
                    errorMessage = null,
                    hardwareInfo = projectionController.inspectHardwareAcceleration()
                )
            }

            releaseProjectionObjects()
            observeRoiSettings()

            val projection = projectionController.acquireMediaProjection(resultCode, resultData)
            if (projection == null) {
                reportError("MediaProjection başlatılamadı (sistem null döndü).")
                stopForegroundAndSelf()
                return
            }

            val captureHandler = frameCapture.ensureHandler()
            val callback = projectionController.registerProjectionCallback(
                projection = projection,
                handler = captureHandler,
                onStopped = {
                    Log.i(TAG, "MediaProjection.Callback.onStop() invoked")
                    stopCaptureSession(stoppedBySystem = true)
                    stopSelf()
                },
                onResized = { newWidth, newHeight ->
                    frameCapture.resize(newWidth, newHeight)
                },
                onVisibilityChanged = { visible ->
                    CaptureStateHolder.update { it.copy(isContentVisible = visible) }
                }
            )

            mediaProjection = projection
            projectionCallback = callback

            val bounds = projectionController.queryMaximumDisplayBounds()
            ocrPipelineWorker.start()
            frameProcessor.start()
            frameCapture.start(
                mediaProjection = projection,
                width = bounds.width,
                height = bounds.height,
                densityDpi = bounds.densityDpi,
                onFrameCaptured = { frame ->
                    frameProcessor.submitFrame(frame)
                },
                onError = { message ->
                    reportError(message)
                }
            )

            CaptureStateHolder.update {
                it.copy(
                    status = CaptureStatus.ACTIVE,
                    errorMessage = null,
                    isContentVisible = true
                )
            }
            Log.i(TAG, "Screen capture session active (${bounds.width}x${bounds.height})")
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException starting MediaProjection", se)
            reportError("Ekran yakalama güvenlik izni reddedildi: ${se.localizedMessage ?: "SecurityException"}")
            stopForegroundAndSelf()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start screen capture", t)
            reportError("Ekran yakalama başlatılamadı: ${t.localizedMessage ?: t.javaClass.simpleName}")
            stopForegroundAndSelf()
        }
    }

    private fun observeRoiSettings() {
        roiObserverJob?.cancel()
        roiObserverJob = serviceScope.launch {
            roiRepository.settingsFlow.collect { settings ->
                val changed = activeRoi != settings.roi
                activeRoi = settings.roi
                ocrPipelineWorker.minIntervalMs = settings.ocrIntervalMs
                if (changed) {
                    ocrPipelineWorker.resetDeduplication()
                }
            }
        }
    }

    private fun dispatchRoiToOcrIfReady(frame: CapturedFrame) {
        if (!ocrPipelineWorker.shouldAcceptFrame(frame.timestampElapsedMs)) {
            return
        }
        val roi = activeRoi
        val cropped = roi.cropBitmap(frame.bitmap) ?: return
        ocrPipelineWorker.submitRoiBitmap(
            roiBitmap = cropped,
            frameIndex = frame.frameIndex,
            capturedElapsedMs = frame.timestampElapsedMs
        )
    }

    private fun stopCaptureSession(stoppedBySystem: Boolean) {
        releaseProjectionObjects()
        CaptureStateHolder.update { current ->
            current.copy(
                status = if (stoppedBySystem) CaptureStatus.STOPPED_BY_SYSTEM else CaptureStatus.IDLE,
                errorMessage = if (stoppedBySystem) {
                    "Ekran yakalama sistem veya durum çubuğu tarafından durduruldu."
                } else {
                    current.errorMessage
                }
            )
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun stopForegroundAndSelf() {
        releaseProjectionObjects()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseProjectionObjects() {
        roiObserverJob?.cancel()
        roiObserverJob = null
        frameCapture.stop()
        frameProcessor.stop()
        ocrPipelineWorker.stop()
        val projection = mediaProjection
        val callback = projectionCallback
        if (projection != null) {
            if (callback != null) {
                runCatching { projection.unregisterCallback(callback) }
            }
            runCatching { projection.stop() }
        }
        mediaProjection = null
        projectionCallback = null
    }

    private fun reportError(message: String) {
        CaptureStateHolder.update {
            it.copy(
                status = CaptureStatus.ERROR,
                errorMessage = message
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_capture_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_capture_desc)
                setShowBadge(false)
            }
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP_CAPTURE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notification_capture_title))
            .setContentText(getString(R.string.notification_capture_text))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentPendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.notification_action_stop),
                stopPendingIntent
            )
            .build()
    }

    override fun onDestroy() {
        releaseProjectionObjects()
        frameProcessor.release()
        ocrPipelineWorker.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "subcast_capture_channel"
        private const val NOTIFICATION_ID = 2048

        const val ACTION_START_CAPTURE = "com.example.capture.ACTION_START_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.example.capture.ACTION_STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        fun createStartIntent(context: Context, resultCode: Int, resultData: Intent): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START_CAPTURE
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
        }

        fun createStopIntent(context: Context): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP_CAPTURE
            }
        }
    }
}
