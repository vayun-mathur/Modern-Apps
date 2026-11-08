package com.vayunmathur.maps.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.maps.data.RecentSearchStore
import com.vayunmathur.maps.data.SavedPlaceStore
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.osmPlace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.util.DataStoreUtils

/** The recency/favorites boost for one search; reads are in-memory, call off the main thread. */
private fun Application.searchBoost(): Map<String, Double> =
    buildSearchBoost(
        RecentSearchStore.get(this).current(),
        SavedPlaceStore(DataStoreUtils.getInstance(this)).savedInitial().map { it.name },
    )

/**
 * A single row in the search list. Results come from the offline OSM POI
 * index: a title, an optional subtitle (category) and a position, so it can
 * both fill the list row and drop a pin on the map.
 */
data class SearchResult(
    val id: String,
    val title: String,
    val subtitle: String?,
    val lat: Double,
    val lon: Double,
    val category: String?,
    /**
     * Tagged OSM boundary id from `poi_region.bin` (low 2 bits are the id
     * space), or 0 when the hit resolves to no boundary. Survives into the
     * sheet's [SpecificFeature.GenericPlace] so a region picked from search
     * masks without an admin-label upgrade round-trip.
     */
    val regionId: Long = 0L,
)

/**
 * Weight a saved (starred) place carries in search ranking: large enough that
 * a favorite always outranks an equally-matching unvisited place (boost only
 * breaks ties within a text-match rank, so an exact match still beats a
 * familiar typo), but still only a reorder — [PoiIndex.searchByName] never
 * returns a non-match.
 */
internal const val SAVED_SEARCH_BOOST = 1_000_000.0

/**
 * The recency/favorites signal for one search: lowercased name → weight.
 *
 * Saved names come first at [SAVED_SEARCH_BOOST]; recent queries follow in
 * most-recent-first order (highest weight = most recent), so a place the user
 * keeps searching for outranks a textually identical one they never tap. Pure
 * so the weighting is unit-testable without DataStore.
 */
internal fun buildSearchBoost(
    recentQueries: List<String>,
    savedNames: List<String>,
): Map<String, Double> {
    if (recentQueries.isEmpty() && savedNames.isEmpty()) return emptyMap()
    val boost = HashMap<String, Double>()
    for (name in savedNames) {
        val key = name.lowercase()
        if (key.isNotBlank()) boost[key] = SAVED_SEARCH_BOOST
    }
    // The store dedupes most-recent-first and caps at 10; weight decays with
    // position so yesterday's query still counts, just less than today's.
    val cap = RecentSearchStore.RECENT_CAP
    recentQueries.forEachIndexed { i, q ->
        val key = q.trim().lowercase()
        if (key.isNotBlank()) boost.putIfAbsent(key, (cap - i).toDouble())
    }
    return boost
}

private fun PoiIndex.PoiRecord.toSearchResult() = SearchResult(
    id = "poi:$latE7:$lonE7:${name.hashCode()}",
    title = name,
    subtitle = PoiCategories.label(type),
    lat = lat,
    lon = lon,
    category = PoiCategories.label(type),
    regionId = regionId,
)

/**
 * Owns the text query and result list for the maps search page.
 *
 * Results come from the offline OSM POI index ([PoiIndex.searchByName]); the map
 * centre is supplied per-query by the calling composable to bias the search. The
 * results flow is also observed by `MapPage` to draw the search-result pin layer.
 */
class MapsSearchViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        /** Keystroke debounce (ms) before a query hits the network. */
        private const val SEARCH_DEBOUNCE_MS = 250L
    }

    private val recentStore = RecentSearchStore.get(application)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<SearchResult>>(emptyList())
    val results: StateFlow<List<SearchResult>> = _results.asStateFlow()

    /**
     * Whether a search is in flight.
     *
     * The view cannot infer this. "Query is long enough and results are empty" is true both
     * while a search runs and after one comes back with nothing, and the search page read it as
     * the second — so it showed "No results found" for the whole debounce plus the offline
     * lookup plus the network round-trip, then replaced it with the results. Only the thing
     * running the search knows which it is, so it is the thing that has to say.
     */
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    /** Recent queries, most-recent first, for the pre-search suggestions. */
    val recents: StateFlow<List<String>> = recentStore.recents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), recentStore.current())

    /** In-flight search job — cancelled on each new keystroke so an older scrape
     *  doesn't race ahead of a newer one and overwrite the result list. */
    private var searchJob: Job? = null

    /**
     * Updates the search text and (asynchronously) the result list from the
     * offline OSM POI index, biased toward [nearLat],[nearLon] (the map centre).
     * Queries shorter than two characters clear the results without searching.
     *
     * Debounced ~250 ms (Decision D7) so fast typists don't stack a search per
     * keystroke; the previous job is cancelled on every call.
     */
    fun setQuery(query: String, nearLat: Double, nearLon: Double) {
        _query.value = query
        searchJob?.cancel()
        // A "lat,lon" query is a place in itself: resolve it directly
        // instead of asking the name index, which can only answer empty.
        PoiSearch.parseCoordinateQuery(query)?.let { (lat, lon) ->
            _results.value = listOf(
                SearchResult(
                    id = "coord:$lat:$lon",
                    title = "%.5f, %.5f".format(lat, lon),
                    subtitle = null,
                    lat = lat,
                    lon = lon,
                    category = null,
                )
            )
            _searching.value = false
            return
        }
        if (query.length < 2) {
            _results.value = emptyList()
            _searching.value = false
            return
        }
        // Set before the launch, not inside it: a coroutine does not necessarily start before
        // the next recomposition, and a frame rendered in that gap is the bug all over again.
        _searching.value = true
        searchJob = viewModelScope.launch {
            try {
                delay(SEARCH_DEBOUNCE_MS)
                // Offline OSM POI index only (P27). Addresses are not carried by
                // the index, so they resolve to empty. Any offline failure
                // (unmapped/poisoned index, decode error) is swallowed to empty.
                val app = getApplication<Application>()
                val offline = withContext(Dispatchers.IO) {
                    runCatching {
                        PoiIndex.initialize(app)
                        PoiIndex.searchByName(query, nearLat, nearLon, limit = 20, boost = app.searchBoost())
                    }.getOrDefault(emptyList())
                }
                _results.value = offline.map { it.toSearchResult() }
            } finally {
                // `finally` because cancellation is the normal exit: every keystroke cancels
                // the previous job, and a cancelled search that never clears this flag leaves
                // the spinner up forever.
                _searching.value = false
            }
        }
    }

    /**
     * Runs the offline OSM search for [query] immediately (no debounce),
     * publishes the results so the list + map pins update, and hands back the
     * FIRST result (or null) so a caller can auto-select it programmatically.
     *
     * Used by the P17 contact-address shortcut: pick a POI name → search → open
     * the top hit without a user tap. Cancels any in-flight keystroke search
     * first so results don't race. Free-text addresses have no OSM path and
     * resolve to null.
     */
    fun searchAndSelectFirst(
        query: String,
        nearLat: Double,
        nearLon: Double,
        onFirst: suspend (SearchResult?) -> Unit,
    ) {
        _query.value = query
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val app = getApplication<Application>()
            val results = withContext(Dispatchers.IO) {
                runCatching {
                    PoiIndex.initialize(app)
                    PoiIndex.searchByName(query, nearLat, nearLon, limit = 20, boost = app.searchBoost())
                }.getOrDefault(emptyList())
            }.map { it.toSearchResult() }
            _results.value = results
            onFirst(results.firstOrNull())
        }
    }

    /**
     * Resolve a free-text [address] (e.g. a picked contact postal address) to a
     * single place and hand it back — WITHOUT touching the query text or the
     * result list. The contact-address shortcut (P17/P31) opens the place
     * directly instead of going through the search box, so this must NOT set
     * [_query] or populate [_results] (that would prefill the search bar and
     * draw the search-result pins).
     *
     * Offline-only: a POI-name query resolves through the OSM index; a full
     * postal address has no OSM path and resolves to null. [onResolved] fires
     * on the main thread so the caller can safely drive
     * [SelectedFeatureViewModel.selectAndFocus].
     */
    fun resolveAndSelect(
        address: String,
        nearLat: Double,
        nearLon: Double,
        onResolved: (SpecificFeature.GenericPlace?) -> Unit,
    ) {
        if (address.isBlank()) {
            onResolved(null)
            return
        }
        viewModelScope.launch {
            // Reuse the POI-name path: named places resolve, street addresses
            // (which the OSM index doesn't carry) resolve to null.
            searchAndSelectFirst(address, nearLat, nearLon) { first ->
                onResolved(
                    first?.let {
                        osmPlace(
                            name = it.title.ifBlank { address },
                            position = GeoPoint(it.lon, it.lat),
                            poiType = null,
                            regionId = it.regionId,
                        )
                    }
                )
            }
        }
    }

    /** Resets the search state (keeps recents). */
    fun reset() {
        searchJob?.cancel()
        _query.value = ""
        _results.value = emptyList()
    }

    /** Record a committed query in the recents store. */
    fun recordRecent(query: String) {
        viewModelScope.launch { recentStore.add(query) }
    }

    fun clearRecents() {
        viewModelScope.launch { recentStore.clear() }
    }

    /**
     * Build a [SpecificFeature.GenericPlace] for a picked search result. It flows
     * through the same selection path as a tapped map pin.
     *
     * The sidecar lookup matters most here: an offline result has no remote
     * enrichment coming, so without it the sheet opens with a title and nothing
     * else. It is also a mapped-file read, hence [osmPlace] and this suspend.
     */
    suspend fun toFeature(result: SearchResult): SpecificFeature.GenericPlace =
        osmPlace(
            name = result.title,
            position = GeoPoint(result.lon, result.lat),
            regionId = result.regionId,
        )

    /**
     * Reverse-geocode [lat],[lon] to the nearest offline POI and hand it back
     * as a [SpecificFeature.GenericPlace] (or null when nothing is nearby).
     * Used for "what's here?" taps on empty map. Street addresses have no OSM
     * path — the result is the nearest named POI within 250 m, or null.
     */
    fun reverseGeocode(lat: Double, lon: Double, onResult: (SpecificFeature.GenericPlace?) -> Unit) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val hit = withContext(Dispatchers.IO) {
                runCatching {
                    PoiIndex.initialize(app)
                    PoiIndex.nearest(lat, lon, limit = 1).firstOrNull()
                }.getOrNull()
            }
            onResult(
                hit?.let {
                    osmPlace(
                        name = it.name,
                        position = GeoPoint(it.lon, it.lat),
                        regionId = it.regionId,
                    )
                }
            )
        }
    }
}
