package com.vayunmathur.maps.util

/**
 * The indexed name search: [searchByWordIndex] and the candidate collection behind it.
 *
 * Built around what a cold planet archive costs on a phone. The old search was correct but
 * read far too much: for every query it walked the rarest token's whole word range
 * checking each entry against its record's name, decoded and distance-sorted up to 5,000
 * of the matches, then also scanned up to 40,000 records around the user by name. "pizza"
 * from San Francisco touched 72 k distinct 4 KiB pages (283 MB) of the archive; "hotel"
 * 92 k. On flash that is seconds per keystroke, which is what "searching takes forever"
 * was. This version keeps the same answer shape while reading a few hundred pages:
 *
 *  1. **Tokens -> word ranges** (two binary searches per spelling, [queryTokens]).
 *  2. **Candidates from ordinals only.** The rarest token's range is scanned sequentially
 *     into a [HitTable]; every other token's range is scanned and marked against it, so a
 *     multi-word query is an intersection of entry arrays — no names read. Word positions
 *     ride along, so "phrase" vs "every word" is known without the name too.
 *  3. **Where.** A small survivor set is placed in full (it is the whole world's answer:
 *     "british museum", "ferry building"). A large one ("pizza", 81 k entries) is joined
 *     against the spatial grid in growing boxes around the bias point, then — only when
 *     those boxes yield too little — against a window of survivors nearest the bias point
 *     in Morton (= file) order. Coordinates are read only for rows that are actually
 *     near.
 *  4. **Fallbacks only when short of results:** a name scan of the few thousand records
 *     closest to the bias point (mid-word substrings, typos, punctuation), then the
 *     index-wide typo lookup.
 *  5. **Rank and decode** at most [SHORTLIST] names.
 *
 * Every scan polls the caller's cancel token, so a superseded keystroke stops promptly.
 */

/** Longest token range that is scanned at all; longer ones (one- or two-letter
 *  prefixes like "s") are checked against candidates' names instead. */
private const val STREAM_CAP = 1_500_000

/** Largest driver range materialised into a table (the others are joined ring by ring). */
private const val MATERIALIZE_CAP = 250_000

/** Survivor sets up to this size are placed whole: their coordinates are all read. */
private const val GLOBAL_ALL_CAP = 1_500

/** Half-sizes of the boxes around the bias point the grid join grows through. */
private val RING_HALF_KM = doubleArrayOf(1.5, 6.0, 25.0)

/** Survivors nearest the bias point in file order read when the boxes come up short. */
private const val REGIONAL_WINDOW = 1_500

/** The name-scan fallback's box half-size and record budget. */
private const val NAME_SCAN_HALF_KM = 0.8
private const val NAME_SCAN_BUDGET = 5_000

/** Candidates whose names are decoded for the final ranking. */
private const val SHORTLIST = 30

/** Place-label candidates decoded besides the shortlist (a city's exact name is wanted
 *  from anywhere). */
private const val PLACE_SHORTLIST = 20

/** Shortest token allowed to drive the candidate scan. */
private const val MIN_DRIVER_LEN = 2

private const val KM_PER_DEGREE = 111.32
private const val E7_PER_DEGREE = 1e7
private const val MIN_COS_LAT = 1e-6
private const val POLL_EVERY = 4_096

/** One candidate row: where it is and how its name matched (a [PoiSearch] tier). */
internal class Candidate(
    val ordinal: Int,
    val latE7: Int,
    val lonE7: Int,
    val type: Int,
    var tier: Int,
    val distKm: Double,
)

/**
 * Name search over the word index. See the file comment for the phases; ranking is
 * [rankCandidates].
 */
internal fun searchByWordIndex(
    m: PoiIndex.Mapped,
    query: String,
    bias: SearchBias,
    cancelled: () -> Boolean = { false },
): List<PoiIndex.PoiRecord> {
    val tokens = m.queryTokens(query)
    if (tokens.isEmpty()) return emptyList()
    val search = CandidateSearch(m, query, tokens, bias, cancelled)
    search.collectIndexed()
    if (search.size < bias.limit) search.collectNearbyNames(acceptLoose = true)
    if (search.size < bias.limit / 2) search.collectIndexTypos()
    if (cancelled()) return emptyList()
    return rankCandidates(m, query, search.candidates(), bias)
}

/** Mutable state of one search: the tokens, the bias point and the candidates so far. */
internal class CandidateSearch(
    private val m: PoiIndex.Mapped,
    query: String,
    private val tokens: List<QueryToken>,
    private val bias: SearchBias,
    private val cancelled: () -> Boolean,
) {
    private val found = LinkedHashMap<Int, Candidate>()
    private val queryNorm = PoiSearch.normalize(query)
    private val queryWords = PoiSearch.tokens(query)
    private val cosLat = Math.cos(Math.toRadians(bias.lat)).coerceAtLeast(MIN_COS_LAT)

    /** The rarest token long enough to drive the scan, or null when none is. */
    private val driver: Int? = tokens.indices
        .filter { tokens[it].text.length >= MIN_DRIVER_LEN && tokens[it].size <= STREAM_CAP }
        .minByOrNull { tokens[it].size }

    /**
     * Tokens whose ranges are scanned: the driver, and every other token whose range is no
     * bigger than [MATERIALIZE_CAP] or the driver's own. A two-letter tail ("st" in "123
     * market st": a million entries against a few thousand) is checked against the names
     * of the few candidates instead of being read whole.
     */
    private val scanned: List<Int> = driver?.let { d ->
        val cap = maxOf(MATERIALIZE_CAP, tokens[d].size)
        tokens.indices.filter {
            it == d || (tokens[it].text.length >= MIN_DRIVER_LEN && tokens[it].size <= cap)
        }
    }.orEmpty()
    private val unscannedKeys: List<List<ByteArray>> = tokens.indices.filter { it !in scanned }
        .map { tokenKeyVariants(tokens[it].text) }

    val size: Int get() = found.size

    fun candidates(): Collection<Candidate> = found.values

    /** Phases 2-3: candidates from the word index, placed near the bias point first. */
    fun collectIndexed() {
        val driver = driver
        when {
            // Every token is one letter or matches millions of words: nothing to scan.
            driver == null -> collectNearbyNames(acceptLoose = false)
            tokens[driver].size == 0 -> Unit
            tokens[driver].size <= MATERIALIZE_CAP -> collectMaterialized(driver)
            else -> collectStreaming(driver)
        }
    }

    /** The driver's whole range in a table, intersected with the other scanned tokens. */
    private fun collectMaterialized(driver: Int) {
        val table = HitTable(tokens.size, tokens[driver].size)
        if (!m.scanToken(tokens[driver], cancelled) { o, e -> table.mark(table.add(o), driver, m.entryWordIdx(e)) }) return
        for (t in scanned) {
            if (t == driver) continue
            val ok = m.scanToken(tokens[t], cancelled) { o, e ->
                val i = table.indexOf(o)
                if (i >= 0) table.mark(i, t, m.entryWordIdx(e))
            }
            if (!ok) return
        }
        val survivors = BooleanArray(table.size) { i -> scanned.all { table.mask(i, it) != 0L } }
        val count = survivors.count { it }
        if (count == 0) return
        // Near first: the grid's ordinals are a sequential read, a survivor's coordinates a
        // random one, so even a small survivor set is only placed whole when the boxes
        // around the bias point did not already fill the page.
        for (half in RING_HALF_KM) {
            forEachOrdinalNear(half) { o ->
                val i = table.indexOf(o)
                if (i >= 0 && survivors[i]) addIndexed(o, table, i)
                true
            }
            if (found.size >= bias.limit || cancelled()) return
        }
        if (count <= GLOBAL_ALL_CAP) {
            for (i in 0 until table.size) if (survivors[i]) addIndexed(table.ordinals[i], table, i)
        } else {
            collectRegional(table, survivors)
        }
    }

    /**
     * A driver too common to materialise (a two- or three-letter prefix): each box's
     * records go into a small table and the token ranges are scanned against it.
     */
    private fun collectStreaming(driver: Int) {
        for (half in RING_HALF_KM) {
            val local = HitTable(tokens.size, POLL_EVERY)
            var lo = Int.MAX_VALUE
            var hi = Int.MIN_VALUE
            forEachOrdinalNear(half) { o ->
                local.add(o)
                if (o < lo) lo = o
                if (o > hi) hi = o
                true
            }
            if (local.size == 0) continue
            for (t in listOf(driver) + scanned.filter { it != driver }) {
                // The box's ordinals span a narrow slice of the Morton-ordered file, so most of
                // a two-letter prefix's million entries are rejected by one compare.
                val ok = m.scanToken(tokens[t], cancelled) { o, e ->
                    if (o in lo..hi) {
                        val i = local.indexOf(o)
                        if (i >= 0) local.mark(i, t, m.entryWordIdx(e))
                    }
                }
                if (!ok) return
            }
            for (i in 0 until local.size) {
                if (scanned.all { local.mask(i, it) != 0L }) addIndexed(local.ordinals[i], local, i)
            }
            if (found.size >= bias.limit) return
        }
    }

    /**
     * Survivors nearest the bias point in Morton order — the file's order, so a window of
     * them around the bias point's own position is mostly the region around it — for when
     * even the largest box came up short (a rare chain, a small town).
     */
    private fun collectRegional(table: HitTable, survivors: BooleanArray) {
        var n = 0
        val packed = LongArray(survivors.count { it })
        for (i in 0 until table.size) {
            if (survivors[i]) packed[n++] = (table.ordinals[i].toLong() shl Int.SIZE_BITS) or i.toLong()
        }
        packed.sort()
        val centre = m.lowerBound(spatialFromE7(toE7(bias.lat), toE7(bias.lon)))
        var hi = lowerBoundPacked(packed, centre)
        var lo = hi - 1
        var taken = 0
        while (taken < REGIONAL_WINDOW && (lo >= 0 || hi < packed.size)) {
            val pickLow = hi >= packed.size ||
                (lo >= 0 && centre - (packed[lo] ushr Int.SIZE_BITS) <= (packed[hi] ushr Int.SIZE_BITS) - centre)
            val p = if (pickLow) packed[lo--] else packed[hi++]
            addIndexed((p ushr Int.SIZE_BITS).toInt(), table, p.toInt())
            taken++
        }
    }

    /**
     * Phase 4a: names of the records closest to the bias point, matched leniently
     * ([acceptLoose]: mid-word substrings and typos too) or by word prefix only.
     */
    fun collectNearbyNames(acceptLoose: Boolean) {
        if (queryWords.isEmpty()) return
        var visited = 0
        val halves = if (acceptLoose) doubleArrayOf(NAME_SCAN_HALF_KM) else RING_HALF_KM.copyOf(2)
        for (half in halves) {
            forEachOrdinalNear(half) { o ->
                if (o !in found) {
                    visited++
                    val name = m.nameAt(m.nameOff(o))
                    val tier = if (name == null) PoiSearch.NO_MATCH else PoiSearch.matchTier(name, queryNorm, queryWords)
                    val accept = tier != PoiSearch.NO_MATCH && (acceptLoose || tier <= PoiSearch.TIER_TOKENS)
                    if (accept) add(o, tier)
                }
                visited < NAME_SCAN_BUDGET && !(visited % POLL_EVERY == 0 && cancelled())
            }
            if (found.size >= bias.limit || visited >= NAME_SCAN_BUDGET) return
        }
    }

    /**
     * Phase 4b: index-wide typo lookup ([fuzzyWordMatches]), intersected across the tokens
     * long enough to have a typo budget. Last resort: it costs a binary search per
     * single-deletion spelling.
     */
    fun collectIndexTypos() {
        val words = tokens.map { it.text }.filter { it.length >= MIN_TYPO_TOKEN_LEN }
        if (words.isEmpty() || words.size != tokens.size) return
        var common: Map<Int, Int>? = null
        for (w in words) {
            if (cancelled()) return
            val hits = m.fuzzyWordMatches(w, PoiIndex.CANDIDATE_CAP)
            common = common?.filterKeys { it in hits } ?: hits
        }
        common.orEmpty().keys.forEach { o -> if (o !in found) add(o, PoiSearch.TIER_FUZZY) }
    }

    /** Add an index candidate whose scanned tokens all matched; checks the rest by name. */
    private fun addIndexed(ordinal: Int, table: HitTable, index: Int) {
        if (ordinal in found) return
        if (unscannedKeys.isNotEmpty() && unscannedKeys.any { !m.nameHasWordPrefix(ordinal, it) }) return
        // Phrase: token t starts word t, for every scanned token.
        val phrase = scanned.all { t -> table.mask(index, t) and (1L shl t) != 0L }
        add(ordinal, if (phrase) PoiSearch.TIER_PREFIX else PoiSearch.TIER_TOKENS)
    }

    private fun add(ordinal: Int, tier: Int) {
        val lat = m.latE7(ordinal)
        val lon = m.lonE7(ordinal)
        found[ordinal] = Candidate(ordinal, lat, lon, m.type(ordinal), tier, distanceKm(lat, lon))
    }

    private fun distanceKm(latE7: Int, lonE7: Int): Double =
        Math.sqrt(PoiIndex.distanceSq(bias.lat, bias.lon, latE7 / E7_PER_DEGREE, lonE7 / E7_PER_DEGREE)) *
            KM_PER_DEGREE

    /** Ordinals filed under the grid cells of the box of half-size [halfKm] around the bias point. */
    private fun forEachOrdinalNear(halfKm: Double, visit: (Int) -> Boolean) {
        val dLat = halfKm / KM_PER_DEGREE
        val dLon = halfKm / (KM_PER_DEGREE * cosLat)
        m.forEachGridOrdinal(
            toE7(bias.lat - dLat), toE7(bias.lat + dLat), toE7(bias.lon - dLon), toE7(bias.lon + dLon), visit,
        )
    }
}

private fun toE7(deg: Double): Int = (deg * E7_PER_DEGREE).toInt()

/** First index in [packed] (ordinal in the high half, ascending) whose ordinal is >= [ordinal]. */
private fun lowerBoundPacked(packed: LongArray, ordinal: Int): Int {
    var lo = 0
    var hi = packed.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if ((packed[mid] ushr Int.SIZE_BITS) < ordinal) lo = mid + 1 else hi = mid
    }
    return lo
}

private class RankedRow(
    val record: PoiIndex.PoiRecord,
    val rank: Int,
    val exact: Boolean,
    val boost: Double,
    val distKm: Double,
)

/** "Local" for the exact-name tier: ~0.5° (≈55 km) from the centre. */
private const val EXACT_LOCAL_KM = 55.0

/** The pre-planet rank buckets: phrase, every word (or a substring), typo. */
private fun rankOf(tier: Int): Int = when (tier) {
    PoiSearch.TIER_EXACT, PoiSearch.TIER_PREFIX -> 0
    PoiSearch.TIER_TOKENS, PoiSearch.TIER_SUBSTRING, PoiSearch.TIER_CATEGORY -> 1
    else -> 2
}

/**
 * Rank, then decode only the rows that can be returned: the [SHORTLIST] best by (rank,
 * distance) plus the nearest phrase-matching place labels. Within a rank a name that IS
 * the query comes first when it is a place label or local, then the user's signal, then
 * distance.
 */
internal fun rankCandidates(
    m: PoiIndex.Mapped,
    query: String,
    candidates: Collection<Candidate>,
    bias: SearchBias,
): List<PoiIndex.PoiRecord> {
    val byRank = candidates.sortedWith(compareBy<Candidate> { rankOf(it.tier) }.thenBy { it.distKm })
    val places = byRank.filter { it.type == PoiCategories.TYPE_PLACE && rankOf(it.tier) == 0 }.take(PLACE_SHORTLIST)
    val shortlist = (byRank.take(SHORTLIST) + places).distinctBy { it.ordinal }
    val whole = query.trim()
    val out = shortlist.map { c ->
        val record = m.record(c.ordinal)
        val exact = record.name.equals(whole, ignoreCase = true) &&
            (record.type == PoiCategories.TYPE_PLACE || c.distKm <= EXACT_LOCAL_KM)
        RankedRow(record, rankOf(c.tier), exact, boostWeight(bias.boost, record.name), c.distKm)
    }
    return out.sortedWith(
        compareBy<RankedRow> { it.rank }.thenBy { !it.exact }.thenByDescending { it.boost }.thenBy { it.distKm },
    ).take(bias.limit).map { it.record }
}
