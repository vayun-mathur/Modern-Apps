package com.vayunmathur.measure.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconUndo
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.measure.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.measure.data.model.TrackingQuality
import com.vayunmathur.measure.domain.ArFlowState
import com.vayunmathur.measure.domain.ArUnavailableReason
import com.vayunmathur.measure.domain.MeasureNative
import com.vayunmathur.measure.domain.Units
import com.vayunmathur.measure.domain.segmentsOf
import com.vayunmathur.measure.platform.ArMeasureActions
import com.vayunmathur.measure.platform.ArMeasureUiState

@Composable
fun ArMeasureContent(
    state: ArMeasureUiState,
    actions: ArMeasureActions,
    screenPoints: List<Offset?> = emptyList(),
    onCanvasSized: (Size) -> Unit = {},
    cameraContent: @Composable () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenRuler: () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
) {
    AppScaffold(
        title = stringResource(R.string.app_name),
        actions = { IconButton(onClick = onOpenSettings) { IconSettings() } },
        bottomBar = bottomBar,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {

            // Unavailable is a real screen with an exit, never a dead camera view.
            val unavailableReason = (state.flowState as? ArFlowState.Unavailable)?.reason
            when {
                unavailableReason != null -> {
                    UnavailableNotice(unavailableMessage(unavailableReason), onOpenRuler)
                    return@Box
                }
                !MeasureNative.available -> {
                    UnavailableNotice(stringResource(R.string.ar_unavailable_native), onOpenRuler)
                    return@Box
                }
                !state.cameraPermissionGranted -> {
                    UnavailableNotice(stringResource(R.string.ar_unavailable_camera), onOpenRuler)
                    return@Box
                }
            }

            cameraContent()

            ArOverlay(
                state = state,
                screenPoints = screenPoints,
                onCanvasSized = onCanvasSized,
                onTap = { actions.placeAtReticle() },
                modifier = Modifier.fillMaxSize(),
            )

            Column(
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TrackingBanner(state)
                val timedOut = (state.flowState as? ArFlowState.Scanning)?.timedOut == true ||
                    (state.flowState as? ArFlowState.TrackingLost)?.timedOut == true
                if (timedOut) {
                    FallbackCard(onOpenRuler = onOpenRuler, onRetry = { actions.resetTracking() })
                }
            }

            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReadoutCard(state)
                if (state.anchors.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.anchors.forEachIndexed { index, anchor ->
                            OutlinedButton(onClick = { actions.removeAnchor(anchor.id) }) {
                                Text(stringResource(R.string.ar_anchor_point, index + 1) + " ×")
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { actions.placeAtReticle() },
                        enabled = state.flowState is ArFlowState.Ready,
                    ) {
                        IconAdd()
                        Text(stringResource(R.string.ar_place_point))
                    }
                    OutlinedButton(
                        onClick = { actions.undoAnchor() },
                        enabled = state.anchors.isNotEmpty(),
                    ) { IconUndo() }
                    OutlinedButton(
                        onClick = { actions.closePolygon() },
                        enabled = state.anchors.size >= 3 && !state.polygonClosed,
                    ) { Text(stringResource(R.string.ar_close_shape)) }
                    OutlinedButton(
                        onClick = { actions.clearAnchors() },
                        enabled = state.anchors.isNotEmpty(),
                    ) { Text(stringResource(UiR.string.clear)) }
                }
            }
        }
    }
}

@Composable
private fun unavailableMessage(reason: ArUnavailableReason): String = when (reason) {
    ArUnavailableReason.NativeEngineMissing -> stringResource(R.string.ar_unavailable_native)
    ArUnavailableReason.CameraPermissionDenied -> stringResource(R.string.ar_unavailable_camera)
    ArUnavailableReason.CameraTimestampMismatch -> stringResource(R.string.ar_unavailable_timestamps)
    ArUnavailableReason.MotionSensorsMissing -> stringResource(R.string.ar_unavailable_sensors)
}

/**
 * Coaching banner, driven by the typed flow state. While scanning this
 * is not decoration: monocular VIO cannot recover metric scale from
 * rotation alone, so the user genuinely has to translate the device.
 * Manually constructed states (previews/tests) fall back to the
 * quality-based wording they always used.
 */
@Composable
private fun TrackingBanner(state: ArMeasureUiState) {
    val (message, color) = when (val flow = state.flowState) {
        is ArFlowState.Unavailable -> return
        is ArFlowState.Ready ->
            stringResource(R.string.ar_ready_place) to MaterialTheme.colorScheme.primaryContainer
        ArFlowState.Searching ->
            stringResource(R.string.ar_searching) to MaterialTheme.colorScheme.tertiaryContainer
        is ArFlowState.Scanning ->
            if (flow.timedOut) {
                stringResource(R.string.ar_scanning_timeout) to MaterialTheme.colorScheme.errorContainer
            } else if (state.quality == TrackingQuality.Limited) {
                stringResource(R.string.ar_tracking_limited) to MaterialTheme.colorScheme.tertiaryContainer
            } else {
                stringResource(R.string.ar_scanning) to MaterialTheme.colorScheme.tertiaryContainer
            }
        is ArFlowState.TrackingLost ->
            if (flow.timedOut) {
                stringResource(R.string.ar_lost_timeout) to MaterialTheme.colorScheme.errorContainer
            } else {
                stringResource(R.string.ar_tracking_lost) to MaterialTheme.colorScheme.errorContainer
            }
        ArFlowState.NoSession -> legacyBanner(state)
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun legacyBanner(state: ArMeasureUiState): Pair<String, androidx.compose.ui.graphics.Color> =
    when (state.quality) {
        TrackingQuality.Initialising ->
            stringResource(R.string.ar_tracking_initialising) to
                MaterialTheme.colorScheme.tertiaryContainer
        TrackingQuality.Limited ->
            stringResource(R.string.ar_tracking_limited) to
                MaterialTheme.colorScheme.tertiaryContainer
        TrackingQuality.Lost ->
            stringResource(R.string.ar_tracking_lost) to
                MaterialTheme.colorScheme.errorContainer
        TrackingQuality.Good ->
            stringResource(
                if (state.hasPlane) R.string.ar_tracking_ready else R.string.ar_tracking_ready_no_surface
            ) to MaterialTheme.colorScheme.primaryContainer
    }

@Composable
private fun FallbackCard(onOpenRuler: () -> Unit, onRetry: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.ar_fallback_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.ar_fallback_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenRuler) { Text(stringResource(R.string.ar_open_ruler)) }
                OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.ar_try_again)) }
            }
        }
    }
}

@Composable
private fun ReadoutCard(state: ArMeasureUiState) {
    if (state.distanceM == null && state.areaM2 == null && !state.polygonClosed) return
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            state.distanceM?.let {
                Text(
                    Units.formatLength(it, state.unitSystem),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            state.totalDistanceM?.let { total ->
                if (state.anchors.size > 2) {
                    Text(
                        stringResource(R.string.ar_total, Units.formatLength(total, state.unitSystem)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (state.polygonClosed && !state.areaTrustworthy) {
                Text(
                    stringResource(R.string.ar_area_unreliable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.areaM2?.let {
                Text(
                    stringResource(R.string.ar_area, Units.formatArea(it, state.unitSystem)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            state.perimeterM?.let {
                Text(
                    stringResource(
                        R.string.ar_perimeter,
                        Units.formatLength(it, state.unitSystem),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Anchor/edge overlay with a continuous centre reticle. The reticle is
 * the placement point: any tap (or the Place button) places at the
 * reticle hit, never at the finger's raw ray, so what the user sees
 * centred is exactly what gets measured.
 */
@Composable
private fun ArOverlay(
    state: ArMeasureUiState,
    screenPoints: List<Offset?>,
    onCanvasSized: (Size) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val error = MaterialTheme.colorScheme.error
    val ready = state.flowState is ArFlowState.Ready
    val searching = state.flowState is ArFlowState.Searching
    val segments = segmentsOf(state.anchors)
    val segmentLabels = segments.map { Units.formatLength(it.lengthM, state.unitSystem) }

    Canvas(
        modifier
            .onSizeChanged { onCanvasSized(Size(it.width.toFloat(), it.height.toFloat())) }
            .pointerInput(ready) {
                if (!ready) return@pointerInput
                detectTapGestures { onTap() }
            }
    ) {
        // Reticle at the centre: filled when a sane hit is selected,
        // hollow while searching, hidden until tracking starts.
        if (ready || searching) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val reticleColor = if (ready) primary else error
            drawCircle(reticleColor.copy(alpha = 0.9f), 6f, c)
            drawCircle(reticleColor.copy(alpha = 0.4f), 22f, c, style = Stroke(2f))
        }

        for (i in 0 until screenPoints.size - 1) {
            val a = screenPoints[i]
            val b = screenPoints[i + 1]
            if (a != null && b != null) {
                drawLine(primary, a, b, 4f)
                // Mid-point distance label for this segment.
                val label = segmentLabels.getOrNull(i)
                if (label != null) {
                    val mid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                    drawContext.canvas.nativeCanvas.drawText(
                        label,
                        mid.x,
                        mid.y - 12f,
                        android.graphics.Paint().apply {
                            textSize = 36f
                            textAlign = android.graphics.Paint.Align.CENTER
                        },
                    )
                }
            }
        }
        if (state.polygonClosed && screenPoints.size >= 3) {
            val a = screenPoints.last()
            val b = screenPoints.first()
            if (a != null && b != null) drawLine(primary, a, b, 4f)
        }
        for (p in screenPoints) {
            if (p == null) continue
            drawCircle(primary, 12f, p)
            drawCircle(onPrimary, 5f, p)
        }
    }
}

@Composable
private fun UnavailableNotice(message: String, onOpenRuler: () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.ar_unavailable_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.ar_fallback_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onOpenRuler) { Text(stringResource(R.string.ar_open_ruler)) }
    }
}
