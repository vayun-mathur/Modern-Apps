package com.vayunmathur.maps.util
import android.app.Application
import android.hardware.SensorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.maps.data.Feature1
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.osmPlace
import com.vayunmathur.maps.data.google.GooglePoiDataSource
import com.vayunmathur.maps.data.google.GooglePoiInfo
import com.vayunmathur.maps.service.ReviewsScraperClient
import com.vayunmathur.maps.data.parse
import com.vayunmathur.maps.data.google.PoiSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
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

class SelectedFeatureViewModel(application: Application): AndroidViewModel(application) {
    private val _selectedFeature = MutableStateFlow<SpecificFeature?>(null)
    val selectedFeature = _selectedFeature.asStateFlow()

    private val _inactiveNavigation = MutableStateFlow<SpecificFeature.Route?>(null)
    val inactiveNavigation = _inactiveNavigation.asStateFlow()

    /**
     * Which of the place sheet's Details / Photos / Reviews tabs is showing.
     *
     * On the ViewModel rather than remembered in the sheet because the tab row and the panel it
     * switches are no longer the same composable: the row is part of the sheet's measured header,
     * so that it is visible at the peek and the other tabs are discoverable without expanding,
     * and the panel is below the fold. Two sibling slots cannot share a `remember`, and this is
     * selection-scoped state that already has a home — which also means the reset lives with the
     * selection it belongs to instead of a `remember(key)` in the UI.
     */
    private val _poiSection = MutableStateFlow(PoiSection.DETAILS)
    val poiSection = _poiSection.asStateFlow()

    fun setPoiSection(section: PoiSection) {
        _poiSection.value = section
    }

    /**
     * The route tab the sheet is showing (DRIVE by default). Read once per
     * selection by [routes] so the visible tab's mode plans first; the flow
     * restarts on the next selection change anyway, so this is a plain var
     * rather than a collected flow (collecting it would re-plan every mode
     * on each tab switch). Synced from the chrome tab in MapPage.
     */
    var selectedRouteMode: RouteService.TravelMode = RouteService.TravelMode.DRIVE

    /**
     * Coalescing gates for the review-streaming partials in [currentPoiInfo]:
     * forward a partial only when the list grew by [PARTIAL_MIN_GROWTH]
     * reviews or [PARTIAL_MIN_INTERVAL_MS] elapsed since the last forward.
     * Dozens of per-parse emissions used to recompose the sheet for a list
     * the user barely sees change; the authoritative final send still always
     * runs, so a dropped partial loses nothing.
     */
    private companion object {
        const val PARTIAL_MIN_GROWTH = 5
        const val PARTIAL_MIN_INTERVAL_MS = 2_000L

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

    // Hidden-WebView reviews scraper (keyless; Google 404'd the old reviews RPC), run in the
    // `:reviews` process so Chromium never maps into this one.
    private val webReviews = ReviewsScraperClient(application)

    init {
        locationManager.startUpdates(
            onUpdateReceived = { position, bearing ->
                _userPosition.value = position
                _userBearing.value = bearing
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
     * The single choke point every selection flows through. Most selection paths construct a bare
     * `GenericPlace(name, null, null, null, pos)` and bypass the `poi_attrs.bin` sidecar lookup,
     * so an unenriched place is re-enriched here: the sheet opens instantly with the title, then
     * the OSM fields (hours, phone, website, address) fill in when the lookup lands.
     *
     * The identity check keeps a slow lookup from overwriting a newer selection, and the enriched
     * write goes through [setDirect] rather than [set] so it cannot recurse. Restaurants already
     * carry their OSM fields from their own construction path and are left alone.
     */
    fun set(feature: SpecificFeature?) {
        setDirect(feature)
        val place = feature as? SpecificFeature.GenericPlace ?: return
        if (!needsEnrichment(place)) return
        viewModelScope.launch {
            val enriched = runCatching { osmPlace(place.name, place.position, place.poiType) }.getOrNull()
                ?: return@launch
            if (hasAnyOsmField(enriched) && _selectedFeature.value === feature) {
                setDirect(enriched)
            }
        }
    }

    private fun setDirect(feature: SpecificFeature?) {
        _selectedFeature.value = feature
        _poiSection.value = PoiSection.DETAILS
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
    fun selectAndFocus(feature: SpecificFeature, zoom: Double? = null) {
        set(feature)
        val pos = (feature as? SpecificFeature.RoutableFeature)?.position
        _pendingFocus.value = pos?.let { PlaceFocus(it, zoom) }
    }

    /** Clear a consumed [pendingFocus] once the map has flown there + opened the pane. */
    fun consumeFocus() {
        _pendingFocus.value = null
    }

    fun setInactiveNavigation(route: SpecificFeature.Route?) {
        _inactiveNavigation.value = route
    }

    /**
     * Park the current route, if the selection is one, before selecting something else.
     *
     * Tapping a place while directions are up should keep the route recoverable rather than
     * discard it, which is why every such tap handler did this. They each did it with an
     * unchecked `as SpecificFeature.Route` guarded by a separate `is` check, so the cast was
     * only safe by inspection; reading the selection once here makes it safe by construction.
     */
    fun stashRouteSelection() {
        (_selectedFeature.value as? SpecificFeature.Route)?.let { _inactiveNavigation.value = it }
    }

    /**
     * Resolve the first basemap place label that parses, or null.
     *
     * Lives here rather than in the tap handler because `parse` may make a Wikidata round-trip
     * per candidate. The native pick returns placed labels in placement order, so stopping at the
     * first success is what keeps a tap from doing every round-trip serially — and doing any of
     * them on the caller's thread is what made the gesture handler a hundred lines long.
     */
    suspend fun resolveAdminLabel(candidates: List<Feature1>): SpecificFeature? =
        withContext(Dispatchers.IO) {
            candidates.firstNotNullOfOrNull { raw -> runCatching { parse(raw) }.getOrNull() }
        }

    /**
     * Keyless Google Maps enrichment (rating, reviews, hours, photos, price,
     * popular times, …) for the currently selected restaurant or generic place.
     * Emits null for other feature types and while the network scrape is in
     * flight; cancels the in-flight fetch when the selection changes.
     * [GooglePoiDataSource] caches and does its own IO, so a re-select is instant.
     *
     * Hard-gated on the Google services toggle: when off, emits null without
     * touching the network — zero Google traffic.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val currentPoiInfo: StateFlow<GooglePoiInfo?> = selectedFeature
        .flatMapLatest { feature ->
            if (!googleServicesEnabled()) return@flatMapLatest flowOf(null)
            val (name, pos) = when (feature) {
                is SpecificFeature.Restaurant -> feature.name to feature.position
                is SpecificFeature.GenericPlace -> feature.name to feature.position
                else -> return@flatMapLatest flowOf(null)
            }
            if (name.isBlank()) return@flatMapLatest flowOf(null)
            // channelFlow so the WebView scrape's onPartial callback (arriving on a JavaBridge
            // thread) can push progressive review updates into the same stream via trySend.
            channelFlow {
                send(null)
                val base = GooglePoiDataSource.fetch(name, pos.latitude, pos.longitude)
                send(base)
                // Base sheet is up. Reviews load lazily via the hidden WebView: no feature id
                // (e.g. no confident Google match) → nothing more to fetch. Best-effort; any
                // failure/timeout just leaves reviews empty and the sheet as-is.
                val fid = base?.featureId
                if (base != null && !fid.isNullOrBlank()) {
                    // Coalesce the progressive partials: the scraper streams the
                    // accumulated list on every parse, and each trySend
                    // recomposes the sheet that collects this flow. Forward a
                    // partial only when it grew meaningfully or enough time
                    // passed; the authoritative final send below still always
                    // runs, so nothing is ever lost by a dropped partial.
                    var lastPartialSize = 0
                    var lastPartialMs = 0L
                    val reviews = runCatching {
                        webReviews.fetch(
                            featureId = fid,
                            onPartial = { list ->
                                if (list.isNotEmpty()) {
                                    val nowMs = System.currentTimeMillis()
                                    if (list.size - lastPartialSize >= PARTIAL_MIN_GROWTH ||
                                        nowMs - lastPartialMs >= PARTIAL_MIN_INTERVAL_MS
                                    ) {
                                        lastPartialSize = list.size
                                        lastPartialMs = nowMs
                                        trySend(base.copy(reviews = list))
                                    }
                                }
                            },
                        )
                    }.getOrDefault(emptyList())
                    if (reviews.isNotEmpty()) send(base.copy(reviews = reviews))
                }
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
    // is read once per selection (not collected): the flow restarts on the
    // next selection change anyway, and collecting the tab here would re-plan
    // every mode on each tab switch.
    //
    // The scan seed: one null per travel mode, merged over as each plan lands.
    private fun emptyRoutes(): Map<RouteService.TravelMode, RouteService.RouteType?> =
        RouteService.TravelMode.entries.associateWith { null as RouteService.RouteType? }
    @OptIn(ExperimentalCoroutinesApi::class)
    val routes = selectedFeature
        .flatMapLatest { feature ->
            val pos = userPosition.value
            val selectedMode = selectedRouteMode
            val routeFeature = feature as? SpecificFeature.Route ?: return@flatMapLatest flowOf(null)

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
