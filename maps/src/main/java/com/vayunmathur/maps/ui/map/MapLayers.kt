package com.vayunmathur.maps.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.maps.ui.RoadsLayer
import com.vayunmathur.maps.ui.SatelliteLayer
import com.vayunmathur.maps.ui.TransitStopsLayer
import com.vayunmathur.maps.data.OfflineRouterLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The overlay layers over the Vulkan basemap that are still plain Compose.
 *
 * The app's own **pins** (saved places, search results, family members, parking) are no longer
 * here: they moved into the renderer as billboarded markers ([com.vayunmathur.library.map.MapMarker],
 * pushed from [MapSurface]), which is what stopped them trailing the basemap on a pan or a tilt.
 * Their taps resolve through the renderer's id buffer
 * ([com.vayunmathur.library.map.Projection.pickMarker]), so the Compose hit-test that rebuilt them
 * is gone too — see [MapSurface].
 *
 * The user puck and the navigation route moved into the renderer earlier, for the same reason (see
 * [com.vayunmathur.library.map.UserPuck] and [com.vayunmathur.library.map.RouteOverlay]).
 *
 * What is still Compose here draws no pins, so none of it lags a pan: satellite imagery, and the
 * roads/transit-stop overlays that remain **no-ops** until the renderer grows a vector
 * source-layer API (reported to lead — symbol-renderer owns that). The basemap draws its own road
 * network and ambient POIs regardless; POI taps arrive as `MapClick.poi`.
 *
 * Traffic and safety are not here: the renderer draws both baked layers, gated by their
 * toggles through `LayerOptions` in [MapSurface].
 */
@Composable
fun MapLayers(
    satelliteEnabled: Boolean = false,
    transitEnabled: Boolean = false,
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        // OfflineRouterLifecycle.initialize does asset-listing I/O — push to IO. The
        // @Synchronized fun itself is idempotent so recomposition is safe.
        withContext(Dispatchers.IO) {
            OfflineRouterLifecycle.initialize(context)
        }
    }

    // Satellite / aerial imagery. Gated: renders nothing until a raster tile source
    // is hosted. Drawn first so it sits beneath the overlays.
    SatelliteLayer(satelliteEnabled)

    // Our own road rendering — no-op until the renderer can draw vector source-layers.
    RoadsLayer()

    if (transitEnabled) {
        // Baked GTFS stop pins. No-op until the renderer can draw vector source-layers;
        // tap a stop → live departure board (handled in MapSurface.onMapClick).
        TransitStopsLayer()
    }
}
