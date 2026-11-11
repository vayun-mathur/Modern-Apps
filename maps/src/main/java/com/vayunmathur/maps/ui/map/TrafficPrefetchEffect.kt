package com.vayunmathur.maps.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.maps.util.visibleBoundsOrWorld
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * Prefetch traffic for the visible 1° squares once the camera settles, so the
 * overlay has data independent of route search (today traffic is fetched only
 * during a route search). The native side dedups squares for the session, so
 * a re-pan over an already-fetched square is a cheap no-op and the fetched
 * data is kept. Keyed on the toggle so it stops when traffic is off.
 * `collectLatest { delay() }` is the repo's debounce idiom (see
 * PoiMapViewModel): a newer camera position cancels the pending delay,
 * so the fetch only fires after the camera stops moving.
 *
 * Extracted from MapSurface so no ui file exceeds the [FileLength] limit.
 */
@Composable
fun TrafficPrefetchEffect(camera: CameraState, trafficEnabled: Boolean) {
    LaunchedEffect(camera, trafficEnabled) {
        if (!trafficEnabled) return@LaunchedEffect
        snapshotFlow { camera.position }.collectLatest {
            delay(TRAFFIC_PREFETCH_DEBOUNCE_MS)
            // The component overlay is dense and zoom-gated in the archive/renderer, and a
            // world-zoom viewport would enumerate hundreds of 1° squares, so only prefetch
            // once zoomed in enough for it to be useful.
            if (camera.position.zoom < TRAFFIC_MIN_ZOOM) return@collectLatest
            prefetchTrafficSquares(camera.visibleBoundsOrWorld())
        }
    }
}
