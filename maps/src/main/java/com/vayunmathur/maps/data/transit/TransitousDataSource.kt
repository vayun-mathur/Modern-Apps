package com.vayunmathur.maps.data.transit

import com.vayunmathur.library.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Online public-transit data source (P10) backed by **Transitous** — a free,
 * community-hosted aggregation of open GTFS / GTFS-RT feeds — via its **MOTIS**
 * REST API, proxied through the self-hosted backend. One call is exposed:
 *
 *  - [departures] — the live board for one stop (`/api/v1/stoptimes`): route
 *    short name, headsign, scheduled + realtime time, delay, platform.
 *
 * This is the ONLY endpoint left, and the only genuinely realtime thing in the
 * system. Stops used to come from `/api/v1/map/stops` per viewport; they are
 * static data, so they are now baked into the basemap as the `transit_stops`
 * layer, along with the MOTIS stop id this endpoint needs. Journey planning used
 * to fall back to `/api/v1/plan`; the on-device RAPTOR planner is now the only
 * planner.
 *
 * Design mirrors the (since removed) GooglePoiDiscovery shape:
 *  - an `object` singleton, all network on [Dispatchers.IO];
 *  - **never throws** — every public call returns empty on any failure so a
 *    flaky feed or a MOTIS schema drift degrades gracefully;
 *  - **brief caching**: departures are cached per stop for only
 *    [DEPARTURES_TTL_MS] since they are live.
 *
 * NOTE (on-device): the live MOTIS fetch needs a device/emulator with network
 * and cannot be exercised at compile time; the endpoint/parse below is
 * verified by shape only.
 */
/**
 * A departure board plus its provenance.
 *
 * [stale] means [departures] are the last-good board served because the live
 * fetch failed; [unavailable] means the fetch failed and no last-good board
 * exists. [fetchedAtMillis] is when [departures] were actually fetched.
 */
data class DeparturesResult(
    val departures: List<Departure>,
    val stale: Boolean,
    val unavailable: Boolean,
    val fetchedAtMillis: Long?,
)

object TransitousDataSource {

    private const val BASE_URL = "https://api.maproj.org/api/transit"

    /** Cap departures requested per board so a busy hub stays scrollable. */
    private const val DEPARTURE_COUNT = 30

    /** Departures are live — cache only very briefly to smooth refresh taps. */
    private const val DEPARTURES_TTL_MS = 20_000L

    /**
     * How long a *failed* fetch is remembered. Distinct from (and far shorter
     * than) the success TTL: one offline attempt used to poison the departures
     * cache for the full 20 s.
     */
    private const val FAILURE_TTL_MS = 3_000L

    private val REQUEST_HEADERS = mapOf(
        "Accept" to "application/json",
        "User-Agent" to "Modern-Apps-Maps/1.0",
    )

    /** A successful fetch; failures never overwrite it, so it doubles as last-good. */
    private class Cached<T>(val value: T) {
        val at: Long = System.currentTimeMillis()
        fun isFresh(ttlMs: Long): Boolean = System.currentTimeMillis() - at < ttlMs
    }

    // Last-good departures keyed on stop id, with a short freshness TTL (live data).
    private val departuresCache = object : LinkedHashMap<String, Cached<List<Departure>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached<List<Departure>>>) = size > 32
    }

    /** When each stop last failed, so failures expire after [FAILURE_TTL_MS] without erasing last-good. */
    private val failureAt = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>) = size > 32
    }

    private fun lastGood(stopId: String): Cached<List<Departure>>? =
        synchronized(departuresCache) { departuresCache[stopId] }

    private fun staleResult(stopId: String): DeparturesResult? = lastGood(stopId)?.let {
        DeparturesResult(it.value, stale = true, unavailable = false, fetchedAtMillis = it.at)
    }

    /**
     * Test seam: fetch the raw board DTO for [stopId] (null on any failure).
     * Defaults to the live network; tests replace it with canned wire payloads
     * so the MOTIS→app mapping is verifiable without network.
     */
    internal var fetchStoptimes: (suspend (String) -> MotisStoptimes?) = { stopId ->
        runCatching {
            val url = "$BASE_URL/stoptimes" +
                "?stopId=${enc(stopId)}&n=$DEPARTURE_COUNT"
            NetworkClient.getJson<MotisStoptimes>(url, REQUEST_HEADERS)
        }.getOrNull()
    }

    /**
     * Live departures for [stopId]. Never throws — empty on failure. Cached for
     * [DEPARTURES_TTL_MS] on success and only [FAILURE_TTL_MS] on failure; pass
     * [force] (a manual refresh) to bypass the cache.
     */
    suspend fun departures(stopId: String, force: Boolean = false): List<Departure> =
        departuresResult(stopId, force).departures

    /**
     * Live departures plus provenance for [stopId]. Never throws.
     *
     * On failure the last-good board is served with [DeparturesResult.stale]
     * instead of blanking; only when no fetch has ever succeeded for the stop
     * is the result [DeparturesResult.unavailable]. A genuinely empty
     * successful board is neither stale nor unavailable.
     */
    suspend fun departuresResult(stopId: String, force: Boolean = false): DeparturesResult {
        if (!force) {
            lastGood(stopId)?.let {
                if (it.isFresh(DEPARTURES_TTL_MS)) {
                    return DeparturesResult(it.value, stale = false, unavailable = false, fetchedAtMillis = it.at)
                }
            }
            val failedAt = synchronized(failureAt) { failureAt[stopId] }
            if (failedAt != null && System.currentTimeMillis() - failedAt < FAILURE_TTL_MS) {
                return staleResult(stopId)
                    ?: DeparturesResult(emptyList(), stale = false, unavailable = true, fetchedAtMillis = null)
            }
        }
        val dto = runCatching { fetchStoptimes(stopId) }.getOrNull()
            ?: run {
                synchronized(failureAt) { failureAt[stopId] = System.currentTimeMillis() }
                return staleResult(stopId)
                    ?: DeparturesResult(emptyList(), stale = false, unavailable = true, fetchedAtMillis = null)
            }
        val deps = dto.stopTimes.mapNotNull { it.toDeparture() }
        val cached = Cached(deps)
        synchronized(departuresCache) { departuresCache[stopId] = cached }
        synchronized(failureAt) { failureAt.remove(stopId) }
        return DeparturesResult(deps, stale = false, unavailable = false, fetchedAtMillis = cached.at)
    }

    /** Last-good board without fetching, or unavailable when none exists. For known-offline callers. */
    fun cachedResult(stopId: String): DeparturesResult = staleResult(stopId)
        ?: DeparturesResult(emptyList(), stale = false, unavailable = true, fetchedAtMillis = null)

    private suspend fun fetchDepartures(stopId: String): List<Departure> = withContext(Dispatchers.IO) {
        // Proxied via self-hosted (same object-singleton shape as the retired Google fetchers):
        // server forwards to api.transitous.org/api/v1/stoptimes with a short cache.
        // arriveBy is fixed false server-side — the only value this client uses.
        val url = "$BASE_URL/stoptimes" +
            "?stopId=${enc(stopId)}&n=$DEPARTURE_COUNT"
        val resp: MotisStoptimes = NetworkClient.getJson(url, REQUEST_HEADERS)
        resp.stopTimes.mapNotNull { it.toDeparture() }
    }

    /** Map a MOTIS stoptime → app [Departure]; drop entries with no usable
     *  departure time. Delay is realtime − scheduled in whole minutes. */
    private fun MotisStopTime.toDeparture(): Departure? {
        val place = place ?: return null
        val scheduled = parseEpoch(place.scheduledDeparture ?: place.departure) ?: return null
        val realtime = parseEpoch(place.departure) ?: scheduled
        val delayMin = ((realtime - scheduled) / 60_000L).toInt()
        return Departure(
            line = routeShortName?.ifBlank { null } ?: agencyName?.ifBlank { null } ?: "",
            headsign = headsign?.ifBlank { null } ?: place.name ?: "",
            scheduledMillis = scheduled,
            realtimeMillis = realtime,
            delayMinutes = delayMin,
            realTime = realTime,
            platform = (place.track ?: place.scheduledTrack)?.ifBlank { null },
            mode = mode,
            routeColor = routeColor?.removePrefix("#")?.ifBlank { null },
            cancelled = cancelled,
            tripId = tripId?.ifBlank { null },
        )
    }

    /** Parse a MOTIS ISO-8601 timestamp (with zone offset) → epoch millis, or
     *  null. Tolerates a plain `...Z` instant too. */
    private fun parseEpoch(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }
            .recoverCatching { Instant.parse(iso).toEpochMilli() }
            .getOrNull()
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
