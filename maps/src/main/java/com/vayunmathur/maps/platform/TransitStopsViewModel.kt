package com.vayunmathur.maps.platform
import com.vayunmathur.maps.data.nearest
import com.vayunmathur.maps.data.string
import com.vayunmathur.maps.data.OfflineRouterTransit

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.ConnectivityMonitor
import com.vayunmathur.maps.data.transit.Departure
import com.vayunmathur.maps.data.transit.TransitStop
import com.vayunmathur.maps.data.transit.TransitousDataSource
import com.vayunmathur.maps.data.transit.TripItinerary
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the P10 public-transit UI: the departure board for a tapped stop.
 *
 * The stop *pins* are no longer this class's business — and currently nobody's:
 * they used to render straight from the baked `transit_stops` basemap layer,
 * but the renderer has no vector source-layer API so [com.vayunmathur.maps.ui.TransitStopsLayer]
 * is a no-op until it grows one (tracked gap). Stop taps arrive via baked POI
 * hits / station-POI nearest-stop resolution instead.
 *
 * [departures] is the board for the [openStop]-selected stop, re-fetched on
 * [refresh]. The live countdown itself is computed client-side in the sheet from
 * each departure's epoch time, so no polling is needed here.
 *
 * All network is on [Dispatchers.IO], and is skipped entirely when
 * [ConnectivityMonitor] reports no validated internet — otherwise every offline
 * lookup pays a full HTTP timeout. The departure board is offline-first: the
 * on-device transit pack (the world pack in the archive when present, else a
 * legacy `*.transit` sidecar) supplies the schedule and MOTIS supplies the
 * realtime overlay on top of it.
 */
class TransitStopsViewModel(application: Application) : AndroidViewModel(application) {

    // --- Departure board ---------------------------------------------------

    /**
     * Departures fetched per half of the window.
     *
     * A day either side of now, at a major interchange, is a few hundred trains. High enough that
     * a busy station is not silently truncated an hour into the day, low enough that the board is
     * a list and not a database dump.
     */
    private val windowMax = 400

    private val _selected = MutableStateFlow<TransitStop?>(null)
    val selected: StateFlow<TransitStop?> = _selected.asStateFlow()

    // Bumped by refresh() to force a re-fetch of the same stop.
    private val _refreshTick = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val departures: StateFlow<DeparturesState> =
        combine(_selected, _refreshTick) { stop, tick -> stop to tick }
            .flatMapLatest { (stop, tick) ->
                if (stop == null) {
                    flowOf(DeparturesState.Idle)
                } else {
                    flow {
                        emit(DeparturesState.Loading(stop))
                        val force = tick > 0
                        // Offline-first now that the baked board folds MOTIS
                        // realtime in as an overlay: the pack has the complete
                        // schedule, the overlay supplies the live delays. Fall
                        // back to the pure online board only when no pack covers
                        // this stop.
                        //
                        // Two queries, because the pack answers "what leaves from
                        // here, onwards" and the board shows a day either side.
                        // The past half is the same question asked from 24 hours
                        // ago, walked forward until it reaches now — without that
                        // walk it returns the 400 trains that followed the anchor,
                        // which at a busy station is still the middle of the night.
                        // Anything of it still in the future is a duplicate of the
                        // forward half and is dropped on merge.
                        val now = System.currentTimeMillis()
                        val offline = runCatching {
                            val ahead = OfflineRouterTransit.getStopDeparturesOffline(
                                getApplication(), stop.lat, stop.lon, max = windowMax,
                            )
                            val behind = OfflineRouterTransit.getStopDeparturesOffline(
                                getApplication(), stop.lat, stop.lon, max = windowMax,
                                anchor = Instant.ofEpochMilli(now).minus(Duration.ofHours(24)),
                                until = Instant.ofEpochMilli(now),
                            ).filter { it.realtimeMillis < now }
                            (behind + ahead)
                                .distinctBy { it.line to it.scheduledMillis }
                                .sortedBy { it.realtimeMillis }
                        }.getOrDefault(emptyList())
                        val deps = offline.ifEmpty {
                            if (ConnectivityMonitor.isOnline(getApplication())) {
                                TransitousDataSource.departures(stop.id, force = force)
                            } else {
                                emptyList()
                            }
                        }
                        emit(DeparturesState.Loaded(stop, deps))
                    }.flowOn(Dispatchers.IO)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DeparturesState.Idle)

    /** Open the board for [stop] (called from the map tap). */
    fun openStop(stop: TransitStop) {
        _refreshTick.value = 0
        _selected.value = stop
    }

    /**
     * Resolve the stop nearest [lat],[lon] and open its board. Reached from a
     * tapped `ma_pois` station POI, which carries no stop id of its own.
     *
     * Resolved from the baked `.transit` pack rather than over the network: the
     * pack already holds every stop's position and MOTIS id, so this needs no
     * connectivity and works for the same coverage the offline board does.
     *
     * # The id is an enrichment, not a precondition
     *
     * [OfflineRouterTransit.nearestStop] returns null whenever the feed's Transitous
     * source name was unknown at build time, because it resolves a *MOTIS* id —
     * and that id is only ever used to fold in live delays. Treating null as
     * "no board" made every station in such a feed inert: no sheet, no message,
     * nothing. The schedule needs no id at all ([OfflineRouterTransit.getStopDeparturesOffline]
     * takes a coordinate), so a stop with no id still opens, just without
     * realtime. That is exactly what `build_transit_stops_layer.sh` promises:
     * "they still open an offline departure board, just no live delays".
     *
     * [name] is the tapped POI's name, so the header reads "Palo Alto" rather
     * than a raw MOTIS id string while the board loads.
     */
    /** Resolve the stop nearest [lat],[lon] without opening any sheet. */
    suspend fun resolveNearestStop(lat: Double, lon: Double, name: String? = null): TransitStop {
        val resolved = withContext(Dispatchers.IO) {
            OfflineRouterTransit.nearestStop(getApplication(), lat, lon)
        }
        return resolved?.let { if (name != null) it.copy(name = name) else it }
            ?: TransitStop(id = "", name = name.orEmpty(), lat = lat, lon = lon)
    }

    fun openNearestStop(lat: Double, lon: Double, name: String? = null) {
        viewModelScope.launch { openStop(resolveNearestStop(lat, lon, name)) }
    }

    /** Close the board. */
    fun closeStop() {
        _selected.value = null
    }

    /** Force a live re-fetch of the current stop's board. */
    fun refresh() {
        if (_selected.value != null) _refreshTick.value += 1
    }

    // --- Trip details ------------------------------------------------------

    /**
     * The vehicle whose trip sheet is open: the stable per-trip id from
     * `activeVehicles` plus where it was tapped (resolving the feed timezone
     * needs a coordinate on the trip). Null when no trip sheet is open.
     */
    private val _selectedTrip = MutableStateFlow<TripSelection?>(null)
    val selectedTrip: StateFlow<TripSelection?> = _selectedTrip.asStateFlow()

    private val _tripItinerary = MutableStateFlow<TripItineraryState>(TripItineraryState.Idle)
    val tripItinerary: StateFlow<TripItineraryState> = _tripItinerary.asStateFlow()

    /** Open the trip sheet for a tapped vehicle sprite. */
    fun openTrip(vehicleId: Long, lat: Double, lon: Double) {
        _selectedTrip.value = TripSelection(vehicleId, lat, lon)
        viewModelScope.launch {
            _tripItinerary.value = TripItineraryState.Loading
            val itin = withContext(Dispatchers.IO) {
                runCatching {
                    OfflineRouterTransit.tripItinerary(getApplication(), vehicleId, lat, lon)
                }.getOrNull()
            }
            // The selection may have moved on while the fetch ran.
            if (_selectedTrip.value?.vehicleId == vehicleId) {
                _tripItinerary.value = if (itin == null) TripItineraryState.Missing
                                       else TripItineraryState.Loaded(itin)
            }
        }
    }

    /**
     * Open the trip sheet for a departure tapped on a board. The board's
     * departures carry the pack trip identity ([Departure.tripVehicleId]),
     * resolved at the board's own coordinate.
     */
    fun openTripForDeparture(departure: Departure, lat: Double, lon: Double) {
        val id = departure.tripVehicleId ?: return
        openTrip(id, lat, lon)
    }

    /** Close the trip sheet. */
    fun closeTrip() {
        _selectedTrip.value = null
        _tripItinerary.value = TripItineraryState.Idle
    }
}

/** Which vehicle the trip sheet names: stable trip id + tap coordinate. */
data class TripSelection(
    val vehicleId: Long,
    val lat: Double,
    val lon: Double,
)

/** UI state for the vehicle trip sheet. */
sealed interface TripItineraryState {
    /** Singleton holding idle state and operations. */
    data object Idle : TripItineraryState
    /** Singleton holding loading state and operations. */
    data object Loading : TripItineraryState
    /** The id did not resolve or the trip does not run today. */
    data object Missing : TripItineraryState
    /** Immutable value describing loaded; carries itinerary. */
    data class Loaded(val itinerary: TripItinerary) : TripItineraryState
}

/** UI state for the departure board. */
sealed interface DeparturesState {
    /** Singleton holding idle state and operations. */
    data object Idle : DeparturesState
    /** Immutable value describing loading; carries stop. */
    data class Loading(val stop: TransitStop) : DeparturesState
    /** Immutable value describing loaded; carries stop, departures. */
    data class Loaded(val stop: TransitStop, val departures: List<Departure>) : DeparturesState
}
