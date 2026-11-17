package com.vayunmathur.maps.ui
import com.vayunmathur.maps.data.Point
import com.vayunmathur.maps.util.OfflineRouter
import com.vayunmathur.maps.platform.TransitStopsViewModel

import androidx.compose.runtime.Composable
import com.vayunmathur.maps.data.Feature1
import com.vayunmathur.maps.data.string
import com.vayunmathur.maps.data.transit.TransitStop

/** Pin id — hit-tested in MapSurface.onMapClickWithScreen so a tapped transit stop
 *  opens its departure board. */
const val TRANSIT_STOP_LAYER_ID = "transit-stop-pins"

/**
 * Baked GTFS stop layer — currently a NO-OP on the phone map (renderer gap, see below).
 * Kept (rather than deleted) so the tap priority ([MapFeaturePicker]), the departure-board
 * path ([toTransitStop], `TransitStopsViewModel.openStop/openNearestStop`) and the Transit
 * toggle wiring all survive the swap untouched.
 *
 * GAP (reported to lead; symbol-renderer owns the renderer API): stops used to render
 * straight from the `transit_stops` source-layer in the overlay PMTiles archive, and
 * library:map has no vector-layer API — so there is nothing to draw them with. The offline
 * `.transit` pack the departure board reads ([OfflineRouter.nearestStop]) answers point
 * queries instead. Mounted only when the Transit layer toggle is on; a tap →
 * the [DeparturesSheet] via [toTransitStop].
 */
@Composable
fun TransitStopsLayer() {
    // Intentionally empty until the renderer can draw vector source-layers.
}

/**
 * Convert a hit-tested transit-stop feature back into a [TransitStop] so the tap
 * handler can open the departure board.
 *
 * Coordinates come from the point geometry, not from properties: a baked tile
 * carries no `lat`/`lng` fields, only the geometry the tiler wrote. [TransitStop.id]
 * takes `motis_id`, which is what the realtime board queries; a stop from a feed
 * whose Transitous source name the build did not know has none, and falls back to
 * its name so the offline board still opens.
 */
fun Feature1.toTransitStop(): TransitStop? {
    val props = properties ?: return null
    val position = (geometry as? com.vayunmathur.maps.data.Point)?.coordinates ?: return null
    val name = props.string("name")?.ifBlank { null }
    val motisId = props.string("motis_id")?.ifBlank { null }
    val id = motisId ?: name ?: return null
    return TransitStop(
        id = id,
        name = name ?: id,
        lat = position.latitude,
        lon = position.longitude,
    )
}
