package com.example.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.subtitle.SubtitleRoi
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.SignalAmber
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.SlateSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlin.math.abs
import kotlin.math.hypot

private enum class DragMode {
    NONE,
    MOVE,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    LEFT_EDGE,
    RIGHT_EDGE,
    TOP_EDGE,
    BOTTOM_EDGE
}

@Composable
fun RoiEditorScreen(
    initialRoi: SubtitleRoi,
    previewBitmap: Bitmap?,
    onSaveRoi: (SubtitleRoi) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onCancel)

    var draftRoi by remember(initialRoi) { mutableStateOf(initialRoi.clamped()) }
    val latestRoi by rememberUpdatedState(draftRoi)
    val density = LocalDensity.current

    val imageBitmap = remember(previewBitmap) {
        if (previewBitmap != null && !previewBitmap.isRecycled) {
            previewBitmap.asImageBitmap()
        } else {
            null
        }
    }
    val frameAspectRatio = remember(previewBitmap) {
        if (previewBitmap != null && !previewBitmap.isRecycled && previewBitmap.height > 0) {
            previewBitmap.width.toFloat() / previewBitmap.height.toFloat()
        } else {
            16f / 9f
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBackground),
        containerColor = ObsidianBackground,
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Altyazı Alanını Ayarla",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = "Dikdörtgeni sürükleyin veya köşe/kenarlardan boyutlandırın.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("roi_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "ROI düzenleyiciyi kapat",
                        tint = TextSecondary
                    )
                }
            }

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(SlateSurface)
                    .border(1.dp, SlateBorder, MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center
            ) {
                val containerW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                val containerH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                val containerAspect = containerW / containerH

                val (targetWidthPx, targetHeightPx) = if (containerAspect > frameAspectRatio) {
                    val h = containerH
                    val w = h * frameAspectRatio
                    w to h
                } else {
                    val w = containerW
                    val h = w / frameAspectRatio
                    w to h
                }

                val targetWidthDp = with(density) { targetWidthPx.toDp() }
                val targetHeightDp = with(density) { targetHeightPx.toDp() }
                val handleRadiusPx = with(density) { 26.dp.toPx() }
                val edgeHitPx = with(density) { 18.dp.toPx() }

                Box(
                    modifier = Modifier
                        .size(targetWidthDp, targetHeightDp)
                        .background(ObsidianBackground)
                        .border(1.dp, SlateBorder)
                ) {
                    if (imageBitmap != null) {
                        Image(
                            bitmap = imageBitmap,
                            contentDescription = "ROI seçimi için canlı yakalanan ekran görüntüsü",
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Henüz yakalanmış kare yok",
                                style = MaterialTheme.typography.titleMedium,
                                color = SignalAmber
                            )
                            Text(
                                text = "Ekran yakalama açıkken gerçek Remote Play karesi arka planda görünür. Şimdilik alt bölgedeki varsayılan oranı ayarlayabilirsiniz.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted
                            )
                        }
                    }

                    var activeDragMode by remember { mutableStateOf(DragMode.NONE) }

                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("roi_editor_canvas")
                            .pointerInput(targetWidthPx, targetHeightPx) {
                                detectDragGestures(
                                    onDragStart = { startOffset ->
                                        val roi = latestRoi
                                        val lPx = roi.left * targetWidthPx
                                        val tPx = roi.top * targetHeightPx
                                        val rPx = roi.right * targetWidthPx
                                        val bPx = roi.bottom * targetHeightPx

                                        activeDragMode = detectDragMode(
                                            touch = startOffset,
                                            leftPx = lPx,
                                            topPx = tPx,
                                            rightPx = rPx,
                                            bottomPx = bPx,
                                            cornerHitRadiusPx = handleRadiusPx,
                                            edgeHitThresholdPx = edgeHitPx
                                        )
                                    },
                                    onDragEnd = { activeDragMode = DragMode.NONE },
                                    onDragCancel = { activeDragMode = DragMode.NONE },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        if (activeDragMode == DragMode.NONE) return@detectDragGestures
                                        val dx = dragAmount.x / targetWidthPx
                                        val dy = dragAmount.y / targetHeightPx
                                        draftRoi = applyDrag(
                                            current = latestRoi,
                                            mode = activeDragMode,
                                            dx = dx,
                                            dy = dy,
                                            referenceAspectRatio = frameAspectRatio
                                        )
                                    }
                                )
                            }
                    ) {
                        val l = draftRoi.left * size.width
                        val t = draftRoi.top * size.height
                        val r = draftRoi.right * size.width
                        val b = draftRoi.bottom * size.height
                        val scrimColor = Color.Black.copy(alpha = 0.50f)

                        // Top scrim
                        drawRect(scrimColor, Offset.Zero, Size(size.width, t))
                        // Bottom scrim
                        drawRect(scrimColor, Offset(0f, b), Size(size.width, size.height - b))
                        // Left scrim
                        drawRect(scrimColor, Offset(0f, t), Size(l, b - t))
                        // Right scrim
                        drawRect(scrimColor, Offset(r, t), Size(size.width - r, b - t))

                        // ROI border
                        drawRect(
                            color = ElectricCyan,
                            topLeft = Offset(l, t),
                            size = Size(r - l, b - t),
                            style = Stroke(width = 2.5.dp.toPx())
                        )

                        // Corner resize handles
                        val handleDotRadius = 7.dp.toPx()
                        val corners = listOf(
                            Offset(l, t),
                            Offset(r, t),
                            Offset(l, b),
                            Offset(r, b)
                        )
                        corners.forEach { pt ->
                            drawCircle(
                                color = ObsidianBackground,
                                radius = handleDotRadius + 2.dp.toPx(),
                                center = pt
                            )
                            drawCircle(
                                color = ElectricCyan,
                                radius = handleDotRadius,
                                center = pt
                            )
                        }
                    }
                }
            }

            Surface(
                shape = MaterialTheme.shapes.small,
                color = SlateSurfaceElevated,
                border = BorderStroke(1.dp, SlateBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "NORMALIZED ROI",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    Text(
                        text = "L:${"%.2f".format(draftRoi.left)}  T:${"%.2f".format(draftRoi.top)}  " +
                            "R:${"%.2f".format(draftRoi.right)}  B:${"%.2f".format(draftRoi.bottom)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = ElectricCyan,
                        modifier = Modifier.testTag("roi_coordinates_readout")
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        draftRoi = SubtitleRoi.DEFAULT.copy(referenceAspectRatio = frameAspectRatio)
                    },
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, SlateBorder),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("roi_reset_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.RestartAlt,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Sıfırla", color = TextSecondary)
                }

                OutlinedButton(
                    onClick = onCancel,
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, SlateBorder),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("roi_cancel_button")
                ) {
                    Text("İptal", color = TextPrimary)
                }

                Button(
                    onClick = {
                        onSaveRoi(draftRoi.copy(referenceAspectRatio = frameAspectRatio).clamped())
                    },
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricCyan,
                        contentColor = ObsidianBackground
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("roi_save_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Kaydet")
                }
            }
        }
    }
}

private fun detectDragMode(
    touch: Offset,
    leftPx: Float,
    topPx: Float,
    rightPx: Float,
    bottomPx: Float,
    cornerHitRadiusPx: Float,
    edgeHitThresholdPx: Float
): DragMode {
    val corners = listOf(
        DragMode.TOP_LEFT to Offset(leftPx, topPx),
        DragMode.TOP_RIGHT to Offset(rightPx, topPx),
        DragMode.BOTTOM_LEFT to Offset(leftPx, bottomPx),
        DragMode.BOTTOM_RIGHT to Offset(rightPx, bottomPx)
    )
    val nearestCorner = corners.minByOrNull { (_, c) -> hypot(touch.x - c.x, touch.y - c.y) }
    if (nearestCorner != null &&
        hypot(touch.x - nearestCorner.second.x, touch.y - nearestCorner.second.y) <= cornerHitRadiusPx
    ) {
        return nearestCorner.first
    }

    val withinVertical = touch.y in (topPx - edgeHitThresholdPx)..(bottomPx + edgeHitThresholdPx)
    val withinHorizontal = touch.x in (leftPx - edgeHitThresholdPx)..(rightPx + edgeHitThresholdPx)

    if (withinVertical && abs(touch.x - leftPx) <= edgeHitThresholdPx) return DragMode.LEFT_EDGE
    if (withinVertical && abs(touch.x - rightPx) <= edgeHitThresholdPx) return DragMode.RIGHT_EDGE
    if (withinHorizontal && abs(touch.y - topPx) <= edgeHitThresholdPx) return DragMode.TOP_EDGE
    if (withinHorizontal && abs(touch.y - bottomPx) <= edgeHitThresholdPx) return DragMode.BOTTOM_EDGE

    if (touch.x in leftPx..rightPx && touch.y in topPx..bottomPx) {
        return DragMode.MOVE
    }
    return DragMode.NONE
}

private fun applyDrag(
    current: SubtitleRoi,
    mode: DragMode,
    dx: Float,
    dy: Float,
    referenceAspectRatio: Float
): SubtitleRoi {
    val minW = SubtitleRoi.MIN_NORMALIZED_WIDTH
    val minH = SubtitleRoi.MIN_NORMALIZED_HEIGHT

    return when (mode) {
        DragMode.NONE -> current
        DragMode.MOVE -> {
            val w = current.width
            val h = current.height
            val newLeft = (current.left + dx).coerceIn(0f, (1f - w).coerceAtLeast(0f))
            val newTop = (current.top + dy).coerceIn(0f, (1f - h).coerceAtLeast(0f))
            SubtitleRoi(
                left = newLeft,
                top = newTop,
                right = (newLeft + w).coerceAtMost(1f),
                bottom = (newTop + h).coerceAtMost(1f),
                referenceAspectRatio = referenceAspectRatio
            )
        }
        DragMode.TOP_LEFT -> current.copy(
            left = (current.left + dx).coerceIn(0f, current.right - minW),
            top = (current.top + dy).coerceIn(0f, current.bottom - minH),
            referenceAspectRatio = referenceAspectRatio
        )
        DragMode.TOP_RIGHT -> current.copy(
            right = (current.right + dx).coerceIn(current.left + minW, 1f),
            top = (current.top + dy).coerceIn(0f, current.bottom - minH),
            referenceAspectRatio = referenceAspectRatio
        )
        DragMode.BOTTOM_LEFT -> current.copy(
            left = (current.left + dx).coerceIn(0f, current.right - minW),
            bottom = (current.bottom + dy).coerceIn(current.top + minH, 1f),
            referenceAspectRatio = referenceAspectRatio
        )
        DragMode.BOTTOM_RIGHT -> current.copy(
            right = (current.right + dx).coerceIn(current.left + minW, 1f),
            bottom = (current.bottom + dy).coerceIn(current.top + minH, 1f),
            referenceAspectRatio = referenceAspectRatio
        )
        DragMode.LEFT_EDGE -> current.copy(
            left = (current.left + dx).coerceIn(0f, current.right - minW),
            referenceAspectRatio = referenceAspectRatio
        )
        DragMode.RIGHT_EDGE -> current.copy(
            right = (current.right + dx).coerceIn(current.left + minW, 1f),
            referenceAspectRatio = referenceAspectRatio
        )
        DragMode.TOP_EDGE -> current.copy(
            top = (current.top + dy).coerceIn(0f, current.bottom - minH),
            referenceAspectRatio = referenceAspectRatio
        )
        DragMode.BOTTOM_EDGE -> current.copy(
            bottom = (current.bottom + dy).coerceIn(current.top + minH, 1f),
            referenceAspectRatio = referenceAspectRatio
        )
    }
}
