package com.vayunmathur.maps.ui.streetview
import com.vayunmathur.maps.data.nearest
import com.vayunmathur.maps.data.string

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.IconDirectionsWalk
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.maps.R
import com.vayunmathur.maps.data.google.StreetViewDataSource
import com.vayunmathur.maps.ui.theme.MapChromeMetrics
import kotlinx.coroutines.launch

/** Side of the peg chip, and of the ghost that follows the finger. */
private val PEG_SIZE = 48.dp

/**
 * The Street View peg: a control you drag off the map chrome and drop on a point to
 * be placed there. This is the app's only entry into Street View.
 *
 * It must be a **direct child of the same box `MapSurface` fills**, because the drop
 * point is read in this composable's own coordinate space and handed straight to the
 * camera's projection — hosting it in an inset box would offset every drop by that
 * inset.
 *
 * COVERAGE IS NOT SHOWN WHILE DRAGGING. [StreetViewDataSource] exposes coverage only
 * as a per-point nearest-pano lookup over the network, not as a tile layer, so there
 * is nothing to shade the map with and probing each drag position would be a request
 * per frame. The drop resolves instead, and a point with no imagery says so rather
 * than opening an empty viewer.
 */
@Composable
fun BoxScope.StreetViewPegman(camera: CameraState, modifier: Modifier = Modifier, onOpenStreetView: (Double, Double) -> Unit = { _, _ -> }) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val messenger = rememberMessenger()
    val noImagery = stringResource(R.string.street_view_unavailable)

    // Both in root coordinates, so their difference is the chip's position in this
    // box however many padding nodes the chip's modifier chain adds.
    var boxRoot by remember { mutableStateOf(Offset.Zero) }
    var chipRoot by remember { mutableStateOf(Offset.Zero) }

    // Where the finger is, in this box's coordinates. Null when not dragging.
    var dragPoint by remember { mutableStateOf<Offset?>(null) }
    var resolving by remember { mutableStateOf(false) }

    fun drop(point: Offset) {
        val projection = camera.projection ?: return
        val target = with(density) {
            projection.positionFromScreenLocation(DpOffset(point.x.toDp(), point.y.toDp()))
        }
        resolving = true
        scope.launch {
            val found = StreetViewDataSource.nearest(target.latitude, target.longitude)
            resolving = false
            if (found == null) messenger.show(noImagery) else onOpenStreetView(target.latitude, target.longitude)
        }
    }

    Box(
        modifier
            .matchParentSize()
            .onGloballyPositioned { boxRoot = it.positionInRoot() }
    ) {
        PegChip(
            Modifier
                .align(Alignment.CenterEnd)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(MapChromeMetrics.chromeMargin)
                .onGloballyPositioned { chipRoot = it.positionInRoot() }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { start -> dragPoint = chipRoot - boxRoot + start },
                        onDrag = { change, delta ->
                            change.consume()
                            dragPoint = dragPoint?.plus(delta)
                        },
                        onDragEnd = {
                            dragPoint?.let { drop(it) }
                            dragPoint = null
                        },
                        onDragCancel = { dragPoint = null },
                    )
                }
        )

        // The ghost is centred on the finger, so the drop point is what is under it.
        dragPoint?.let { point ->
            val half = with(density) { (PEG_SIZE / 2).roundToPx() }
            PegChip(Modifier.offset { IntOffset(point.x.toInt() - half, point.y.toInt() - half) })
        }

        if (resolving) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }

    }
}

@Composable
private fun PegChip(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.street_view_peg)
    Surface(
        modifier = modifier.size(PEG_SIZE).semantics { contentDescription = label },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        shadowElevation = 6.dp,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            IconDirectionsWalk()
        }
    }
}
