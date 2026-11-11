package com.vayunmathur.maps.data.google

import com.vayunmathur.library.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.net.URLEncoder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Keyless Google Maps place details: photos, hours/open status, phone, website, price
 * band, fuel price and popular times — and nothing review-shaped. Ratings, review
 * counts, summaries and review text come only from the offline archive
 * (`PoiIndexAttrs`), so this neither requests review posts (the `pb` carries no
 * review-posts selector) nor reads the rating/review/editorial slots of the response.
 *
 * Follows the NewPipe model: the device calls the same undocumented google.com/maps
 * web endpoints a logged-out browser does — no API key, no first-party backend,
 * no Play Services. The request is a `pb` protobuf-ish string; the response is a
 * guard-prefixed, deeply nested *positional* JSON array (no field names). The
 * calibrated index paths ([Paths]) pull hours/price/website/phone/photos/
 * popular-times out of it.
 *
 * This is a scrape of a private endpoint, so it is inherently fragile:
 *  - a Google-side reshape makes fields go null (paths rot) — every accessor
 *    degrades to null instead of throwing, so the sheet keeps working;
 *  - keyless responses are bot-degraded — popular times and the full photo
 *    gallery are usually stripped, so those sections often just won't show.
 *
 * All network runs on [Dispatchers.IO]. Results are cached in a small bounded
 * in-memory LRU keyed by (name, lat, lon) so toggling a POI doesn't refetch.
 *
 * NOTE (on-device): the live scrape needs a device with network — it can't be
 * exercised at compile time. Session cookie warming relies on the platform
 * cookie handler; whether that persists across [NetworkClient] calls is a
 * device-verification item.
 */
object GooglePoiDataSource {

    // Endpoints (calibrated 2026-06). hl/gl pinned to en/us so the parser's
    // English status keywords line up with the response text.
    private const val SEARCH_ENDPOINT =
        "https://www.google.com/search?tbm=map&authuser=0&hl=en&gl=us"
    private const val SESSION_WARM_URL = "https://www.google.com/maps?hl=en&gl=us"

    // Browser-like identity. The endpoints authorise by referer + a normal
    // desktop UA rather than a key, so these headers ARE the credential.
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val REQUEST_HEADERS = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "https://www.google.com/maps/",
    )

    /** Match radius: a scraped result must be within this of the OSM point to be
     *  treated as the same place, else the enrichment is discarded (wrong match). */
    private const val MATCH_RADIUS_M = 250.0

    /** [Paths.SUMMARY_KIND] value that makes [Paths.SUMMARY_TEXT] a pump price. */
    private const val GAS_STATION_KIND = "SearchResult.TYPE_GAS_STATION"

    /** Mean Earth radius (m) for the match-distance haversine. */
    private const val EARTH_RADIUS_M = 6_371_000.0
    /** Most photo URLs kept per place (hero + gallery preview). */
    private const val MAX_PHOTO_URLS = 12
    /** Price-band ceilings ($) mapping a numeric price label to a 1..4 level. */
    private const val PRICE_BAND_MID = 10
    private const val PRICE_BAND_HIGH = 20
    private const val PRICE_BAND_LUXURY = 35
    private const val MIN_PRICE_LEVEL = 1
    private const val MAX_PRICE_LEVEL = 4

    @Volatile private var sessionWarmed = false

    // Bounded LRU cache (access-ordered). Stores the resolved info OR null (a
    // negative cache entry) so a place with no Google match isn't retried on
    // every reselect. Guarded by `synchronized(cache)`.
    //
    // Only a request that COMPLETED is ever written here — see [Outcome].
    private data class Key(val name: String, val lat: Double, val lon: Double)
    private val cache = object : LinkedHashMap<Key, GooglePoiInfo?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, GooglePoiInfo?>) = size > 64
    }

    /**
     * Why a fetch came back empty.
     *
     * The two reasons must not be remembered alike. "Google has nothing for this
     * place" is a stable fact and worth caching; "the request did not complete" is a
     * property of the moment, and caching it strands the place for the rest of the
     * process — a POI tapped offline would stay bare even after connectivity
     * returned, with no way for the user to ask again.
     */
    private sealed interface Outcome {
        data class Found(val info: GooglePoiInfo) : Outcome
        data object NoMatch : Outcome
        data object Failed : Outcome
    }

    /**
     * Fetch enrichment for an OSM place identified by [name] at [lat],[lon].
     * Returns null when there's no confident Google match or the scrape fails —
     * callers treat null as "no enrichment available". Never throws.
     */
    suspend fun fetch(name: String, lat: Double, lon: Double): GooglePoiInfo? {
        if (name.isBlank()) return null
        val key = Key(name, lat, lon)
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val outcome = runCatching { fetchUncached(name, lat, lon) }.getOrElse { Outcome.Failed }
        return when (outcome) {
            is Outcome.Found -> outcome.info.also { synchronized(cache) { cache[key] = it } }
            Outcome.NoMatch -> null.also { synchronized(cache) { cache[key] = null } }
            // Deliberately not cached, so the next tap retries.
            Outcome.Failed -> null
        }
    }

    private suspend fun fetchUncached(name: String, lat: Double, lon: Double): Outcome =
        withContext(Dispatchers.IO) {
            warmSession()
            val pb = buildSearchPb(name, lat, lon)
            val url = "$SEARCH_ENDPOINT&q=${name.enc()}&pb=${pb.enc()}"
            // A transport error or a non-2xx never became an answer.
            val body = httpGet(url) ?: return@withContext Outcome.Failed
            // Nor did a body we cannot parse: a 200 that does not decode is far more
            // likely an interstitial (consent, captcha, rate limit) than a genuine
            // "no such place".
            val root = GoogleResponse.parseOrNull(body) ?: return@withContext Outcome.Failed
            val entry = pickEntry(root, lat, lon) ?: return@withContext Outcome.NoMatch
            parsePlace(entry)?.let { Outcome.Found(it) } ?: Outcome.NoMatch
        }

    // --- HTTP plumbing ------------------------------------------------------

    /**
     * One GET of the maps home page so the cookie jar (if the platform installs a
     * default CookieHandler) picks up Google's consent/NID cookies, after which
     * the data requests behave like one logged-out browser. Best-effort and
     * fire-once; failure never blocks a fetch.
     */
    private suspend fun warmSession() {
        if (sessionWarmed) return
        runCatching {
            httpWarm(SESSION_WARM_URL)
        }
        sessionWarmed = true
    }

    /**
     * Test seam for the search GET: returns the body text, or null on any non-2xx /
     * transport error. Defaults to the live network ([get]); tests replace it with
     * canned payloads so the parse/selection logic is verifiable without network.
     */
    internal var httpGet: (suspend (String) -> String?) = { get(it) }

    /**
     * Test seam for the session-warm GET. Routed through [httpGet] (rather than
     * straight at [NetworkClient]) so tests never touch the network at all.
     */
    internal var httpWarm: (suspend (String) -> String?) = {
        NetworkClient.performRequest(
            url = it,
            headers = REQUEST_HEADERS + ("Accept" to "text/html,application/xhtml+xml"),
            useSystemTrust = true,
        ).body
    }

    /** GET returning the body text, or null on any non-2xx / transport error.
     *  `useSystemTrust` because google.com is an arbitrary external host, not one
     *  of the app's pinned first-party endpoints. */
    private suspend fun get(url: String): String? {
        val resp = runCatching {
            NetworkClient.performRequest(url = url, headers = REQUEST_HEADERS, useSystemTrust = true)
        }.getOrNull() ?: return null
        return if (resp.isSuccess) resp.body else null
    }

    // --- result selection + parsing ----------------------------------------

    /**
     * Pick the result entry that matches the OSM point. Results come back either
     * as a list at [Paths.RESULTS] or as a single geocoded node at
     * [Paths.SINGLE] (wrapped so its place node lands at entry[1], matching a
     * list entry). Among list entries we take the nearest within [MATCH_RADIUS_M],
     * so a category-y response doesn't graft a neighbour's data onto this place.
     */
    private fun pickEntry(root: JsonElement, lat: Double, lon: Double): JsonElement? {
        val list = root.at(*Paths.RESULTS).arr()
        if (list != null && list.isNotEmpty()) {
            val best = list
                .mapNotNull { e ->
                    val plat = e.at(*Paths.LAT).dbl() ?: return@mapNotNull null
                    val plng = e.at(*Paths.LNG).dbl() ?: return@mapNotNull null
                    e to haversine(lat, lon, plat, plng)
                }
                .minByOrNull { it.second }
            if (best != null && best.second <= MATCH_RADIUS_M) return best.first
            // No close match, but a specific-name search often returns exactly one
            // focused result — accept a lone entry regardless of distance.
            if (list.size == 1) return list.first()
            return null
        }
        // Single geocoded node → wrap as [null, node] so the entry-relative paths
        // (place node at [1]) resolve unchanged.
        val node = root.at(*Paths.SINGLE) ?: return null
        val wrapped = JsonArray(listOf(JsonNull, node))
        return if (wrapped.at(*Paths.NAME).str() != null) wrapped else null
    }

    private fun parsePlace(entry: JsonElement): GooglePoiInfo? {
        entry.at(*Paths.NAME).str() ?: return null // gate: a real place node
        val rich = entry.at(*Paths.STATUS_RICH).str()
        val s118 = entry.at(*Paths.STATUS_118).str()
        val statusStr = rich ?: s118 ?: entry.at(*Paths.OPEN_STATUS).str()
        val priceText = entry.at(*Paths.PRICE_TEXT).str()
        val info = GooglePoiInfo(
            priceText = priceText,
            priceLevel = priceLevelOf(priceText),
            website = entry.at(*Paths.WEBSITE).str(),
            phone = entry.at(*Paths.PHONE).str(),
            openNow = parseOpenNow(statusStr),
            statusText = statusStr,
            category = entry.at(*Paths.CATEGORY).str(),
            fuelPrice = parseFuelPrice(entry),
            hours = parseHours(entry, prefer118 = rich == null && s118 != null),
            photoUrls = parsePhotos(entry),
            popularTimes = parsePopularTimes(entry),
        )
        // Ignore an entry that carried nothing but a name (no signal to show).
        return if (info.isEmpty) null else info
    }

    /**
     * Pump price for a fuel station, e.g. `"$6.20/Regular"` → `FuelPrice("$6.20", "Regular")`.
     *
     * [Paths.SUMMARY_TEXT] is a *shared* slot, not a price field: on a restaurant it
     * holds the editorial tagline ("Classic bistro with a Mediterranean menu"). So the
     * sibling type tag at [Paths.SUMMARY_KIND] decides what the text means, and anything
     * other than a gas station is ignored — reading the slot unconditionally would put a
     * restaurant's tagline in the fuel row, which no null-check would catch.
     *
     * Google pre-formats the string and sends a single grade, so this splits rather than
     * reducing to a number: no currency or locale information is available here.
     */
    private fun parseFuelPrice(entry: JsonElement): FuelPrice? {
        if (entry.at(*Paths.SUMMARY_KIND).str() != GAS_STATION_KIND) return null
        val (price, grade) = entry.at(*Paths.SUMMARY_TEXT).str()
            ?.split('/', limit = 2)
            ?.takeIf { it.size == 2 }
            ?: return null
        if (price.isBlank() || grade.isBlank()) return null
        return FuelPrice(price.trim(), grade.trim())
    }

    /** Weekly hours — main-entity schedule ([Paths.HOURS_203]) first, falling
     *  back to a department sub-schedule ([Paths.HOURS_118]); [prefer118] flips
     *  the order when the displayed status itself came from the department block,
     *  so table and status describe the same schedule. */
    private fun parseHours(entry: JsonElement, prefer118: Boolean): List<String> {
        val h203 = { readHours(entry.at(*Paths.HOURS_203)) }
        val h118 = { readHours(entry.at(*Paths.HOURS_118)) }
        return if (prefer118) h118().ifEmpty(h203) else h203().ifEmpty(h118)
    }

    /** Days of the schedule Google actually sends. Despite the field's shape this is
     *  only ever *today*: the search response carries no weekly schedule at all (checked
     *  2026-09 against captured gas-station and restaurant responses — the only weekday
     *  tokens present are today's and forward-looking status text like "Opens 8 AM Wed").
     *  Showing a full week needs a different request, not a different path here. */
    private fun readHours(days: JsonElement?): List<String> {
        val arr = days.arr() ?: return emptyList()
        return arr.mapNotNull { day ->
            val name = day.at(0).str() ?: return@mapNotNull null
            val hrs = day.at(3).arr()?.mapNotNull { it.at(0).str()?.ifBlank { null } }
                ?.joinToString(", ")?.ifBlank { null } ?: return@mapNotNull null
            val note = day.at(6, 1).str()?.ifBlank { null }
            if (note != null) "$name: $hrs \u00B7 $note" else "$name: $hrs"
        }
    }

    /** Business photos. Google gutted the keyless preview to a single hero photo
     *  (served duplicated → de-dup); big places carry a small extra gallery
     *  preview. The full gallery is login-gated. De-dup by the resized URL. */
    private fun parsePhotos(entry: JsonElement): List<String> {
        val urls = LinkedHashSet<String>()
        fun add(u: String?) {
            if (u != null && u.contains("googleusercontent"))
                urls += u.replace(Regex("=w\\d+-h\\d+.*$"), "=w500-h350")
        }
        entry.at(*Paths.PHOTOS).arr()?.forEach { add(it.at(*Paths.PHOTO_HERO).str()) }
        entry.at(*Paths.GALLERY).arr()?.forEach { add(it.at(*Paths.GALLERY_PHOTO).str()) }
        return urls.take(MAX_PHOTO_URLS)
    }

    /** Popular-times histogram. Usually stripped on a keyless response (returns
     *  null and the section just doesn't render). */
    private fun parsePopularTimes(entry: JsonElement): PoiPopularTimes? {
        val days = entry.at(*Paths.POPULAR_TIMES).at(0).arr() ?: return null
        val parsed = days.mapNotNull { d ->
            val dow = d.at(0).int() ?: return@mapNotNull null
            val hours = d.at(1).arr().orEmpty().mapNotNull { h ->
                val hour = h.at(0).int() ?: return@mapNotNull null
                val occ = h.at(1).int() ?: return@mapNotNull null
                PoiHourBusyness(hour, occ)
            }
            if (hours.isEmpty()) null else PoiDayBusyness(dow, hours)
        }
        return if (parsed.isEmpty()) null else PoiPopularTimes(parsed)
    }

    // --- small helpers

    // --- small helpers ------------------------------------------------------

    /** Derive a 1..4 price level from Google's label ("$10–20"→2), or the count
     *  of '$' for the symbol style ("$$"→2). Null when there's no price. */
    private fun priceLevelOf(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        priceLevelOfAmount(text)?.let { return it }
        return text.count { it == '$' }.takeIf { it in MIN_PRICE_LEVEL..MAX_PRICE_LEVEL }
    }

    private fun priceLevelOfAmount(text: String): Int? {
        val low = Regex("\\d+").find(text)?.value?.toIntOrNull() ?: return null
        return priceLevelForLow(low)
    }

    private fun priceLevelForLow(low: Int): Int {
        if (low < PRICE_BAND_MID) return MIN_PRICE_LEVEL
        if (low < PRICE_BAND_HIGH) return MIN_PRICE_LEVEL + 1
        if (low < PRICE_BAND_LUXURY) return MAX_PRICE_LEVEL - 1
        return MAX_PRICE_LEVEL
    }

    /** Open/closed from the English status text (hl=en pins the language). Closed
     *  words are matched first — several are prefix-cousins of the open form. */
    private val CLOSED_WORDS = listOf("Closed", "Opens", "Opening", "Temporarily", "Permanently")
    private val OPEN_WORDS = listOf("Open", "Closes", "Closing")
    private fun parseOpenNow(status: String?): Boolean? {
        val s = status?.trim()?.ifBlank { null } ?: return null
        return when {
            CLOSED_WORDS.any { s.startsWith(it) } -> false
            OPEN_WORDS.any { s.startsWith(it) } -> true
            else -> null
        }
    }

    private fun String.enc(): String = URLEncoder.encode(this, "UTF-8")

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = EARTH_RADIUS_M
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Build the search `pb`. Calibrated template (2026-06): a plain `q=`
     *  returns an empty envelope, so search needs this full pb, and results are
     *  viewport-driven (the `!2d<lng>!3d<lat>` block is the OSM point).
     *
     *  Trimmed 2026-11 of the review-posts selector
     *  (`!3sother_user_google_review_posts__and__hotel_and_vr_partner_review_posts`, with its
     *  enclosing counts `!8m10`→`!8m9`, `!72m22`→`!72m21`, `!24m107`→`!24m106`); a live A/B
     *  against the untrimmed template returned the same 20 results with identical name,
     *  website, phone, photos, hours and status. */
    private fun buildSearchPb(query: String, lat: Double, lon: Double): String =
        SEARCH_PB_TEMPLATE
            .replace("{QUERY}", query.replace('!', ' ').trim())
            .replace("{LNG}", lon.toString())
            .replace("{LAT}", lat.toString())

    private const val SEARCH_PB_TEMPLATE =
        "!1s{QUERY}!4m8!1m3!1d25229.167291701906!2d{LNG}!3d{LAT}!3m2!1i1024!2i768!4f13.1!7i20" +
            "!10b1!12m52!1m5!18b1!30b1!31m1!1b1!34e1!2m4!5m1!6e2!20e3!39b1!6m25!32i1!49b1!63m0!66b1" +
            "!85b1!114b1!149b1!206b1!209b1!212b1!216b1!222b1!223b1!232b1!234b1!235b1!244b1!246b1" +
            "!250b1!253b1!260b1!266b1!273b1!281b1!291m0!10b1!12b1!13b1!14b1!16b1!17m1!3e1!20m3!5e2" +
            "!6b1!14b1!46m1!1b0!96b1!99b1!19m4!2m3!1i360!2i120!4i8!20m57!2m2!1i203!2i100!3m2!2i4!5b1" +
            "!6m6!1m2!1i86!2i86!1m2!1i408!2i240!7m33!1m3!1e1!2b0!3e3!1m3!1e2!2b1!3e2!1m3!1e2!2b0!3e3" +
            "!1m3!1e8!2b0!3e3!1m3!1e10!2b0!3e3!1m3!1e10!2b1!3e2!1m3!1e10!2b0!3e4!1m3!1e9!2b1!3e2!2b1" +
            "!9b0!15m8!1m7!1m2!1m1!1e2!2m2!1i195!2i195!3i20!15i9937!24m106!1m25!13m9!2b1!3b1!4b1!6i1" +
            "!8b1!9b1!14b1!20b1!25b1!18m14!3b1!4b1!5b1!6b1!13b1!14b1!17b1!21b1!22b1!32b1!33m1!1b1" +
            "!34b1!36e2!10m1!8e3!11m1!3e1!17b1!20m2!1e3!1e6!24b1!25b1!26b1!27b1!29b1!30m1!2b1!36b1" +
            "!37b1!39m3!2m2!2i1!3i1!43b1!52b1!54m1!1b1!55b1!56m1!1b1!61m2!1m1!1e1!65m5!3m4!1m3!1m2" +
            "!1i224!2i298!72m21!1m8!2b1!5b1!7b1!12m4!1b1!2b1!4m1!1e1!4b1!8m9!1m6!4m1!1e1!4m1!1e3" +
            "!4m1!1e4!6m1" +
            "!1e1!9b1!89b1!90m2!1m1!1e2!98m3!1b1!2b1!3b1!103b1!113b1!114m3!1b1!2m1!1b1!117b1!122m1" +
            "!1b1!126b1!127b1!128m1!1b0!26m4!2m3!1i80!2i92!4i8!30m28!1m6!1m2!1i0!2i0!2m2!1i530!2i768" +
            "!1m6!1m2!1i974!2i0!2m2!1i1024!2i768!1m6!1m2!1i0!2i0!2m2!1i1024!2i20!1m6!1m2!1i0!2i748" +
            "!2m2!1i1024!2i768!34m19!2b1!3b1!4b1!6b1!8m6!1b1!3b1!4b1!5b1!6b1!7b1!9b1!12b1!14b1!20b1" +
            "!23b1!25b1!26b1!31b1!37m1!1e81!42b1!49m10!3b1!6m2!1b1!2b1!7m2!1e3!2b1!8b1!9b1!10e2!50m3" +
            "!2e2!3m1!3b1!61b1!67m5!7b1!10b1!14b1!15m1!1b0!69i782!77b1"

    /**
     * Calibrated positional field-index paths (2026-06),
     * relative to a result *entry* whose place node is `[1]` (RESULTS/SINGLE are
     * relative to the response root). A Google reshape moves these; when that
     * happens each accessor returns null and the affected field simply drops out.
     */
    private object Paths {
        val RESULTS = intArrayOf(64)
        val SINGLE = intArrayOf(0, 1, 0, 14)
        val NAME = intArrayOf(1, 11)
        val LAT = intArrayOf(1, 9, 2)
        val LNG = intArrayOf(1, 9, 3)
        val CATEGORY = intArrayOf(1, 13, 0)
        val PRICE_TEXT = intArrayOf(1, 4, 2)
        val WEBSITE = intArrayOf(1, 7, 0)
        val PHONE = intArrayOf(1, 178, 0, 0)
        val PHOTOS = intArrayOf(1, 72, 0)
        // Hero photo inside the PHOTOS list.
        val PHOTO_HERO = intArrayOf(6, 0)
        val OPEN_STATUS = intArrayOf(1, 203, 1, 8, 0)
        val STATUS_RICH = intArrayOf(1, 203, 1, 4, 0)
        val STATUS_118 = intArrayOf(1, 118, 0, 3, 1, 4, 0)
        val HOURS_203 = intArrayOf(1, 203, 0)
        val HOURS_118 = intArrayOf(1, 118, 0, 3, 0)
        val POPULAR_TIMES = intArrayOf(1, 84)

        // [1][88] is a shared "summary" block: [0] is a display string whose meaning is
        // set by the type tag at [1] — "$6.20/Regular" for a gas station, an editorial
        // tagline for a restaurant. Always check SUMMARY_KIND before reading SUMMARY_TEXT.
        val SUMMARY_TEXT = intArrayOf(1, 88, 0)
        val SUMMARY_KIND = intArrayOf(1, 88, 1)
        // Extra gallery preview beside the hero photo.
        val GALLERY = intArrayOf(1, 204, 0)
        val GALLERY_PHOTO = intArrayOf(1, 2, 0, 0)
    }
}
