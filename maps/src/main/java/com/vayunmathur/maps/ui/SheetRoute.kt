package com.vayunmathur.maps.ui
import com.vayunmathur.maps.platform.SelectedFeatureViewModel

import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.google.PoiSection
import com.vayunmathur.maps.domain.RouteService
import kotlinx.serialization.Serializable

/**
 * Destinations inside [Route.MapPage]: the sheet stack.
 *
 * Everything that decides which sheet, panel or modal is showing over the
 * map is a leaf of this stack (Browse root plus at most a few pushed
 * sheets). Back is a stack pop; Directions pushes [RoutePlan] over
 * [Place]; a tapped place while a route is up pushes [Place] over
 * [RoutePlan], and popping it returns to the route — the parked-route
 * state this replaces was just stack depth held by hand.
 *
 * Routes carry the identity of the feature (the feature itself, which is
 * already serializable), never enrichment: [SelectedFeatureViewModel]
 * re-keys `currentPoiInfo` / `routes` off the top of this stack.
 */
@Serializable
sealed interface SheetRoute : NavKey {
    /** Browsing: no sheet up. */
    @Serializable
    data object Browse : SheetRoute

    /** A place sheet. [section] is the Details/Photos tab of this sheet. */
    @Serializable
    data class Place(
        val feature: SpecificFeature,
        val section: PoiSection = PoiSection.DETAILS,
    ) : SheetRoute

    /** A tapped admin (country/state/city) label sheet. */
    @Serializable
    data class AdminLabel(val feature: SpecificFeature) : SheetRoute

    /** Directions planning for [waypoints]; [mode] is the visible tab. */
    @Serializable
    data class RoutePlan(
        val waypoints: List<SpecificFeature.RoutableFeature?>,
        val mode: RouteService.TravelMode = RouteService.TravelMode.DRIVE,
    ) : SheetRoute

    /** Search sheet, optionally editing waypoint [waypointIndex]. */
    @Serializable
    data class Search(
        val waypointIndex: Int? = null,
        val nearLat: Double = 0.0,
        val nearLon: Double = 0.0,
    ) : SheetRoute

    /** Transit departure board (dialog destination). */
    @Serializable
    data class Departures(
        val stopId: String,
        val name: String,
        val lat: Double,
        val lon: Double,
    ) : SheetRoute

    /** A vehicle's trip sheet (dialog destination, over [Departures]). */
    @Serializable
    data class Trip(val vehicleId: Long, val lat: Double, val lon: Double) : SheetRoute

    /** Map-layers sheet (dialog destination). */
    @Serializable
    data object Layers : SheetRoute

    /** Saved-parking sheet (dialog destination). */
    @Serializable
    data object Parking : SheetRoute
}

/** The feature the sheet stack is showing, or null while browsing. */
fun List<SheetRoute>.effectiveFeature(): SpecificFeature? = reversed().firstNotNullOfOrNull { route ->
    when (route) {
        is SheetRoute.Place -> route.feature
        is SheetRoute.AdminLabel -> route.feature
        is SheetRoute.RoutePlan -> SpecificFeature.Route(route.waypoints)
        else -> null
    }
}

/** The topmost route plan, including one parked under a place. */
fun List<SheetRoute>.routePlan(): SheetRoute.RoutePlan? =
    reversed().filterIsInstance<SheetRoute.RoutePlan>().firstOrNull()

/** The route plan directly under a top place, if the place was tapped over one. */
fun List<SheetRoute>.parkedRoutePlan(): SheetRoute.RoutePlan? =
    if (lastOrNull() is SheetRoute.Place || lastOrNull() is SheetRoute.AdminLabel) {
        dropLast(1).routePlan()
    } else {
        null
    }

/** True when [route] is a dialog destination rendered over the map/sheet. */
fun SheetRoute.isDialogDestination(): Boolean =
    this is SheetRoute.Departures || this is SheetRoute.Trip ||
        this is SheetRoute.Layers || this is SheetRoute.Parking

/**
 * Select [feature] on the stack (map taps, search picks, deep links).
 *
 * A place tapped while a route plan is up is pushed over it (the route is
 * parked as stack depth); a place tapped while another place/admin sheet
 * is up replaces it in place, so repeated taps do not grow the stack.
 * An open search is superseded by any map selection.
 */
fun NavBackStack<SheetRoute>.selectFeature(feature: SpecificFeature) {
    if (last() is SheetRoute.Search) pop()
    when (feature) {
        is SpecificFeature.Route -> {
            val plan = SheetRoute.RoutePlan(feature.waypoints)
            if (last() is SheetRoute.RoutePlan) setLast(plan) else add(plan)
        }
        is SpecificFeature.Admin0Label, is SpecificFeature.Admin1Label, is SpecificFeature.Admin2Label -> {
            val label = SheetRoute.AdminLabel(feature)
            if (last() is SheetRoute.Place || last() is SheetRoute.AdminLabel) setLast(label) else add(label)
        }
        else -> {
            val place = SheetRoute.Place(feature)
            if (last() is SheetRoute.Place || last() is SheetRoute.AdminLabel) setLast(place) else add(place)
        }
    }
}

/**
 * Directions to [destination] from the current top place.
 *
 * Over a parked route plan this appends the destination to that plan and
 * pops back to it; otherwise it pushes a fresh plan over the place, so
 * popping the plan returns to the place sheet.
 */
fun NavBackStack<SheetRoute>.directionsTo(destination: SpecificFeature.RoutableFeature) {
    val parked = backStack.parkedRoutePlan()
    if (parked != null) {
        pop()
        setLast(SheetRoute.RoutePlan(parked.waypoints + destination, parked.mode))
    } else {
        add(SheetRoute.RoutePlan(listOf(null, destination)))
    }
}

/** Pop dialogs/search/place/route one level; never pops the Browse root. */
fun NavBackStack<SheetRoute>.popSheet() {
    if (backStack.size > 1) pop()
}

/** The destination the VM flows key off: topmost Place/AdminLabel/RoutePlan. */
fun List<SheetRoute>.engineRoute(): SheetRoute? = reversed().firstOrNull {
    it is SheetRoute.Place || it is SheetRoute.AdminLabel || it is SheetRoute.RoutePlan
}

/** Replace the stack entry holding [old] with [replacement], keeping its slot. */
fun NavBackStack<SheetRoute>.replaceFeature(old: SpecificFeature, replacement: SpecificFeature) {
    val index = backStack.indexOfFirst { route ->
        (route is SheetRoute.Place && route.feature === old) ||
            (route is SheetRoute.AdminLabel && route.feature === old)
    }
    if (index < 0) return
    val current = backStack[index]
    set(
        index,
        when (current) {
            is SheetRoute.Place -> SheetRoute.Place(replacement, current.section)
            else -> SheetRoute.AdminLabel(replacement)
        },
    )
}

/** The feature a single route shows, or null for Browse/dialog/search. */
fun SheetRoute?.toFeature(): SpecificFeature? = when (this) {
    is SheetRoute.Place -> feature
    is SheetRoute.AdminLabel -> feature
    is SheetRoute.RoutePlan -> SpecificFeature.Route(waypoints)
    else -> null
}

/** The sheet route that shows [this] feature. */
fun SpecificFeature.toSheetRoute(): SheetRoute = when (this) {
    is SpecificFeature.Route -> SheetRoute.RoutePlan(waypoints)
    is SpecificFeature.Admin0Label, is SpecificFeature.Admin1Label, is SpecificFeature.Admin2Label ->
        SheetRoute.AdminLabel(this)
    else -> SheetRoute.Place(this)
}
