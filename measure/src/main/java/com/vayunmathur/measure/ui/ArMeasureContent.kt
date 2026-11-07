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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
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
import com.vayunmathur.measure.domain.MeasureNative
import com.vayunmathur.measure.domain.Units
import com.vayunmathur.measure.platform.ArMeasureActions
import com.vayunmathur.measure.platform.ArMeasureUiState

@Composable
fun ArMeasureContent(
    state: ArMeasureUiState,
    actions: ArMeasureActions,
    screenPoints: List<Offset?> = emptyList(),
    onCanvasSized: (Size) -> Unit = {},
    onTap: (Float, Float) -> Unit = { _, _ -> },
    cameraContent: @Composable () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
) {
    AppScaffold(
        title = stringResource(R.string.app_name),
        actions = { IconButton(onClick = onOpenSettings) { IconSettings() } },
        bottomBar = bottomBar,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {

            if (!MeasureNative.available) {
                UnavailableNotice(stringResource(R.string.ar_unavailable_native))
                return@Box
            }
            if (!state.cameraPermissionGranted) {
                UnavailableNotice(stringResource(R.string.ar_unavailable_camera))
                return@Box
            }

            cameraContent()

            ArOverlay(
                state = state,
                screenPoints = screenPoints,
                onCanvasSized = onCanvasSized,
                onTap = onTap,
                modifier = Modifier.fillMaxSize(),
            )

            Column(
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TrackingBanner(state.quality, state.hasPlane)
            }

            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReadoutCard(state)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

/**
 * Coaching banner.
 *
 * While initialising this is not decoration — monocular visual-inertial tracking cannot
 * recover metric scale from rotation alone, so the user genuinely has to translate the
 * device before any distance exists. Saying so plainly is more useful than a spinner.
 */
@Composable
private fun TrackingBanner(quality: TrackingQuality, hasPlane: Boolean) {
    val (message, color) = when (quality) {
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
                if (hasPlane) R.string.ar_tracking_ready else R.string.ar_tracking_ready_no_surface
            ) to MaterialTheme.colorScheme.primaryContainer
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
private fun ReadoutCard(state: ArMeasureUiState) {
    if (state.distanceM == null && state.areaM2 == null) return
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
 * Anchor and edge overlay.
 *
 * Anchors are drawn from screen positions the engine projected from their world
 * coordinates, so this layer holds no projection maths and stays previewable.
 */
@Composable
private fun ArOverlay(
    state: ArMeasureUiState,
    screenPoints: List<Offset?>,
    onCanvasSized: (Size) -> Unit,
    onTap: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val measurable = state.quality == TrackingQuality.Good ||
        state.quality == TrackingQuality.Limited

    Canvas(
        modifier
            .onSizeChanged { onCanvasSized(Size(it.width.toFloat(), it.height.toFloat())) }
            .pointerInput(measurable) {
                if (!measurable) return@pointerInput
                detectTapGestures { pos ->
                    onTap(pos.x / size.width, pos.y / size.height)
                }
            }
    ) {
        // Reticle at the centre, showing where a tap would land.
        if (measurable) {
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(primary.copy(alpha = 0.9f), 6f, c)
            drawCircle(primary.copy(alpha = 0.4f), 22f, c, style = Stroke(2f))
        }

        for (i in 0 until screenPoints.size - 1) {
            val a = screenPoints[i]
            val b = screenPoints[i + 1]
            if (a != null && b != null) drawLine(primary, a, b, 4f)
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
private fun UnavailableNotice(message: String) {
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
    }
}
