package com.vayunmathur.maps.platform
import com.vayunmathur.maps.data.planWithAlternatives
import com.vayunmathur.maps.data.OfflineRouterRoadRoutes
import com.vayunmathur.maps.domain.AlternativeRoutes
import com.vayunmathur.maps.domain.RouteService
import android.app.Application
import android.hardware.SensorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.maps.data.Feature1
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.osmPlace
import com.vayunmathur.maps.data.google.GooglePoiDataSource
import com.vayunmathur.maps.data.google.GooglePoiInfo
import com.vayunmathur.maps.data.parse
import com.vayunmathur.maps.Route
import com.vayunmathur.maps.ui.SheetRoute
import com.vayunmathur.maps.ui.effectiveFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.maps.data.MapPreferences

/** Selected Feature View Model: a application implementation for selected feature view model. */
class SelectedFeatureViewModel(application: Application): AndroidViewModel(application) {
    /**
     * The sheet-stack destination the derived flows are keyed off: the top
     * Place/AdminLabel/RoutePlan of the MapPage sheet stack, synced from
     * composition by MapPage. This is an engine input (which place to
     * enrich, which waypoints to plan), not selection state — the stack
     * in the UI owns what is showing.
     */
    private val _sheetRoute = MutableStateFlow<SheetRoute?>(null)
    val sheetRoute = _sheetRoute.asStateFlow()

    fun setSheetRoute(route: SheetRoute?) {
        _sheetRoute.value = route
    }

    /** A place a non-composition caller (deep link, contact shortcut) asked to open. */
    private val _pendingPlace = MutableStateFlow<SpecificFeature?>(null)
    val pendingPlace = _pendingPlace.asStateFlow()

    fun consumePendingPlace() {
        _pendingPlace.value = null
    }

    /** A [Route.Navigating] a non-composition caller asked to push. */
    private val _pendingNavigating = MutableStateFlow<Route.Navigating?>(null)
    val pendingNavigating = _pendingNavigating.asStateFlow()

    fun requestNavigating(destLabel: String, mode: RouteService.TravelMode) {
        _pendingNavigating.value = Route.Navigating(destLabel, mode)
    }

    fun consumeNavigating() {
        _pendingNavigating.value = null
    }

    private companion object {
        /**
         * Minimum movement (metres, equirectangular — fine at this scale)
         * before the persisted position is rewritten.
         */
        private const val MIN_PERSIST_DISTANCE_M = 50.0

        /** Equirectangular distance in metres — fine at the 50 m gate scale. */
        private fun distanceM(a: GeoPoint, b: GeoPoint): Double {
            val dx = (a.longitude - b.longitude) * 111_320.0 *
                kotlin.math.cos(Math.toRadians((a.latitude + b.latitude) / 2.0))
            val dy = (a.latitude - b.latitude) * 110_540.0
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }
    }

    /** A pending request for the map to fly to [position] (at [zoom] when set) and
     *  show the place bottom PANE (peek), the place card. Backed by a
     *  StateFlow so a request made before MapPage is composed (a cold-start deep
     *  link) survives until the map consumes it via [consumeFocus]. */
    data class PlaceFocus(val position: GeoPoint, val zoom: Double? = null)

    private val _pendingFocus = MutableStateFlow<PlaceFocus?>(null)
    val pendingFocus = _pendingFocus.asStateFlow()

    private val _userPosition = MutableStateFlow(GeoPoint(0.0, 0.0))
    val userPosition = _userPosition.asStateFlow()

    private val _userBearing = MutableStateFlow<Float?>(null)
    val userBearing = _userBearing.asStateFlow()

    /** Latest GPS speed in m/s (0 when the fix carries none), for the ambient speed readout. */
    private val _userSpeedMps = MutableStateFlow(0f)
    val userSpeedMps = _userSpeedMps.asStateFlow()

    // Magnetometer accuracy backing the compass calibration banner. Defaults to
    // HIGH so the banner stays hidden until the sensor reports a lower value.
    private val _userHeadingAccuracy = MutableStateFlow(SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
    val userHeadingAccuracy = _userHeadingAccuracy.asStateFlow()

    val locationManager = FrameworkLocationManager(application)

    private val dataStore = DataStoreUtils.getInstance(application)

    /** Synchronous Google-services gate (off by default): no opt-in, no fetch. */
    private fun googleServicesEnabled(): Boolean =
        dataStore.getBoolean(
            MapPreferences.KEY_GOOGLE_SERVICES,
            MapPreferences.DEFAULT_GOOGLE_SERVICES,
        )

    /**
     * Last fix persisted, throttled by distance — DataStore rewrites the whole
     * file per edit, so a standing-still phone should not rewrite it every second.
     */
    private var lastPersisted: GeoPoint? = null

    init {
        locationManager.startUpdates(
            onUpdateReceived = { position, bearing, speedMps ->
                _userPosition.value = position
                _userBearing.value = bearing
                _userSpeedMps.value = speedMps
                persistPosition(position)
            },
            onAccuracyReceived = { accuracy ->
                _userHeadingAccuracy.value = accuracy
            },
        )
    }

    /**
     * Persist the live fix for the next cold start, throttled by distance so a
     * stationary phone does not rewrite DataStore every second. Fire-and-forget
     * on `viewModelScope`: a lost write only costs one stale open.
     */
    private fun persistPosition(position: GeoPoint) {
        // (0,0) is this VM's "no fix" sentinel — never persist it over a real fix.
        if (position.latitude == 0.0 && position.longitude == 0.0) return
        val previous = lastPersisted
        if (previous != null && distanceM(previous, position) < MIN_PERSIST_DISTANCE_M) return
        lastPersisted = position
        viewModelScope.launch {
            runCatching {
                dataStore.setDouble(MapPreferences.KEY_LAST_LON, position.longitude)
                dataStore.setDouble(MapPreferences.KEY_LAST_LAT, position.latitude)
            }
        }
    }

    override fun onCleared() {
        // Unregister GPS + sensor listeners so the radio doesn't keep draining
        // battery after the user navigates away from the map module.
        locationManager.stop()
    }

    /**
     * Enrich a bare [SpecificFeature.GenericPlace] from the offline sidecar,
     * or null when it already carries OSM fields or the lookup finds none.
     * The caller (the sheet-stack host) writes the result back onto the
     * stack entry it came from.
     */
    suspend fun enrichedOrNull(place: SpecificFeature.GenericPlace): SpecificFeature.GenericPlace? {
        if (!needsEnrichment(place)) return null
        val enriched = runCatching { osmPlace(place.name, place.position, place.poiType) }.getOrNull()
            ?: return null
        return enriched.takeIf { hasAnyOsmField(it) }
    }

    private fun needsEnrichment(place: SpecificFeature.GenericPlace): Boolean =
        place.phone == null && place.website == null && place.openingHours == null && place.address == null

    private fun hasAnyOsmField(place: SpecificFeature.GenericPlace): Boolean =
        place.phone != null || place.website != null || place.openingHours != null || place.address != null

    /**
     * Select [feature] AND ask the map to fly to it and open the place bottom pane
     * (peek). This is the single "search → auto-select first / deep link → open a
     * place" path shared by the contact-address shortcut (P17) and the external
     * intent handler (geo:/maps links). Selecting a [SpecificFeature.RoutableFeature]
     * triggers [currentPoiInfo] enrichment so the pane fills with details.
     */
    /** Ask the map to fly to [feature] without changing the sheet stack. */
    fun focusFeature(feature: SpecificFeature, zoom: Double? = null) {
        val pos = (feature as? SpecificFeature.RoutableFeature)?.position
        _pendingFocus.value = pos?.let { PlaceFocus(it, zoom) }
    }

    fun selectAndFocus(feature: SpecificFeature, zoom: Double? = null) {
        _pendingPlace.value = feature
        val pos = (feature as? SpecificFeature.RoutableFeature)?.position
        _pendingFocus.value = pos?.let { PlaceFocus(it, zoom) }
    }

    /** Clear a consumed [pendingFocus] once the map has flown there + opened the pane. */
    fun consumeFocus() {
        _pendingFocus.value = null
    }

    /**
     * Resolve the first basemap place label that parses, or null.
     *
     * Lives here rather than in the tap handler so label resolution stays off the gesture
     * handler's thread. The native pick returns placed labels in placement order, so stopping
     * at the first success keeps a tap from resolving every candidate.
     */
    suspend fun resolveAdminLabel(candidates: List<Feature1>): SpecificFeature? =
        withContext(Dispatchers.IO) {
            candidates.firstNotNullOfOrNull { raw -> runCatching { parse(raw) }.getOrNull() }
        }

    /**
     * Keyless Google Maps place details (photos, hours/open status, phone, website, price,
     * popular times — never ratings or reviews, which come only from the archive's POI
     * attributes on the selected [SpecificFeature.GenericPlace]) for the currently selected
     * restaurant or generic place. Emits null for other feature types and while the fetch is
     * in flight; cancels the in-flight fetch when the selection changes.
     * [GooglePoiDataSource] caches and does its own IO, so a re-select is instant.
     *
     * Hard-gated on the Google services toggle: when off, emits null without
     * touching the network — zero Google traffic.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val currentPoiInfo: StateFlow<GooglePoiInfo?> = sheetRoute
        .flatMapLatest { route ->
            if (!googleServicesEnabled()) return@flatMapLatest flowOf(null)
            val feature = (route as? SheetRoute.Place)?.feature
            val (name, pos) = when (feature) {
                is SpecificFeature.Restaurant -> feature.name to feature.position
                is SpecificFeature.GenericPlace -> feature.name to feature.position
                else -> return@flatMapLatest flowOf(null)
            }
            if (name.isBlank()) return@flatMapLatest flowOf(null)
            flow {
                emit(null)
                emit(GooglePoiDataSource.fetch(name, pos.latitude, pos.longitude))
            }.flowOn(Dispatchers.IO)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    // Move heavy computation to a background StateFlow
    //
    // The selected tab's mode is computed first and emitted immediately, so
    // the visible route overlay resolves after one plan instead of four: the
    // old sequential loop emitted five maps (empty + one per mode) through
    // `scan`, and every emission re-resolved the route overlay/traffic tables
    // and re-pushed to the renderer. The remaining modes compute lazily off
    // the critical path and arrive in a single coalesced map. [selectedRouteMode]
    // is read once per route-plan change (not collected): the flow restarts
    // on the next plan change anyway, and collecting the tab here would
    // re-plan every mode on each tab switch.
    //
    // The scan seed: one null per travel mode, merged over as each plan lands.
    /**
     * Genuinely different alternatives for the visible plan's mode
     * (primary excluded), gated by [AlternativeRoutes]. Only the
     * selected mode is planned, so this costs one extra plan.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val alternatives: kotlinx.coroutines.flow.StateFlow<List<RouteService.Route>> = sheetRoute
        .flatMapLatest { route ->
            val plan = route as? SheetRoute.RoutePlan
                ?: return@flatMapLatest flowOf(emptyList())
            flow {
                emit(emptyList())
                emit(
                    planWithAlternatives(
                        getApplication(),
                        SpecificFeature.Route(plan.waypoints),
                        userPosition.value,
                        plan.mode,
                    ).drop(1),
                )
            }.flowOn(Dispatchers.Default)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList(),
        )

    private fun emptyRoutes(): Map<RouteService.TravelMode, RouteService.RouteType?> =
        RouteService.TravelMode.entries.associateWith { null as RouteService.RouteType? }
    @OptIn(ExperimentalCoroutinesApi::class)
    val routes = sheetRoute
        .flatMapLatest { route ->
            val pos = userPosition.value
            val plan = route as? SheetRoute.RoutePlan ?: return@flatMapLatest flowOf(null)
            val selectedMode = plan.mode
            val routeFeature = SpecificFeature.Route(plan.waypoints)

            flow {
                emit(emptyMap())

                suspend fun plan(mode: RouteService.TravelMode): RouteService.RouteType? =
                    try {
                        OfflineRouterRoadRoutes.getRouteForMode(application, routeFeature, pos, mode)
                    } catch (_: Exception) {
                        null
                    } ?: RouteService.EmptyRoute()

                // Offline-first routing with chained multi-waypoint support.
                // TRANSIT prefers the on-device RAPTOR planner over any
                // downloaded region index (P11d); if none covers the trip, it
                // falls back to the P10 online Transitous (MOTIS) planner.
                // getRouteForMode owns that split so the road graph, which has no
                // timetable, never sees TRANSIT.
                val first = plan(selectedMode)
                emit(mapOf(selectedMode to first))

                val rest = mutableMapOf<RouteService.TravelMode, RouteService.RouteType?>()
                for (mode in RouteService.TravelMode.entries) {
                    if (mode == selectedMode) continue
                    rest[mode] = plan(mode)
                }
                emit(mapOf(selectedMode to first) + rest)
            }
                .scan(emptyRoutes()) { accumulator, newEntry ->
                    accumulator + newEntry // Combine the old map with the new calculation
                }
                .flowOn(Dispatchers.Default)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )
}
