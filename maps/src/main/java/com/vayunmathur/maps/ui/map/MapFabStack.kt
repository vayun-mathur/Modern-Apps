package com.vayunmathur.maps.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconMyLocation
import com.vayunmathur.library.ui.Text
import com.vayunmathur.maps.ui.CompassButton
import com.vayunmathur.maps.ui.LayersButton
import com.vayunmathur.maps.ui.MapScaleBar
import com.vayunmathur.maps.ui.theme.MapChromeMetrics
import com.vayunmathur.maps.R as MapsR

/**
 * The scale bar bound to the live camera, scoped so a pan/zoom recomposes only this leaf.
 *
 * Reading `camera.position` here (rather than in `MapContentBox`, which hosts the whole map
 * chrome) keeps drag frames from recomposing the map surface, sheets, search bar and FAB
 * column: `CameraState` itself is a stable holder, and only this tiny leaf subscribes to its
 * `position` snapshot state. Passing `camera` down costs no subscription; reading
 * `camera.position` inside this body is what subscribes — deliberately isolated here.
 */
@Composable
private fun BoxScope.CameraScaleBar(camera: CameraState, lift: () -> Int = { 0 }) {
    MapScaleBar(
        zoom = camera.position.zoom,
        latitude = camera.position.target.latitude,
        modifier = Modifier
            .align(Alignment.BottomStart)
            // Lifted like the column: the search bar spans the full width of the bottom, so
            // anything anchored down there is behind it otherwise.
            .offset { IntOffset(0, -lift()) }
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(MapChromeMetrics.chromeMargin),
    )
}

/**
 * The compass bound to the live camera bearing, scoped like [CameraScaleBar] so a rotate or
 * pan recomposes only this leaf.
 *
 * The bearing used to be read by the caller (`MapContentBox`) and passed in as a `Double`. That
 * read sat in `MapContentBox`'s own restart scope, so every camera change — every frame of a
 * drag, pinch or fling — recomposed the whole map box: the map surface call and its overlay
 * builders, the top bar, the chips, the search bar and this stack. Reading it here confines the
 * subscription to the one composable that draws it.
 */
@Composable
private fun CameraCompass(camera: CameraState, onResetNorth: () -> Unit) {
    CompassButton(bearing = camera.position.bearing, onResetNorth = onResetNorth)
}

/**
 * The map controls: a scale bar bottom-left, a FAB stack bottom-right.
 *
 * Stateless — every action is a lambda, so this file has no idea what a parking spot is or how
 * to animate a camera.
 *
 * Most of it is browse-only: navigation has its own controls, and a selected place hands the
 * bottom half of the screen to the sheet. **Layers is the exception** — which basemap and which
 * overlays are on is a question you also ask while looking at a place, so it stays and rides
 * above the sheet via [lift]. Keeping it in this one column rather than as its own floating
 * button is what preserves its position in the stack while browsing.
 */
@Composable
fun BoxScope.MapFabStack(
    /** Read only inside the scale-bar and compass leaves, never in this body or its caller. */
    camera: CameraState,
    /** Whether the browse-only controls are shown. Layers is drawn either way. */
    browsing: Boolean,
    /**
     * Pixels the sheet currently covers, from `FreeHeightSheetState.liftPx`. A lambda,
     * and read during layout, so tracking the sheet costs no recomposition.
     */
    lift: () -> Int,
    onResetNorth: () -> Unit,
    onLayers: () -> Unit,
    onParking: () -> Unit,
    onMyLocation: () -> Unit,
) {
    if (browsing) {
        CameraScaleBar(camera = camera, lift = lift)
    }
    Column(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            // Negated: liftPx is the sheet's visible height, not a distance from the top.
            .offset { IntOffset(0, -lift()) }
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(MapChromeMetrics.chromeMargin),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(MapChromeMetrics.fabSpacing),
    ) {
        // Compass on top and only while the map is rotated; layers in the middle;
        // my-location is the primary action, so it sits closest to the thumb.
        if (browsing) {
            CameraCompass(camera = camera, onResetNorth = onResetNorth)
        }
        LayersButton(onClick = onLayers)
        if (browsing) {
            // Parking memory (P9): with no saved spot a tap saves the current location; with one,
            // it recenters and opens the sheet ("find my car"). Both live in [onParking] because
            // which one happens depends on state this component does not own.
            FloatingActionButton(onClick = onParking) {
                Text(stringResource(MapsR.string.parking_pin_glyph))
            }
            FloatingActionButton(onClick = onMyLocation) {
                IconMyLocation()
            }
        }
    }
}
