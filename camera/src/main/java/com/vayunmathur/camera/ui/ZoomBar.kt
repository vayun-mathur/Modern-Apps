package com.vayunmathur.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.camera.domain.ZoomScrubRouter
import com.vayunmathur.camera.platform.formatZoomLabel
import com.vayunmathur.library.ui.Text

/** Horizontal extent of one ZoomBar chip in the bar, for tap resolution. */
private data class ZoomChipBounds(val ratio: Float, val left: Float, val right: Float)

/** The chip at [xPx], or the nearest chip when [xPx] falls in a gap/padding. */
private fun zoomChipAt(bounds: Collection<ZoomChipBounds>, xPx: Float): Float? =
    bounds.minByOrNull { chip ->
        when {
            xPx < chip.left -> chip.left - xPx
            xPx > chip.right -> xPx - chip.right
            else -> 0f
        }
    }?.ratio

@Composable
internal fun ZoomBar(
    currentZoom: Float,
    zoomLevels: List<Pair<String, Float>>,
    onZoomSelected: (Float) -> Unit,
    modifier: Modifier = Modifier,
    // Device zoom range (session zoomRatioRange) the scrub traverses;
    // defaults to the offered levels' span for previews/tests without a
    // live session.
    zoomRange: Pair<Float, Float> =
        (zoomLevels.minOfOrNull { it.second } ?: 1f) to (zoomLevels.maxOfOrNull { it.second } ?: 1f)
) {
    // Insert the live zoom ratio between the two fixed levels it falls between,
    // unless it already matches one of the listed options.
    val displayLevels = remember(zoomLevels, currentZoom) {
        if (zoomLevels.any { kotlin.math.abs(it.second - currentZoom) < 0.05f }) {
            zoomLevels
        } else {
            val entry = formatZoomLabel(currentZoom) to currentZoom
            val insertAt = zoomLevels.indexOfFirst { it.second > currentZoom }
            if (insertAt < 0) zoomLevels + entry
            else zoomLevels.toMutableList().apply { add(insertAt, entry) }
        }
    }
    // Latest values for the long-lived gesture handler below.
    val currentOnZoomSelected by rememberUpdatedState(onZoomSelected)
    val currentZoomRange by rememberUpdatedState(zoomRange)
    val currentZoomState by rememberUpdatedState(currentZoom)
    var chipBounds by remember { mutableStateOf<List<ZoomChipBounds>>(emptyList()) }
    val currentChipBounds by rememberUpdatedState(chipBounds)
    Row(
        modifier = modifier
            .background(Color(0x99000000), androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
            // One gesture handler for the whole bar (the PreviewGestureRouter
            // lesson: two competing detectors race). Past the touch slop a
            // horizontal drag is a continuous RELATIVE scrub — the ratio
            // anchored at drag start is scaled log-scale by the drag delta
            // (never by the finger's absolute position, so touch-down alone
            // emits nothing and a small move never jumps) and is applied
            // live through onZoomSelected (CameraViewModel.setZoomRatio, so
            // clamping and the panorama/photosphere zoom lock compose
            // exactly as for pinch); a release without scrubbing selects
            // the chip under the finger. Chips carry only a semantics click
            // action (accessibility / test clicks), no touch detector of
            // their own.
            .pointerInput(Unit) {
                val router = ZoomScrubRouter(onZoom = { currentOnZoomSelected(it) })
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val (minZoom, maxZoom) = currentZoomRange
                    router.updateZoomRange(minZoom, maxZoom)
                    router.dragStart(down.position.x, size.width.toFloat(), currentZoomState)
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            router.dragTo(pressed[0].position.x, viewConfiguration.touchSlop)
                            if (router.isScrubbing) event.changes.forEach { it.consume() }
                        }
                        if (!router.dragEnd()) {
                            zoomChipAt(currentChipBounds, down.position.x)
                                ?.let { currentOnZoomSelected(it) }
                        }
                    } catch (e: Exception) {
                        router.dragCancel()
                        throw e
                    }
                }
            }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        displayLevels.forEachIndexed { index, (label, ratio) ->
            val isSelected = kotlin.math.abs(currentZoom - ratio) < 0.05f
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .selectedPill(isSelected, CircleShape)
                    .clip(CircleShape)
                    .onGloballyPositioned { coordinates ->
                        val bounds = ZoomChipBounds(
                            ratio = ratio,
                            left = coordinates.positionInParent().x,
                            right = coordinates.positionInParent().x + coordinates.size.width,
                        )
                        val previous = chipBounds.getOrNull(index)
                        if (previous != bounds || chipBounds.size != displayLevels.size) {
                            chipBounds = chipBounds.toMutableList().apply {
                                while (size <= index) add(bounds)
                                set(index, bounds)
                            }.take(displayLevels.size)
                        }
                    }
                    .semantics { onClick { onZoomSelected(ratio); true } },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (isSelected) Color.White else Color(0xFFBBBBBB),
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}
