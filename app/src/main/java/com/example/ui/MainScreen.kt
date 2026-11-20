package com.example.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.LockClock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.capture.CaptureStatus
import com.example.capture.FrameTelemetry
import com.example.capture.OcrState
import com.example.capture.OcrStatus
import com.example.capture.SpeechState
import com.example.capture.TranslationState
import com.example.speech.SpeechDiagnosticCategory
import com.example.speech.SpeechDiagnosticReport
import com.example.speech.SpeechModelState
import com.example.speech.SpeechStatus
import com.example.subtitle.SubtitleRoi
import com.example.subtitle.SubtitleRoiSettings
import com.example.translation.TranslationModelState
import com.example.translation.TranslationStatus
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElectricCyanDim
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.SignalAmber
import com.example.ui.theme.SignalAmberDim
import com.example.ui.theme.SignalCoral
import com.example.ui.theme.SignalCoralDim
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.SignalEmeraldDim
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.SlateSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun MainScreen(
    uiState: CaptureDashboardUiState,
    onStartCaptureClick: () -> Unit,
    onStopCaptureClick: () -> Unit,
    onOpenRoiEditorClick: () -> Unit,
    onUpdateOcrIntervalMs: (Long) -> Unit,
    onPrepareTranslationClick: () -> Unit,
    onPrepareSpeechClick: () -> Unit,
    onTestVoiceClick: () -> Unit,
    onLaunchRemotePlayClick: () -> Unit,
    onRefreshChecksClick: () -> Unit,
    onDismissErrorClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isCaptureActive = uiState.session.status == CaptureStatus.ACTIVE ||
        uiState.session.status == CaptureStatus.STARTING

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBackground),
        containerColor = ObsidianBackground,
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                ElectricCyan.copy(alpha = 0.08f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * 0.15f, size.height * 0.06f),
                            radius = size.maxDimension * 0.55f
                        )
                    )
                }
        ) {
            val isExpanded = maxWidth >= 720.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                TopHeaderBar(
                    captureStatus = uiState.session.status,
                    onRefreshChecksClick = onRefreshChecksClick
                )

                AnimatedVisibility(
                    visible = !uiState.session.errorMessage.isNullOrBlank(),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    ErrorBanner(
                        message = uiState.session.errorMessage.orEmpty(),
                        onDismiss = onDismissErrorClick
                    )
                }

                if (isExpanded) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CaptureActionCard(
                                isCaptureActive = isCaptureActive,
                                status = uiState.session.status,
                                isMediaProjectionSupported = uiState.environment.isMediaProjectionSupported,
                                onStartCaptureClick = onStartCaptureClick,
                                onStopCaptureClick = onStopCaptureClick
                            )
                            SubtitleOcrResultCard(
                                ocrState = uiState.session.ocrState,
                                isCaptureActive = isCaptureActive
                            )
                            TranslationCard(
                                translationState = uiState.session.translationState,
                                onPrepareTranslationClick = onPrepareTranslationClick
                            )
                            SpeechCard(
                                speechState = uiState.session.speechState,
                                onPrepareSpeechClick = onPrepareSpeechClick,
                                onTestVoiceClick = onTestVoiceClick
                            )
                            SystemReadinessCard(
                                uiState = uiState,
                                onLaunchRemotePlayClick = onLaunchRemotePlayClick
                            )
                        }

                        Column(
                            modifier = Modifier.weight(1.15f),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            LiveFramePreviewCard(
                                uiState = uiState,
                                onOpenRoiEditorClick = onOpenRoiEditorClick
                            )
                            CaptureAndOcrRateCard(
                                telemetry = uiState.session.telemetry,
                                ocrIntervalMs = uiState.roiSettings.ocrIntervalMs,
                                onUpdateOcrIntervalMs = onUpdateOcrIntervalMs
                            )
                            PipelineRoadmapCard()
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 620.dp)
                            .align(Alignment.CenterHorizontally)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CaptureActionCard(
                            isCaptureActive = isCaptureActive,
                            status = uiState.session.status,
                            isMediaProjectionSupported = uiState.environment.isMediaProjectionSupported,
                            onStartCaptureClick = onStartCaptureClick,
                            onStopCaptureClick = onStopCaptureClick
                        )
                        SubtitleOcrResultCard(
                            ocrState = uiState.session.ocrState,
                            isCaptureActive = isCaptureActive
                        )
                        TranslationCard(
                            translationState = uiState.session.translationState,
                            onPrepareTranslationClick = onPrepareTranslationClick
                        )
                        SpeechCard(
                            speechState = uiState.session.speechState,
                            onPrepareSpeechClick = onPrepareSpeechClick,
                            onTestVoiceClick = onTestVoiceClick
                        )
                        LiveFramePreviewCard(
                            uiState = uiState,
                            onOpenRoiEditorClick = onOpenRoiEditorClick
                        )
                        CaptureAndOcrRateCard(
                            telemetry = uiState.session.telemetry,
                            ocrIntervalMs = uiState.roiSettings.ocrIntervalMs,
                            onUpdateOcrIntervalMs = onUpdateOcrIntervalMs
                        )
                        SystemReadinessCard(
                            uiState = uiState,
                            onLaunchRemotePlayClick = onLaunchRemotePlayClick
                        )
                        PipelineRoadmapCard()
                    }
                }
            }
        }
    }
}

@Composable
private fun TopHeaderBar(
    captureStatus: CaptureStatus,
    onRefreshChecksClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "SubCast TR",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = ElectricCyanDim,
                    border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = "PHASE 5 • SESLİ ÇEVİRİ",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "PS5 Remote Play • ROI, OCR, EN→TR Çeviri & Türkçe Ses",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusBadgePill(status = captureStatus)
            IconButton(
                onClick = onRefreshChecksClick,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("refresh_environment_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Sistem durumunu yenile",
                    tint = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun StatusBadgePill(status: CaptureStatus) {
    val (label, dotColor, bgColor) = when (status) {
        CaptureStatus.ACTIVE -> Triple("CAPTURE: ON", SignalEmerald, SignalEmeraldDim)
        CaptureStatus.STARTING -> Triple("STARTING", SignalAmber, SignalAmberDim)
        CaptureStatus.STOPPED_BY_SYSTEM -> Triple("STOPPED", SignalAmber, SignalAmberDim)
        CaptureStatus.ERROR -> Triple("ERROR", SignalCoral, SignalCoralDim)
        CaptureStatus.IDLE -> Triple("CAPTURE: OFF", TextMuted, SlateSurfaceElevated)
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = bgColor,
        border = BorderStroke(1.dp, dotColor.copy(alpha = 0.5f)),
        modifier = Modifier.testTag("capture_status_pill")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = TextPrimary
            )
        }
    }
}

@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = SignalCoralDim,
        border = BorderStroke(1.dp, SignalCoral.copy(alpha = 0.6f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("error_banner")
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = "Hata bildirimi",
                tint = SignalCoral
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("dismiss_error_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Hatayı kapat",
                    tint = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun CaptureActionCard(
    isCaptureActive: Boolean,
    status: CaptureStatus,
    isMediaProjectionSupported: Boolean,
    onStartCaptureClick: () -> Unit,
    onStopCaptureClick: () -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        border = BorderStroke(
            width = 1.dp,
            color = if (isCaptureActive) ElectricCyan.copy(alpha = 0.55f) else SlateBorder
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Monitor,
                    contentDescription = "Ekran yakalama kontrolü",
                    tint = if (isCaptureActive) ElectricCyan else TextSecondary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isCaptureActive) {
                            "Canlı Altyazı Yakalama Aktif"
                        } else {
                            "Ekran Yakalamayı Başlatın"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = when (status) {
                            CaptureStatus.ACTIVE ->
                                "PS Remote Play'e geçin; seçili ROI alanı on-device ML Kit ile okunuyor."
                            CaptureStatus.STARTING ->
                                "MediaProjection servisi ve OCR hattı başlatılıyor..."
                            CaptureStatus.STOPPED_BY_SYSTEM ->
                                "Yakalama sistem tarafından sonlandırıldı."
                            else ->
                                "Remote Play görüntüsünü okumak için MediaProjection izni verin."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }

            Button(
                onClick = {
                    if (isCaptureActive) onStopCaptureClick() else onStartCaptureClick()
                },
                enabled = isMediaProjectionSupported && status != CaptureStatus.STARTING,
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isCaptureActive) SignalCoral else ElectricCyan,
                    contentColor = ObsidianBackground
                ),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("toggle_capture_button")
            ) {
                Icon(
                    imageVector = if (isCaptureActive) {
                        Icons.Filled.StopScreenShare
                    } else {
                        Icons.Filled.ScreenShare
                    },
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (isCaptureActive) {
                        "Yakalamayı Durdur"
                    } else {
                        "Ekran Yakalamayı Başlat"
                    },
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun SubtitleOcrResultCard(
    ocrState: OcrState,
    isCaptureActive: Boolean
) {
    var nowElapsedMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    LaunchedEffect(isCaptureActive, ocrState.lastRecognitionElapsedMs) {
        nowElapsedMs = SystemClock.elapsedRealtime()
        while (isCaptureActive && ocrState.lastRecognitionElapsedMs != null) {
            delay(500L)
            nowElapsedMs = SystemClock.elapsedRealtime()
        }
    }

    val statusColor = when (ocrState.status) {
        OcrStatus.READY -> SignalEmerald
        OcrStatus.RUNNING -> ElectricCyan
        OcrStatus.WAITING_FOR_FRAME -> SignalAmber
        OcrStatus.MODEL_UNAVAILABLE, OcrStatus.ERROR -> SignalCoral
        OcrStatus.IDLE -> TextMuted
    }

    val statusText = when (ocrState.status) {
        OcrStatus.IDLE -> "IDLE"
        OcrStatus.WAITING_FOR_FRAME -> "WAITING FOR FRAME"
        OcrStatus.RUNNING -> "RUNNING"
        OcrStatus.READY -> "READY"
        OcrStatus.MODEL_UNAVAILABLE -> "MODEL UNAVAILABLE"
        OcrStatus.ERROR -> "ERROR"
    }

    val ageText = ocrState.lastRecognitionElapsedMs?.let { lastMs ->
        val diff = (nowElapsedMs - lastMs).coerceAtLeast(0L)
        if (diff < 1000L) "$diff ms" else "${"%.1f".format(diff / 1000f)} sn"
    } ?: "—"

    val confidenceText = ocrState.confidence?.let { conf ->
        "${(conf * 100f).roundToInt()}%"
    } ?: "UNKNOWN"

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        border = BorderStroke(
            1.dp,
            if (ocrState.status == OcrStatus.READY && !ocrState.latestText.isNullOrBlank()) {
                SignalEmerald.copy(alpha = 0.55f)
            } else {
                SlateBorder
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ocr_result_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Subtitles,
                        contentDescription = "Altyazı OCR durumu",
                        tint = statusColor
                    )
                    Text(
                        text = "ALTYAZI OCR",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = SlateSurfaceElevated,
                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.55f)),
                    modifier = Modifier.testTag("ocr_status_badge")
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Surface(
                shape = MaterialTheme.shapes.medium,
                color = ObsidianBackground,
                border = BorderStroke(1.dp, SlateBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "SON ALGILANAN İNGİLİZCE METİN",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    Text(
                        text = ocrState.latestText ?: "Henüz altyazı algılanmadı",
                        style = MaterialTheme.typography.titleLarge,
                        color = if (ocrState.latestText != null) TextPrimary else TextMuted,
                        modifier = Modifier.testTag("ocr_latest_text")
                    )
                    if (!ocrState.errorMessage.isNullOrBlank()) {
                        Text(
                            text = ocrState.errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = SignalCoral
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TelemetryMetricTile(
                    label = "OCR SÜRESİ",
                    value = ocrState.processingTimeMs?.let { "$it ms" } ?: "—",
                    accentColor = ElectricCyan,
                    modifier = Modifier.weight(1f)
                )
                TelemetryMetricTile(
                    label = "SON GÜNCELLEME",
                    value = ageText,
                    accentColor = SignalEmerald,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TelemetryMetricTile(
                    label = "CONFIDENCE",
                    value = confidenceText,
                    accentColor = if (ocrState.confidence != null) TextPrimary else TextSecondary,
                    modifier = Modifier.weight(1f)
                )
                TelemetryMetricTile(
                    label = "MOTOR",
                    value = ocrState.engineLabel,
                    accentColor = TextSecondary,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun TranslationCard(
    translationState: TranslationState,
    onPrepareTranslationClick: () -> Unit
) {
    val statusColor = when (translationState.status) {
        TranslationStatus.ACTIVE, TranslationStatus.READY -> SignalEmerald
        TranslationStatus.TRANSLATING -> ElectricCyan
        TranslationStatus.DOWNLOADING, TranslationStatus.MODEL_NOT_READY -> SignalAmber
        TranslationStatus.ERROR -> SignalCoral
    }

    val statusText = when (translationState.status) {
        TranslationStatus.MODEL_NOT_READY -> "NOT READY"
        TranslationStatus.DOWNLOADING -> "DOWNLOADING"
        TranslationStatus.READY -> "READY"
        TranslationStatus.TRANSLATING -> "TRANSLATING"
        TranslationStatus.ACTIVE -> "ACTIVE"
        TranslationStatus.ERROR -> "ERROR"
    }

    val modelBadgeText = when (translationState.modelState) {
        TranslationModelState.NOT_READY -> "Model: NOT READY"
        TranslationModelState.DOWNLOADING -> "Model: DOWNLOADING"
        TranslationModelState.READY -> "Model: READY"
        TranslationModelState.ERROR -> "Model: ERROR"
    }

    val modelBadgeColor = when (translationState.modelState) {
        TranslationModelState.READY -> SignalEmerald
        TranslationModelState.DOWNLOADING -> ElectricCyan
        TranslationModelState.NOT_READY -> SignalAmber
        TranslationModelState.ERROR -> SignalCoral
    }

    val modelHelperText = when (translationState.modelState) {
        TranslationModelState.DOWNLOADING -> "English → Turkish model hazırlanıyor..."
        TranslationModelState.READY -> "English → Turkish hazır"
        TranslationModelState.ERROR -> "Model hazırlama başarısız oldu"
        TranslationModelState.NOT_READY -> "İlk kullanım için EN → TR çeviri modeli indirilmeli"
    }

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        border = BorderStroke(
            width = 1.dp,
            color = if (translationState.status == TranslationStatus.ACTIVE &&
                !translationState.translatedText.isNullOrBlank()
            ) {
                SignalEmerald.copy(alpha = 0.55f)
            } else {
                SlateBorder
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("translation_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Translate,
                        contentDescription = "Türkçe çeviri durumu",
                        tint = statusColor
                    )
                    Text(
                        text = "TÜRKÇE ÇEVİRİ (EN → TR)",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = SlateSurfaceElevated,
                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.55f)),
                    modifier = Modifier.testTag("translation_status_badge")
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = SlateSurfaceElevated,
                    border = BorderStroke(1.dp, modelBadgeColor.copy(alpha = 0.45f)),
                    modifier = Modifier.testTag("translation_model_badge")
                ) {
                    Text(
                        text = modelBadgeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = modelBadgeColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = modelHelperText,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            Surface(
                shape = MaterialTheme.shapes.medium,
                color = ObsidianBackground,
                border = BorderStroke(1.dp, SlateBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "TÜRKÇE ALTYAZI ÇIKTISI",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    Text(
                        text = when {
                            !translationState.translatedText.isNullOrBlank() ->
                                translationState.translatedText
                            translationState.isTranslating ->
                                "Çevriliyor..."
                            translationState.modelState == TranslationModelState.NOT_READY ->
                                "Model henüz hazırlanmadı"
                            else ->
                                "Henüz çeviri yok"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = if (!translationState.translatedText.isNullOrBlank()) {
                            TextPrimary
                        } else {
                            TextMuted
                        },
                        modifier = Modifier.testTag("translation_result_text")
                    )
                    if (!translationState.errorMessage.isNullOrBlank()) {
                        Text(
                            text = translationState.errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = SignalCoral,
                            modifier = Modifier.testTag("translation_error_text")
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TelemetryMetricTile(
                    label = "ÇEVİRİ SÜRESİ",
                    value = translationState.processingTimeMs?.let { "$it ms" } ?: "—",
                    accentColor = ElectricCyan,
                    modifier = Modifier.weight(1f)
                )
                TelemetryMetricTile(
                    label = "MOTOR",
                    value = translationState.engineLabel,
                    accentColor = TextSecondary,
                    modifier = Modifier.weight(1f)
                )
            }

            if (translationState.modelState != TranslationModelState.READY) {
                Button(
                    onClick = onPrepareTranslationClick,
                    enabled = translationState.modelState != TranslationModelState.DOWNLOADING,
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricCyan,
                        contentColor = ObsidianBackground,
                        disabledContainerColor = SlateSurfaceElevated,
                        disabledContentColor = TextMuted
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("prepare_translation_button")
                ) {
                    if (translationState.modelState == TranslationModelState.DOWNLOADING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = ElectricCyan
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Model İndiriliyor...",
                            style = MaterialTheme.typography.labelLarge
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Çeviriyi Hazırla",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeechCard(
    speechState: SpeechState,
    onPrepareSpeechClick: () -> Unit,
    onTestVoiceClick: () -> Unit
) {
    val isPreparing = speechState.modelState == SpeechModelState.DOWNLOADING ||
        speechState.modelState == SpeechModelState.LOADING ||
        speechState.status == SpeechStatus.PREPARING

    val statusColor = when (speechState.status) {
        SpeechStatus.READY -> SignalEmerald
        SpeechStatus.SPEAKING -> ElectricCyan
        SpeechStatus.PREPARING, SpeechStatus.MODEL_NOT_READY -> SignalAmber
        SpeechStatus.IDLE -> TextMuted
        SpeechStatus.ERROR -> SignalCoral
    }

    val statusText = when (speechState.status) {
        SpeechStatus.IDLE -> "IDLE"
        SpeechStatus.MODEL_NOT_READY -> "MODEL NOT READY"
        SpeechStatus.PREPARING -> "PREPARING"
        SpeechStatus.READY -> "READY"
        SpeechStatus.SPEAKING -> "SPEAKING"
        SpeechStatus.ERROR -> "ERROR"
    }

    val modelBadgeText = when (speechState.modelState) {
        SpeechModelState.MODEL_NOT_READY -> "Model: NOT READY"
        SpeechModelState.DOWNLOADING -> {
            val pct = speechState.downloadProgressPercent
            if (pct != null) "Model: DOWNLOADING $pct%" else "Model: DOWNLOADING"
        }
        SpeechModelState.LOADING -> "Model: LOADING"
        SpeechModelState.READY -> "Model: READY"
        SpeechModelState.ERROR -> "Model: ERROR"
    }

    val modelBadgeColor = when (speechState.modelState) {
        SpeechModelState.READY -> SignalEmerald
        SpeechModelState.DOWNLOADING, SpeechModelState.LOADING -> ElectricCyan
        SpeechModelState.MODEL_NOT_READY -> SignalAmber
        SpeechModelState.ERROR -> SignalCoral
    }

    val helperText = when {
        speechState.status == SpeechStatus.SPEAKING -> "Türkçe ses çıkışına aktarılıyor..."
        speechState.modelState == SpeechModelState.DOWNLOADING -> "Preparing Turkish voice (indiriliyor)..."
        speechState.modelState == SpeechModelState.LOADING -> "Preparing Turkish voice..."
        speechState.modelState == SpeechModelState.READY -> "Language: TR • On-device Neural TTS hazır"
        speechState.modelState == SpeechModelState.ERROR -> "TTS motoru hatası"
        else -> "İlk kullanım için Türkçe Piper ONNX modeli gerekli"
    }

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        border = BorderStroke(
            width = 1.dp,
            color = when (speechState.status) {
                SpeechStatus.SPEAKING -> ElectricCyan.copy(alpha = 0.7f)
                SpeechStatus.READY -> SignalEmerald.copy(alpha = 0.45f)
                SpeechStatus.ERROR -> SignalCoral.copy(alpha = 0.5f)
                else -> SlateBorder
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("speech_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Türkçe ses durumu",
                        tint = statusColor
                    )
                    Text(
                        text = "TÜRKÇE SES",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = SlateSurfaceElevated,
                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.55f)),
                    modifier = Modifier.testTag("speech_status_badge")
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = SlateSurfaceElevated,
                    border = BorderStroke(1.dp, modelBadgeColor.copy(alpha = 0.45f)),
                    modifier = Modifier.testTag("speech_model_badge")
                ) {
                    Text(
                        text = modelBadgeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = modelBadgeColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = SlateSurfaceElevated,
                    border = BorderStroke(1.dp, SlateBorder)
                ) {
                    Text(
                        text = "Language: TR",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Text(
                text = helperText,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Surface(
                shape = MaterialTheme.shapes.medium,
                color = ObsidianBackground,
                border = BorderStroke(1.dp, SlateBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "SESLENDİRİLEN METİN",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    Text(
                        text = when {
                            !speechState.currentText.isNullOrBlank() -> speechState.currentText
                            speechState.modelState == SpeechModelState.MODEL_NOT_READY ->
                                "Türkçe neural ses modeli henüz hazırlanmadı"
                            else -> "Henüz seslendirme yok"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (!speechState.currentText.isNullOrBlank()) TextPrimary else TextMuted,
                        modifier = Modifier.testTag("speech_current_text")
                    )
                    if (!speechState.errorMessage.isNullOrBlank()) {
                        Text(
                            text = speechState.errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = SignalCoral,
                            modifier = Modifier.testTag("speech_error_text")
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TelemetryMetricTile(
                    label = "FIRST AUDIO",
                    value = speechState.firstAudioLatencyMs?.let { "$it ms" } ?: "UNKNOWN",
                    accentColor = ElectricCyan,
                    modifier = Modifier.weight(1f)
                )
                TelemetryMetricTile(
                    label = "SYNTHESIS",
                    value = speechState.synthesisDurationMs?.let { "$it ms" } ?: "UNKNOWN",
                    accentColor = SignalEmerald,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TelemetryMetricTile(
                    label = "PLAYBACK",
                    value = speechState.audioPlaybackDurationMs?.let { "$it ms" } ?: "UNKNOWN",
                    accentColor = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                TelemetryMetricTile(
                    label = "HAZIRLIK",
                    value = speechState.preparationTimeMs?.let { "$it ms" } ?: "UNKNOWN",
                    accentColor = TextSecondary,
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                text = "Motor: ${speechState.engineLabel} • Bluetooth/Hoparlör otomatik yönlendirme",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            speechState.diagnosticReport?.let { report ->
                SpeechDiagnosticResultBox(report = report)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (speechState.modelState != SpeechModelState.READY) {
                    Button(
                        onClick = onPrepareSpeechClick,
                        enabled = !isPreparing,
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = ObsidianBackground,
                            disabledContainerColor = SlateSurfaceElevated,
                            disabledContentColor = TextMuted
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("prepare_speech_button")
                    ) {
                        if (isPreparing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = ElectricCyan
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = speechState.downloadProgressPercent?.let { "İndiriliyor %$it" }
                                    ?: "Hazırlanıyor...",
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Sesi Hazırla",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = onTestVoiceClick,
                    enabled = !isPreparing,
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(
                        1.dp,
                        if (!isPreparing) ElectricCyan.copy(alpha = 0.6f) else SlateBorder
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("test_voice_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.RecordVoiceOver,
                        contentDescription = null,
                        tint = if (!isPreparing) ElectricCyan else TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "TTS Test",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (!isPreparing) ElectricCyan else TextMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeechDiagnosticResultBox(report: SpeechDiagnosticReport) {
    val isSuccess = report.category == SpeechDiagnosticCategory.TTS_SUCCESS
    val accent = if (isSuccess) SignalEmerald else SignalCoral
    val bg = if (isSuccess) SignalEmeraldDim else SignalCoralDim

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = bg,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("speech_diagnostic_box")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "TTS DİAGNOSTİK TESTİ: ${report.category.codeLabel}",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = accent
            )
            Text(
                text = "Model: ${report.modelName} • Native: ${if (report.nativeLibraryLoaded) "OK" else "FAIL"} • " +
                    "Dosya: ${if (report.modelExists) "${report.modelSizeBytes / (1024 * 1024)} MB" else "YOK"} • " +
                    "Config: ${if (report.configExists) "OK" else "YOK"}",
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary
            )
            Text(
                text = "${report.d1ModelStatus} | ${report.d2NativeStatus} | ${report.d3DirectGenerateStatus} | ${report.d4CallbackStatus} | ${report.d5AudioTrackStatus}",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            Text(
                text = "Direct generate: ${report.directGenerateSamples} örnek (null=${report.directGenerateReturnNull ?: "-"}) • " +
                    "Callback: ${report.callbackInvocations}/${report.callbackSamples} (ret=${report.callbackGenerateReturnedSamples})",
                style = MaterialTheme.typography.labelSmall,
                color = TextPrimary
            )
            if (isSuccess || report.sampleCount > 0L) {
                Text(
                    text = "Samples: ${report.sampleCount} • SampleRate: ${report.sampleRateHz ?: 0} Hz • " +
                        "Süre: ${report.audioDurationMs ?: 0} ms • Mod: ${report.synthesisMode ?: "-"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary
                )
            }
            report.routedDeviceSummary?.let { routing ->
                Text(
                    text = "AudioTrack: init=${report.audioTrackInitialized}, play=${report.audioTrackPlayed} • $routing",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            report.detailMessage?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSuccess) TextSecondary else SignalCoral
                )
            }
        }
    }
}

@Composable
private fun LiveFramePreviewCard(
    uiState: CaptureDashboardUiState,
    onOpenRoiEditorClick: () -> Unit
) {
    val previewBitmap = uiState.session.previewBitmap
    val telemetry = uiState.session.telemetry
    val isActive = uiState.session.status == CaptureStatus.ACTIVE
    val activeRoi = uiState.roiSettings.roi

    val imageBitmap = remember(previewBitmap) {
        if (previewBitmap != null && !previewBitmap.isRecycled) {
            previewBitmap.asImageBitmap()
        } else {
            null
        }
    }

    val previewAspectRatio = remember(previewBitmap) {
        if (previewBitmap != null && !previewBitmap.isRecycled && previewBitmap.height > 0) {
            (previewBitmap.width.toFloat() / previewBitmap.height.toFloat()).coerceIn(0.45f, 2.4f)
        } else {
            16f / 9f
        }
    }

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        border = BorderStroke(1.dp, SlateBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("live_preview_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Canlı Görüntü & Altyazı ROI",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                if (telemetry != null) {
                    Text(
                        text = "#${telemetry.frameIndex}",
                        style = MaterialTheme.typography.labelMedium,
                        color = ElectricCyan
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(previewAspectRatio)
                    .clip(MaterialTheme.shapes.medium)
                    .background(ObsidianBackground)
                    .border(1.dp, SlateBorder, MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center
            ) {
                if (imageBitmap != null) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = "MediaProjection ile yakalanan canlı ekran görüntüsü",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("captured_frame_image")
                    )

                    RoiPreviewOverlay(
                        roi = activeRoi,
                        modifier = Modifier.fillMaxSize()
                    )

                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = ObsidianBackground.copy(alpha = 0.82f),
                            border = BorderStroke(
                                1.dp,
                                (if (isActive) SignalEmerald else SignalAmber).copy(alpha = 0.6f)
                            )
                        ) {
                            Text(
                                text = if (isActive) "LIVE • ROI ACTIVE" else "SON KARE",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isActive) SignalEmerald else SignalAmber,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    if (!uiState.session.isContentVisible && isActive) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = SignalAmberDim.copy(alpha = 0.9f),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(10.dp)
                        ) {
                            Text(
                                text = "Yakalanan pencere şu an görünür değil",
                                style = MaterialTheme.typography.labelSmall,
                                color = SignalAmber,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                } else {
                    RoiPreviewOverlay(
                        roi = activeRoi,
                        modifier = Modifier.fillMaxSize()
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Henüz kare yakalanmadı",
                            tint = TextMuted,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = "Ekran yakalama başlatıldığında canlı kare ve ROI alanı burada görünür.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (uiState.roiSettings.isUserConfigured) {
                        "Kayıtlı ROI: L:${"%.2f".format(activeRoi.left)} T:${"%.2f".format(activeRoi.top)} " +
                            "R:${"%.2f".format(activeRoi.right)} B:${"%.2f".format(activeRoi.bottom)}"
                    } else {
                        "Varsayılan alt bölge ROI aktif"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            OutlinedButton(
                onClick = onOpenRoiEditorClick,
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.6f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("open_roi_editor_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.CropFree,
                    contentDescription = null,
                    tint = ElectricCyan,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Altyazı Alanını Ayarla",
                    style = MaterialTheme.typography.labelLarge,
                    color = ElectricCyan
                )
            }
        }
    }
}

@Composable
private fun RoiPreviewOverlay(
    roi: SubtitleRoi,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val l = roi.left * size.width
        val t = roi.top * size.height
        val r = roi.right * size.width
        val b = roi.bottom * size.height
        val scrim = Color.Black.copy(alpha = 0.32f)

        drawRect(scrim, Offset.Zero, Size(size.width, t))
        drawRect(scrim, Offset(0f, b), Size(size.width, size.height - b))
        drawRect(scrim, Offset(0f, t), Size(l, b - t))
        drawRect(scrim, Offset(r, t), Size(size.width - r, b - t))

        drawRect(
            color = ElectricCyan,
            topLeft = Offset(l, t),
            size = Size(r - l, b - t),
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

@Composable
private fun CaptureAndOcrRateCard(
    telemetry: FrameTelemetry?,
    ocrIntervalMs: Long,
    onUpdateOcrIntervalMs: (Long) -> Unit
) {
    val approxHz = 1000f / ocrIntervalMs.coerceAtLeast(1L)

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        border = BorderStroke(1.dp, SlateBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("telemetry_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Yakalama ve OCR Frekansı",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TelemetryMetricTile(
                    label = "ÇÖZÜNÜRLÜK",
                    value = if (telemetry != null) "${telemetry.width}×${telemetry.height}" else "—",
                    modifier = Modifier.weight(1f)
                )
                TelemetryMetricTile(
                    label = "CAPTURE FPS",
                    value = if (telemetry != null) "${"%.1f".format(telemetry.captureFps)} FPS" else "—",
                    accentColor = SignalEmerald,
                    modifier = Modifier.weight(1f)
                )
                TelemetryMetricTile(
                    label = "ATLANAN",
                    value = telemetry?.droppedFrames?.toString() ?: "—",
                    accentColor = if ((telemetry?.droppedFrames ?: 0L) > 0L) SignalAmber else TextPrimary,
                    modifier = Modifier.weight(0.8f)
                )
            }

            Surface(
                shape = MaterialTheme.shapes.small,
                color = SlateSurfaceElevated,
                border = BorderStroke(1.dp, SlateBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "OCR ÖRNEKLEME ARALIĞI",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                        Text(
                            text = "$ocrIntervalMs ms (~${"%.1f".format(approxHz)}/sn)",
                            style = MaterialTheme.typography.labelMedium,
                            color = ElectricCyan
                        )
                    }
                    Slider(
                        value = ocrIntervalMs.toFloat(),
                        onValueChange = { onUpdateOcrIntervalMs(it.roundToInt().toLong()) },
                        valueRange = SubtitleRoiSettings.MIN_OCR_INTERVAL_MS.toFloat()..
                            SubtitleRoiSettings.MAX_OCR_INTERVAL_MS.toFloat(),
                        steps = 7,
                        colors = SliderDefaults.colors(
                            thumbColor = ElectricCyan,
                            activeTrackColor = ElectricCyan,
                            inactiveTrackColor = SlateBorder
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun TelemetryMetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accentColor: Color = TextPrimary
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = SlateSurfaceElevated,
        border = BorderStroke(1.dp, SlateBorder),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = accentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SystemReadinessCard(
    uiState: CaptureDashboardUiState,
    onLaunchRemotePlayClick: () -> Unit
) {
    val env = uiState.environment
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        border = BorderStroke(1.dp, SlateBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("system_readiness_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Uygulama ve İzin Durumu",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )

            ReadinessRow(
                icon = Icons.Filled.SportsEsports,
                title = "REMOTE PLAY",
                statusText = if (env.isRemotePlayInstalled) "INSTALLED" else "NOT DETECTED",
                isReady = env.isRemotePlayInstalled
            )

            ReadinessRow(
                icon = Icons.Filled.Monitor,
                title = "CAPTURE",
                statusText = when (uiState.session.status) {
                    CaptureStatus.ACTIVE -> "ON"
                    CaptureStatus.STARTING -> "STARTING"
                    CaptureStatus.ERROR -> "ERROR"
                    CaptureStatus.STOPPED_BY_SYSTEM -> "STOPPED"
                    CaptureStatus.IDLE -> "OFF"
                },
                isReady = uiState.session.status == CaptureStatus.ACTIVE
            )

            ReadinessRow(
                icon = Icons.Filled.NotificationsActive,
                title = "BİLDİRİM İZNİ",
                statusText = if (env.isNotificationPermissionGranted) "GRANTED" else "REQUIRED",
                isReady = env.isNotificationPermissionGranted
            )

            if (env.isRemotePlayInstalled) {
                OutlinedButton(
                    onClick = onLaunchRemotePlayClick,
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("launch_remote_play_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "PS Remote Play'i Aç",
                        style = MaterialTheme.typography.labelLarge,
                        color = ElectricCyan
                    )
                }
            } else {
                Text(
                    text = "Resmi PS Remote Play uygulaması cihazda algılanmadı. Yine de herhangi bir video veya oyun ekranı ile OCR akışını test edebilirsiniz.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted
                )
            }
        }
    }
}

@Composable
private fun ReadinessRow(
    icon: ImageVector,
    title: String,
    statusText: String,
    isReady: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (isReady) SignalEmerald else TextSecondary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary
            )
        }
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (isReady) SignalEmeraldDim else SlateSurfaceElevated,
            border = BorderStroke(
                1.dp,
                if (isReady) SignalEmerald.copy(alpha = 0.45f) else SlateBorder
            )
        ) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = if (isReady) SignalEmerald else SignalAmber,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PipelineRoadmapCard() {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        border = BorderStroke(1.dp, SlateBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Aşama Durumu",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PhaseChip(label = "Phase 1–2: MediaProjection", active = true)
                PhaseChip(label = "Phase 3: ROI + ML Kit OCR", active = true)
                PhaseChip(label = "Phase 4: EN→TR Çeviri", active = true)
                PhaseChip(label = "Phase 5: Türkçe TTS", active = true)
            }
        }
    }
}

@Composable
private fun PhaseChip(label: String, active: Boolean) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (active) SignalEmeraldDim else SlateSurfaceElevated,
        border = BorderStroke(1.dp, if (active) SignalEmerald.copy(alpha = 0.5f) else SlateBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = if (active) Icons.Filled.CheckCircle else Icons.Outlined.LockClock,
                contentDescription = null,
                tint = if (active) SignalEmerald else TextMuted,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (active) TextPrimary else TextMuted
            )
        }
    }
}
