package com.vayunmathur.maps.util

/**
 * Planet-scale halves of the indexed name search ([searchByWordIndex]).
 *
 * The word index was designed against state-sized archives, where walking the first
 * [PoiIndex.CANDIDATE_CAP] entries of a word's prefix range saw every match. A planet
 * (planet4: 30.9 M records, 69.6 M word entries) breaks that: a range is sorted by word and
 * then by ordinal, ordinals are Morton-sorted, so a capped walk sees one arbitrary corner of
 * the world. Two fixes, both bounded:
 *
 *  * [rarestTokenMatches] — measure every token's range (two binary searches each), walk the
 *    smallest one whole, and check the other tokens against each candidate's own name.
 *  * [localWordMatches] — scan the spatial grid around the search centre in growing rings,
 *    under a visit budget, so a common word ("cafe") still finds what is near the user. It
 *    reads each name anyway, so it also answers mid-word substrings ("izza" → "Pizza") the
 *    way the scan path does, which a sorted word index cannot.
 */

/** Rank of a name that starts with the whole phrase (tokens on its first words, in order). */
private const val RANK_PHRASE = 0

/** Rank of a name where every token starts some word. */
private const val RANK_WORDS = 1

/** No match. */
private const val RANK_NONE = -1

private const val BYTE_MASK = 0xFF

/** Rings the local pass grows through, in metres from the search centre. */
private val LOCAL_RADII_M = doubleArrayOf(2_000.0, 10_000.0, 40_000.0, 150_000.0)

/** Records the local pass checks at most, across all rings. */
private const val LOCAL_VISIT_BUDGET = 40_000

private const val METERS_PER_DEGREE_LAT = 111_320.0
private const val MIN_COS_LAT = 1e-6
private const val E7_PER_DEGREE = 1e7

/** Entries `[first, last]` whose word starts with [key]; empty when none. */
internal fun PoiIndex.Mapped.prefixRange(key: ByteArray): IntRange {
    if (nameIdx == null || entryCount == 0) return IntRange.EMPTY
    val lo = lowerBoundWord(key)
    val hi = nextPrefix(key)?.let { lowerBoundWord(it) } ?: entryCount
    return lo until hi
}

/**
 * The smallest key strictly greater than every key starting with [key] (its last
 * non-0xFF byte incremented, the rest dropped), or null when no such key exists.
 */
private fun nextPrefix(key: ByteArray): ByteArray? {
    var i = key.size - 1
    while (i >= 0 && (key[i].toInt() and BYTE_MASK) == BYTE_MASK) i--
    if (i < 0) return null
    return key.copyOf(i + 1).also { it[i] = (it[i] + 1).toByte() }
}

/**
 * Ordinal → rank for every record whose name matches all [keys], found by walking the
 * rarest key's whole prefix range; null when even that range is longer than [walkCap]
 * (the caller then falls back to capped walks plus the local pass).
 */
internal fun PoiIndex.Mapped.rarestTokenMatches(keys: List<ByteArray>, walkCap: Int): Map<Int, Int>? {
    if (keys.isEmpty() || nameIdx == null) return null
    val ranges = keys.map { prefixRange(it) }
    val rarest = ranges.minBy { it.last - it.first + 1 }
    val size = rarest.last - rarest.first + 1
    if (size <= 0) return emptyMap()
    if (size > walkCap) return null
    val out = HashMap<Int, Int>()
    for (i in rarest) {
        val ordinal = entryOrdinal(i)
        if (ordinal < 0 || ordinal >= count || ordinal in out) continue
        val rank = nameTokenRank(ordinal, keys)
        if (rank != RANK_NONE) out[ordinal] = rank
    }
    return out
}

/**
 * The pre-planet exact pass: per-token walks capped at [cap] entries, intersected on the
 * ordinal, each survivor re-ranked from its own name. Only used when every token is too
 * common to walk whole.
 */
internal fun cappedExactMatches(m: PoiIndex.Mapped, keys: List<ByteArray>, cap: Int): Map<Int, Int> {
    var ordinals: Set<Int>? = null
    for (key in keys) {
        val hits = m.wordPrefixMatches(key, cap).mapTo(HashSet()) { it[0] }
        ordinals = ordinals?.intersect(hits) ?: hits
    }
    val out = HashMap<Int, Int>()
    for (o in ordinals.orEmpty()) {
        val rank = m.nameTokenRank(o, keys)
        if (rank != RANK_NONE) out[o] = rank
    }
    return out
}

/**
 * Ordinal → rank for records near ([lat],[lon]) whose name matches all [keys]: rings of
 * [LOCAL_RADII_M] through the spatial index, nearest ring first, stopping once [want]
 * matches are in hand or [LOCAL_VISIT_BUDGET] records were checked.
 */
internal fun PoiIndex.Mapped.localWordMatches(
    keys: List<ByteArray>,
    lat: Double,
    lon: Double,
    want: Int,
): Map<Int, Int> {
    if (keys.isEmpty() || count == 0) return emptyMap()
    val out = HashMap<Int, Int>()
    val seen = HashSet<Int>()
    var visited = 0
    val cosLat = Math.cos(Math.toRadians(lat)).coerceAtLeast(MIN_COS_LAT)
    for (radius in LOCAL_RADII_M) {
        val dLat = radius / METERS_PER_DEGREE_LAT
        val dLon = radius / (METERS_PER_DEGREE_LAT * cosLat)
        forEachInBbox(
            ((lat - dLat) * E7_PER_DEGREE).toInt(),
            ((lat + dLat) * E7_PER_DEGREE).toInt(),
            ((lon - dLon) * E7_PER_DEGREE).toInt(),
            ((lon + dLon) * E7_PER_DEGREE).toInt(),
        ) { ordinal, _, _ ->
            if (seen.add(ordinal)) {
                visited++
                var rank = nameTokenRank(ordinal, keys)
                if (rank == RANK_NONE && nameContainsAll(ordinal, keys)) rank = RANK_WORDS
                if (rank != RANK_NONE) out[ordinal] = rank
            }
            visited < LOCAL_VISIT_BUDGET
        }
        if (out.size >= want || visited >= LOCAL_VISIT_BUDGET) break
    }
    return out
}

/**
 * How record [ordinal]'s name matches [keys]: [RANK_PHRASE] when word `t` starts with key
 * `t` for every token, [RANK_WORDS] when every key starts some word, else [RANK_NONE].
 * Same word split and ASCII case fold as the index itself (`poi_side.rs`).
 */
internal fun PoiIndex.Mapped.nameTokenRank(ordinal: Int, keys: List<ByteArray>): Int {
    val off = nameOff(ordinal)
    val matched = BooleanArray(keys.size)
    var phrase = true
    var w = 0
    while (true) {
        val range = wordRange(off, w) ?: break
        for (t in keys.indices) {
            val hit = wordStartsWith(range, keys[t])
            if (hit) matched[t] = true
            if (w == t && !hit) phrase = false
        }
        w++
    }
    if (w < keys.size) phrase = false
    return when {
        matched.any { !it } -> RANK_NONE
        phrase -> RANK_PHRASE
        else -> RANK_WORDS
    }
}

/** Whether every key occurs somewhere in record [ordinal]'s name (ASCII case-folded bytes). */
private fun PoiIndex.Mapped.nameContainsAll(ordinal: Int, keys: List<ByteArray>): Boolean {
    val off = nameOff(ordinal)
    if (off < 0 || off >= namesLen) return false
    var end = off
    while (end < namesLen && names.get(end).toInt() != 0) end++
    val folded = ByteArray(end - off) { asciiLowerW(names.get(off + it)).toByte() }
    return keys.all { key -> indexOf(folded, key) >= 0 }
}

/** First index of [needle] in [hay], or -1. Names are short; a naive scan is enough. */
private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
    if (needle.isEmpty()) return 0
    outer@ for (i in 0..hay.size - needle.size) {
        for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
        return i
    }
    return -1
}

/** Candidates whose names are decoded and boost-ranked; the rest lose on (rank, distance). */
private const val DECODE_SHORTLIST = 5_000

/**
 * Fuzzy pass: ordinal → rank 2 for names where every token matches exactly or within one
 * edit, minus the exact [ordinals]. Runs even when the exact pass hit — a typo'd name
 * ("Piza Kitchen" for "pizza") should surface below the exact hits, not vanish. Tokens under
 * [MIN_TYPO_TOKEN_LEN] chars are all typo-noise and contribute their exact matches only.
 */
internal fun fuzzyMatches(
    m: PoiIndex.Mapped,
    tokens: List<String>,
    exact: List<Map<Int, Int>>,
    ordinals: Set<Int>,
    candidateCap: Int,
): Map<Int, Int> {
    var fuzzyOrdinals: Set<Int>? = null
    for (t in tokens.indices) {
        val set = HashSet(exact[t].keys)
        set.addAll(ordinals)
        if (tokens[t].length >= MIN_TYPO_TOKEN_LEN) {
            set.addAll(m.fuzzyWordMatches(tokens[t], candidateCap).keys)
        }
        fuzzyOrdinals = fuzzyOrdinals?.intersect(set) ?: set
    }
    return fuzzyOrdinals.orEmpty().filter { it !in ordinals }.associateWith { 2 }
}

/**
 * At most [DECODE_SHORTLIST] of [candidates] (ordinal, rank), best (rank, distance) first.
 * A whole-range walk can hand back tens of thousands of matches; decoding every name (for
 * the record and the boost) would dominate the search, so the cut uses only the index's
 * coordinates.
 */
internal fun PoiIndex.Mapped.shortlist(
    candidates: List<Pair<Int, Int>>,
    lat: Double,
    lon: Double,
): List<Pair<Int, Int>> {
    if (candidates.size <= DECODE_SHORTLIST) return candidates
    return candidates.sortedWith(
        compareBy<Pair<Int, Int>> { it.second }.thenBy { (o, _) -> distanceSqTo(o, lat, lon) },
    ).take(DECODE_SHORTLIST)
}

/** Squared degree distance from record [ordinal] to ([lat],[lon]), straight from the index. */
private fun PoiIndex.Mapped.distanceSqTo(ordinal: Int, lat: Double, lon: Double): Double =
    PoiIndex.distanceSq(lat, lon, latE7(ordinal) * PoiIndex.E7_TO_DEGREES, lonE7(ordinal) * PoiIndex.E7_TO_DEGREES)
