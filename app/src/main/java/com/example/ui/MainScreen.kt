package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material.icons.outlined.LockClock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.capture.CaptureStatus
import com.example.capture.FrameTelemetry
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

@Composable
fun MainScreen(
    uiState: CaptureDashboardUiState,
    onStartCaptureClick: () -> Unit,
    onStopCaptureClick: () -> Unit,
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
                            SystemReadinessCard(
                                uiState = uiState,
                                onLaunchRemotePlayClick = onLaunchRemotePlayClick
                            )
                            PipelineRoadmapCard()
                        }

                        Column(
                            modifier = Modifier.weight(1.15f),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            LiveFramePreviewCard(uiState = uiState)
                            TelemetryGridCard(
                                telemetry = uiState.session.telemetry,
                                hardwareSummary = uiState.environment.hardwareSummary
                            )
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
                        LiveFramePreviewCard(uiState = uiState)
                        TelemetryGridCard(
                            telemetry = uiState.session.telemetry,
                            hardwareSummary = uiState.environment.hardwareSummary
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
                        text = "PHASE 1–2",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "PS5 Remote Play • MediaProjection Frame Capture",
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
                            "Arka Plan Ekran Yakalama Aktif"
                        } else {
                            "MediaProjection Yakalamaya Hazır"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = when (status) {
                            CaptureStatus.ACTIVE ->
                                "PS Remote Play'e geçebilirsiniz. Kareler latest-frame-only olarak yakalanıyor."
                            CaptureStatus.STARTING ->
                                "Foreground servis ve VirtualDisplay başlatılıyor..."
                            CaptureStatus.STOPPED_BY_SYSTEM ->
                                "Sistem oturumu sonlandırdı. Yeniden başlatabilirsiniz."
                            else ->
                                "Sistem izni vererek VirtualDisplay + ImageReader yakalamayı doğrulayın."
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
private fun LiveFramePreviewCard(uiState: CaptureDashboardUiState) {
    val previewBitmap = uiState.session.previewBitmap
    val telemetry = uiState.session.telemetry
    val isActive = uiState.session.status == CaptureStatus.ACTIVE

    val imageBitmap = remember(previewBitmap) {
        if (previewBitmap != null && !previewBitmap.isRecycled) {
            previewBitmap.asImageBitmap()
        } else {
            null
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
                    text = "Canlı Ekran Yakalama Önizlemesi",
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
                    .aspectRatio(16f / 9f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(ObsidianBackground)
                    .border(1.dp, SlateBorder, MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center
            ) {
                if (imageBitmap != null) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = "MediaProjection ile yakalanan canlı ekran görüntüsü",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("captured_frame_image")
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
                                text = if (isActive) "LIVE • VIRTUAL DISPLAY" else "SON YAKALANAN KARE",
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
                                text = "Yakalanan içerik şu an arka planda veya görünür değil",
                                style = MaterialTheme.typography.labelSmall,
                                color = SignalAmber,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Henüz kare yakalanmadı",
                            tint = TextMuted,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = "Henüz yakalanan ekran karesi yok",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextSecondary
                        )
                        Text(
                            text = "Ekran Yakalamayı Başlat'a dokunup izin verdiğinizde gerçek VirtualDisplay karesi burada görünür.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryGridCard(
    telemetry: FrameTelemetry?,
    hardwareSummary: String
) {
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Memory,
                    contentDescription = "Donanım ve kare telemetrisi",
                    tint = ElectricCyan
                )
                Text(
                    text = "Kare Hattı Telemetrisi",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
            }

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
                    label = "YAKALAMA HIZI",
                    value = if (telemetry != null) "${"%.1f".format(telemetry.captureFps)} FPS" else "—",
                    accentColor = SignalEmerald,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TelemetryMetricTile(
                    label = "KARE DÖNÜŞÜMÜ",
                    value = if (telemetry != null) "${telemetry.conversionTimeMs} ms" else "—",
                    accentColor = ElectricCyan,
                    modifier = Modifier.weight(1f)
                )
                TelemetryMetricTile(
                    label = "ATLANAN / TOPLAM",
                    value = if (telemetry != null) "${telemetry.droppedFrames} / ${telemetry.frameIndex}" else "—",
                    accentColor = if ((telemetry?.droppedFrames ?: 0L) > 0L) SignalAmber else TextPrimary,
                    modifier = Modifier.weight(1f)
                )
            }

            Surface(
                shape = MaterialTheme.shapes.small,
                color = SlateSurfaceElevated,
                border = BorderStroke(1.dp, SlateBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "RUNTIME",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    Text(
                        text = buildString {
                            append(hardwareSummary.ifBlank { "Standart" })
                            if (telemetry != null) {
                                append(if (telemetry.usedHardwareBuffer) " • HWBuf Active" else " • Plane Copy")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
                text = "Cihaz ve Modül Durumu",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )

            ReadinessRow(
                icon = Icons.Filled.SportsEsports,
                title = "REMOTE PLAY",
                statusText = if (env.isRemotePlayInstalled) "READY" else "NOT DETECTED",
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
                statusText = if (env.isNotificationPermissionGranted) "READY" else "REQUIRED",
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
                    text = "Cihazda resmi PS Remote Play paketi (com.playstation.remoteplay) bulunamadı. Yine de herhangi bir ekran içeriği ile MediaProjection yakalamayı test edebilirsiniz.",
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
                text = "Aşamalı Pipeline Durumu",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PhaseChip(label = "Phase 1–2: MediaProjection", active = true)
                PhaseChip(label = "Phase 3: ROI Seçimi", active = false)
                PhaseChip(label = "Phase 4–5: ML Kit OCR", active = false)
                PhaseChip(label = "Phase 6: EN→TR Çeviri", active = false)
                PhaseChip(label = "Phase 7: Türkçe TTS", active = false)
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
