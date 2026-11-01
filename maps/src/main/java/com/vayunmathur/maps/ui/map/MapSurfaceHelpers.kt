package com.vayunmathur.maps.ui.map

import androidx.compose.ui.graphics.toArgb
import com.vayunmathur.library.map.GeoBounds
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.MapMarker
import com.vayunmathur.library.map.MarkerIcon
import com.vayunmathur.library.map.TrafficColorTable
import com.vayunmathur.maps.data.Feature1
import com.vayunmathur.maps.data.ParkingSpot
import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.ipc.FamilyMember
import com.vayunmathur.maps.ui.FAMILY_LOCATION_LAYER_ID
import com.vayunmathur.maps.ui.PARKING_PIN_LAYER_ID
import com.vayunmathur.maps.ui.SAVED_PLACE_LAYER_ID
import com.vayunmathur.maps.ui.SEARCH_RESULT_LAYER_ID
import com.vayunmathur.maps.ui.toSelectedFamilyMember
import com.vayunmathur.maps.ui.toSelectedSavedPlace
import com.vayunmathur.maps.ui.toSelectedSearchResult
import com.vayunmathur.maps.ui.theme.MapTokens
import com.vayunmathur.maps.util.OfflineRouter
import com.vayunmathur.maps.util.OfflineRouterTraffic
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import com.vayunmathur.maps.util.TransitStopsViewModel
import com.vayunmathur.maps.util.PoiCategories
import com.vayunmathur.maps.util.SearchResult
import kotlin.math.floor

/**
 * OSM station-ish POI type whose taps open the departure board. Station POIs carry no
 * stop id of their own; see `TransitStopsViewModel.openNearestStop`.
 */
internal const val STATION_POI_TYPE = PoiCategories.STATION_TYPE

/** A pin feature tagged with the probe layer it belongs to. */
internal data class TaggedFeature(val layerId: String, val feature: Feature1)

/**
 * How long the camera must be still before a traffic prefetch fires. Matches the browse-time
 * debounce the POI driver uses; long enough that a fling does not fetch every square it flies
 * over, short enough that the overlay fills in promptly once you stop.
 */
internal const val TRAFFIC_PREFETCH_DEBOUNCE_MS = 400L

/** Scale of the wire's `u8 = round(speedRatio*100)` ratio byte. */
private const val TRAFFIC_RATIO_SCALE = 100.0
/** Mask for one unsigned byte read out of the table. */
private const val BYTE_MASK = 0xFF
/** Below this ratio a segment reads as jammed; below [TRAFFIC_SLOW_BELOW] as slow. */
private const val TRAFFIC_JAM_BELOW = 0.5
private const val TRAFFIC_SLOW_BELOW = 0.9

/**
 * Below this zoom the per-component traffic overlay is neither drawn (the archive zoom-gates
 * it) nor worth fetching — a zoomed-out viewport spans too many 1° squares to enumerate.
 */
internal const val TRAFFIC_MIN_ZOOM = 11.0

/**
 * Safety cap on how many 1° squares a single settle may request, so an unexpectedly wide
 * viewport (near the antimeridian, or a viewport measured before the zoom gate applies) can
 * never fan out into hundreds of fetches. At [TRAFFIC_MIN_ZOOM] a settled viewport is a
 * handful of squares, well under this.
 */
internal const val TRAFFIC_MAX_SQUARES = 16L

/**
 * Ask the native prefetch to load traffic for every 1° square the viewport touches.
 *
 * [ensureTrafficLoadedNative] re-derives the packed square from the point and dedups, so
 * passing each cell's centre is enough and repeats are free. A viewport that would span more
 * than [TRAFFIC_MAX_SQUARES] cells (or an inverted/antimeridian box) is skipped rather than
 * enumerated.
 */
internal fun prefetchTrafficSquares(bounds: GeoBounds) {
    val lonMin = floor(bounds.west).toInt()
    val lonMax = floor(bounds.east).toInt()
    val latMin = floor(bounds.south).toInt()
    val latMax = floor(bounds.north).toInt()
    val cells = (lonMax - lonMin + 1).toLong() * (latMax - latMin + 1).toLong()
    if (cells < 1L || cells > TRAFFIC_MAX_SQUARES) return
    for (lat in latMin..latMax) {
        for (lon in lonMin..lonMax) {
            OfflineRouter.ensureTrafficLoadedNative(lat + 0.5, lon + 0.5, true)
        }
    }
}

/**
 * Resolve the component-level traffic readings into an id→ARGB table for the renderer.
 *
 * Buckets mirror `staticColorFor` in `RouteOverlayBuilder`: `ratio < 0.5` jam, `< 0.9` slow,
 * else free, where `ratio = ratio_pct / 100`. `ratio_pct == 0` is "no data" and is dropped — an id
 * absent from the table draws nothing, so those segments fall back to the plain basemap road.
 * Colours come from [tokens], which already encodes the light/dark palette, so the renderer
 * only looks them up. Returns null when nothing is left to draw.
 */
internal fun buildTrafficColorTable(
    components: OfflineRouterTraffic.TrafficComponents,
    tokens: MapTokens,
): TrafficColorTable? {
    val n = components.ids.size
    if (n == 0) return null
    val outIds = LongArray(n)
    val outArgb = IntArray(n)
    var k = 0
    for (i in 0 until n) {
        val pct = components.ratioPct[i].toInt() and BYTE_MASK
        if (pct == 0) continue
        val ratio = pct / TRAFFIC_RATIO_SCALE
        val color = when {
            ratio < TRAFFIC_JAM_BELOW -> tokens.traffic.jam
            ratio < TRAFFIC_SLOW_BELOW -> tokens.traffic.slow
            else -> tokens.traffic.free
        }
        outIds[k] = components.ids[i]
        outArgb[k] = color.toArgb()
        k++
    }
    if (k == 0) return null
    return TrafficColorTable(outIds.copyOf(k), outArgb.copyOf(k))
}

/**
 * The tappable pin set: every Compose-drawn pin as the [Feature1] its resolver
 * (`toSelected*`) already understands, tagged for [MapFeaturePicker]'s per-layer
 * probes. Built from the same inputs the layers draw from, so the hit-test can never
 * disagree with what is on screen.
 *
 * ## Known limitation: renderer transit lines are not tappable
 *
 * The transit toggle now drives the renderer's own transit layer, so rail and route lines
 * are drawn in the basemap. Those are **geometry, not pins** — they have no Compose
 * counterpart here, and the native pick answers placed *labels* only, which is why
 * extending [MapFeaturePicker.NATIVE_LABEL_LAYER_IDS] would not reach them either
 * (`toFeature1` resolves admin place ids and nothing else). Tapping a rail line therefore
 * falls through to reverse-geocode, exactly as tapping empty basemap does. Live-departure
 * stop pins are unaffected: those are Compose-drawn and still probe normally.
 */
internal fun pinFeatures(
    searchResults: List<SearchResult>,
    savedPlaces: List<SavedPlace>,
    parkingSpot: ParkingSpot?,
    familyMembers: List<FamilyMember>,
): List<TaggedFeature> = buildList {
    if (parkingSpot != null) {
        add(TaggedFeature(PARKING_PIN_LAYER_ID, parkingPinFeature(parkingSpot)))
    }
    for (result in searchResults) {
        add(TaggedFeature(SEARCH_RESULT_LAYER_ID, searchPinFeature(result)))
    }
    for (place in savedPlaces) {
        add(TaggedFeature(SAVED_PLACE_LAYER_ID, savedPinFeature(place)))
    }
    for (member in familyMembers) {
        add(TaggedFeature(FAMILY_LOCATION_LAYER_ID, familyPinFeature(member)))
    }
}

/**
 * The renderer marker set for the app's pins, plus a map from each marker's id to the [MapHit] a
 * tap on it means, resolved once here so the tap path is a lookup.
 *
 * The renderer draws these (billboarded, glued to the basemap); a tap reads back the marker's id
 * from the id buffer and this map turns it into the same [MapHit] the old CPU picker produced. Ids
 * are assigned per pin kind in disjoint ranges so they stay unique and never collide with the
 * pick-buffer's `0` = "nothing". A pin whose feature does not resolve (a malformed saved place, say)
 * is dropped rather than drawn as an untappable dot — the same outcome the CPU picker's
 * `firstNotNullOfOrNull` gave.
 */
internal fun buildMarkers(
    searchResults: List<SearchResult>,
    savedPlaces: List<SavedPlace>,
    parkingSpot: ParkingSpot?,
    familyMembers: List<FamilyMember>,
): Pair<List<MapMarker>, Map<Long, MapHit>> {
    val markers = ArrayList<MapMarker>()
    val hits = HashMap<Long, MapHit>()
    // Disjoint id ranges per kind: 1 for the single parking pin, then a decade each for the lists.
    var id = 1L
    if (parkingSpot != null) {
        markers.add(MapMarker(id, GeoPoint(parkingSpot.lon, parkingSpot.lat), MarkerIcon.PARKING))
        hits[id] = MapHit.Parking
        id++
    }
    id = 100_000L
    for (result in searchResults) {
        val place = searchPinFeature(result).toSelectedSearchResult() ?: continue
        markers.add(MapMarker(id, GeoPoint(result.lon, result.lat), MarkerIcon.SEARCH, label = result.title))
        hits[id] = MapHit.Place(place)
        id++
    }
    id = 200_000L
    for (saved in savedPlaces) {
        val place = savedPinFeature(saved).toSelectedSavedPlace() ?: continue
        markers.add(MapMarker(id, GeoPoint(saved.lon, saved.lat), MarkerIcon.SAVED, label = saved.name))
        hits[id] = MapHit.Place(place)
        id++
    }
    id = 300_000L
    for (member in familyMembers) {
        val place = familyPinFeature(member).toSelectedFamilyMember() ?: continue
        markers.add(MapMarker(id, GeoPoint(member.lng, member.lat), MarkerIcon.FAMILY, label = member.name))
        hits[id] = MapHit.Place(place)
        id++
    }
    return markers to hits
}

/**
 * The vehicle sprite under [offset], or null.
 *
 * Vehicles are deliberately outside the GPU pick path (a moving simulated
 * sprite is not a pin tap target), so this projects each pushed vehicle to
 * the screen on the CPU and takes the nearest inside the same tolerance box
 * the pin picker uses ([MapChromeMetrics.hitSlop]). Ranked below the app's
 * own pins by the caller.
 */
internal fun pickVehicle(
    vehicles: List<MapMarker>,
    projection: com.vayunmathur.library.map.Projection,
    offset: androidx.compose.ui.unit.DpOffset,
    slop: androidx.compose.ui.unit.Dp = com.vayunmathur.maps.ui.theme.MapChromeMetrics.hitSlop,
): MapMarker? {
    var best: MapMarker? = null
    var bestDist = slop.value * slop.value
    for (marker in vehicles) {
        val screen = try {
            projection.screenLocationFromPosition(marker.position)
        } catch (_: Exception) {
            continue
        }
        val dx = screen.x.value - offset.x.value
        val dy = screen.y.value - offset.y.value
        val dist = dx * dx + dy * dy
        if (dist <= bestDist) {
            bestDist = dist
            best = marker
        }
    }
    return best
}

/**
 * CPU hit-test over the app's pins: the fallback when the GPU id buffer misses (e.g. a pin
 * pushed this very frame, before the id buffer caught up). Tilt-aware via [projection].
 */
internal suspend fun pickPinOnCpu(
    offset: androidx.compose.ui.unit.DpOffset,
    projection: com.vayunmathur.library.map.Projection,
    searchResults: List<SearchResult>,
    savedPlaces: List<SavedPlace>,
    parkingSpot: ParkingSpot?,
    familyMembers: List<FamilyMember>,
    transitEnabled: Boolean,
): MapHit? {
    val pins = pinFeatures(
        searchResults = searchResults,
        savedPlaces = savedPlaces,
        parkingSpot = parkingSpot,
        familyMembers = familyMembers,
    )
    val picker = MapFeaturePicker(
        source = FeatureSource { box, layerIds ->
            featuresInBox(
                pins.filter { it.layerId in layerIds }.map { it.feature },
                box,
                projection,
            )
        },
        transitEnabled = transitEnabled,
    )
    return picker.pickPin(offset)
}

/**
 * Open the trip sheet for the vehicle sprite under [offset]. True when a
 * sprite was hit (the caller returns from the tap); false to fall through to
 * ambient furniture. Replaces any place sheet rather than stacking it.
 */
internal suspend fun openVehicleTrip(
    vehicles: List<MapMarker>,
    projection: com.vayunmathur.library.map.Projection,
    offset: androidx.compose.ui.unit.DpOffset,
    viewModel: SelectedFeatureViewModel,
    transitViewModel: TransitStopsViewModel,
    sheetState: com.vayunmathur.library.ui.FreeHeightSheetState?,
): Boolean {
    val marker = pickVehicle(vehicles, projection, offset) ?: return false
    viewModel.set(null)
    transitViewModel.openTrip(
        marker.id,
        marker.position.latitude,
        marker.position.longitude,
    )
    sheetState?.partialExpand()
    return true
}

/**
 * The selected label's outline, dimmed outside. Derived from the sheet's own
 * selection rather than the tap, so a region picked from search masks too — and the
 * mask is the label's baked `regionId`, so no point/level guess: linked masks exactly
 * that region, unlinked (`0` / absent) masks nothing. A label with no position
 * cannot be placed on the map, so it masks nothing even when linked.
 */
internal fun regionMaskFor(
    selectedFeature: com.vayunmathur.maps.data.SpecificFeature?,
): Long? = when (selectedFeature) {
    is com.vayunmathur.maps.data.SpecificFeature.Admin0Label ->
        selectedFeature.regionId.takeIf { it != 0L && selectedFeature.position != null }
    is com.vayunmathur.maps.data.SpecificFeature.Admin1Label ->
        selectedFeature.regionId.takeIf { it != 0L && selectedFeature.position != null }
    is com.vayunmathur.maps.data.SpecificFeature.Admin2Label ->
        selectedFeature.regionId.takeIf { it != 0L && selectedFeature.position != null }
    else -> null
}
