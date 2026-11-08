package com.vayunmathur.maps.util

/**
 * The word-index half of [PoiIndex]: prefix search over `poi_name_index.bin`.
 *
 * Split out so PoiIndex.kt stays under the FileLength limit. These are `internal`
 * extensions on `PoiIndex.Mapped` — which therefore has to be visible outside the
 * file — plus the indexed search entry point that [PoiIndex.searchByName]
 * delegates to.
 */

private const val WORD_BYTE_MASK = 0xFF
private const val WORD_CASE_OFFSET = 32
private const val WORD_VT = 0x0B
private const val WORD_FF = 0x0C
private const val WORD_ENTRY_BYTES = 5L
/** Bytes of one int32 slot in the word-index arrays. */
private const val INT_SLOT_BYTES = 4

/**
 * ASCII-only lowercase, and deliberately not [Char.lowercase].
 *
 * `poi_name_index.bin` is sorted by the writer, so the reader has to reproduce that
 * order byte for byte. Rust's `to_lowercase` and Kotlin's `lowercase` do not agree on
 * every input, and here a disagreement is a POI that can never be found. See the
 * cross-language contract in `osm_ingest/src/poi_side.rs`.
 */
internal fun asciiLowerW(b: Byte): Int {
    val v = b.toInt() and WORD_BYTE_MASK
    return if (v >= 'A'.code && v <= 'Z'.code) v + WORD_CASE_OFFSET else v
}

internal fun isAsciiSpaceW(b: Byte): Boolean {
    val v = b.toInt() and WORD_BYTE_MASK
    return v == ' '.code || v == '\t'.code || v == '\n'.code || v == '\r'.code ||
        v == WORD_VT || v == WORD_FF
}

/** A query as the index's sort key: UTF-8 bytes, ASCII-lowercased. */
internal fun poiQueryKey(query: String): ByteArray {
    val raw = query.toByteArray(Charsets.UTF_8)
    return ByteArray(raw.size) { asciiLowerW(raw[it]).toByte() }
}

private fun PoiIndex.Mapped.entryOrdinal(i: Int): Int =
    nameIdx!!.getInt(PoiIndex.NAME_INDEX_HEADER_BYTES + INT_SLOT_BYTES * i)

private fun PoiIndex.Mapped.entryWordIdx(i: Int): Int {
    val off = PoiIndex.NAME_INDEX_HEADER_BYTES + INT_SLOT_BYTES * entryCount + i
    return nameIdx!!.get(off).toInt() and WORD_BYTE_MASK
}

/**
 * Byte range of the [wordIdx]th whitespace-separated word of the name at [off], or
 * null when the name has fewer words. Must match `word_at` in `poi_side.rs`.
 */
private fun PoiIndex.Mapped.wordRange(off: Int, wordIdx: Int): IntRange? {
    if (off < 0 || off >= namesLen) return null
    var i = off
    var idx = 0
    while (hasNameByte(i)) {
        i = skipSpaces(i)
        if (!hasNameByte(i)) break
        val start = i
        i = skipWord(i)
        if (idx == wordIdx) return start until i
        idx++
    }
    return null
}

/** Whether [i] is still inside the NUL-terminated name. */
private fun PoiIndex.Mapped.hasNameByte(i: Int): Boolean =
    i < namesLen && names.get(i).toInt() != 0

/** Advance past whitespace within the name. */
private fun PoiIndex.Mapped.skipSpaces(i: Int): Int {
    var j = i
    while (hasNameByte(j) && isAsciiSpaceW(names.get(j))) j++
    return j
}

/** Advance past one word within the name. */
private fun PoiIndex.Mapped.skipWord(i: Int): Int {
    var j = i
    while (hasNameByte(j) && !isAsciiSpaceW(names.get(j))) j++
    return j
}

private fun PoiIndex.Mapped.entryWord(i: Int): IntRange? =
    wordRange(nameOff(entryOrdinal(i)), entryWordIdx(i))

/** ASCII-lowercased byte compare of the word at [range] against [key]. */
private fun PoiIndex.Mapped.compareWord(range: IntRange, key: ByteArray): Int {
    val len = range.last - range.first + 1
    for (k in 0 until minOf(len, key.size)) {
        val d = asciiLowerW(names.get(range.first + k)) - (key[k].toInt() and WORD_BYTE_MASK)
        if (d != 0) return d
    }
    return len - key.size
}

private fun PoiIndex.Mapped.wordStartsWith(range: IntRange, key: ByteArray): Boolean {
    if (range.last - range.first + 1 < key.size) return false
    for (k in key.indices) {
        if (asciiLowerW(names.get(range.first + k)) != (key[k].toInt() and WORD_BYTE_MASK)) return false
    }
    return true
}

/** First entry whose word is >= [key], or [entryCount]. */
private fun PoiIndex.Mapped.lowerBoundWord(key: ByteArray): Int {
    var lo = 0
    var hi = entryCount
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        val w = entryWord(mid)
        // A word we cannot resolve sorts first, so the search steps past it rather
        // than stalling on a name the index disagrees with.
        if (w == null || compareWord(w, key) < 0) lo = mid + 1 else hi = mid
    }
    return lo
}

/**
 * Ordinals whose name has a word starting with [key], each paired with whether the
 * match was on the name's *first* word.
 *
 * Binary search plus a walk of the matching range, so the cost is the number of
 * matches rather than the size of the name pool.
 */
internal fun PoiIndex.Mapped.wordPrefixMatches(key: ByteArray, cap: Int): List<IntArray> {
    if (nameIdx == null || entryCount == 0) return emptyList()
    val out = ArrayList<IntArray>()
    val seen = HashSet<Int>()
    var i = lowerBoundWord(key)
    while (i < entryCount && out.size < cap && matchesAt(i, key)) {
        val ordinal = entryOrdinal(i)
        // A name can match on more than one word ("Pizza Pizza"); the better rank
        // wins, and the index lists word 0 first within one name.
        if (seen.add(ordinal)) out.add(intArrayOf(ordinal, entryWordIdx(i)))
        i++
    }
    return out
}

/** Whether entry [i]'s word still has [key] as a prefix (null word sorts out). */
private fun PoiIndex.Mapped.matchesAt(i: Int, key: ByteArray): Boolean {
    val w = entryWord(i) ?: return false
    return wordStartsWith(w, key)
}

private class Ranked(val record: PoiIndex.PoiRecord, val rank: Int, val distSq: Double)

/**
 * Weight of [name] from the caller's recency/favorites signal, or 0.
 *
 * Keys are lowercased full names. An exact hit wins outright; otherwise the
 * best key the name merely contains still counts — a recent query is usually
 * a prefix of the place it found ("blue bottle" → "Blue Bottle Coffee"), not
 * the whole name. This only reorders candidates the text match already
 * accepted; it never resurrects a non-match.
 */
internal fun boostWeight(boost: Map<String, Double>, name: String): Double {
    if (boost.isEmpty()) return 0.0
    val lower = name.lowercase()
    boost[lower]?.let { return it }
    var best = 0.0
    for ((key, weight) in boost) {
        if (weight > best && lower.contains(key)) best = weight
    }
    return best
}

/**
 * Whether [a] and [b] differ by at most one insertion, deletion, or
 * substitution — the cheap typo tolerance. Returns false fast on a length
 * gap over one; otherwise a small full DP (names/words are short, and the
 * caller only reaches here when the substring pass already failed).
 */
internal fun editDistanceLE1(a: String, b: String): Boolean {
    if (a == b) return true
    val n = a.length
    val m = b.length
    if (kotlin.math.abs(n - m) > 1) return false
    var prev = IntArray(m + 1) { it }
    var cur = IntArray(m + 1)
    for (i in 1..n) {
        cur[0] = i
        var rowMin = cur[0]
        for (j in 1..m) {
            cur[j] = minOf(
                prev[j] + 1,
                cur[j - 1] + 1,
                prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
            )
            if (cur[j] < rowMin) rowMin = cur[j]
        }
        if (rowMin > 1) return false
        val tmp = prev
        prev = cur
        cur = tmp
    }
    return prev[m] <= 1
}

/**
 * The unified offline search behind [PoiIndex.searchByName].
 *
 * Matching is token-based (see [PoiSearch]): every query token must match
 * a word of the place name — by word prefix, substring, or (as a last
 * resort) a small edit distance — so multi-word queries, diacritics and
 * small typos all resolve. Category terms ("coffee", "gas station") also
 * match places by their POI type. There is deliberately no bounding-box
 * restriction: the whole offline index is searched and results are ranked
 * by match tier, then by distance to ([nearLat], [nearLon]), so the
 * nearest good match wins wherever it is.
 *
 * Candidate gathering prefers the word index (one binary search per
 * token, intersected by ordinal) and falls back to the name-pool scan
 * when the index is absent or the intersection is empty — which is also
 * the path that provides substring and fuzzy matches the sorted index
 * cannot answer. A per-token indexed fuzzy supplement (see
 * [collectIndexedFuzzyMatches]) catches typo'd names the exact prefix
 * intersection misses.
 *
 * Within one tier, [boost] (recents + saved places) breaks ties — it never
 * resurrects a non-match.
 */
internal fun searchPoi(
    m: PoiIndex.Mapped,
    query: String,
    nearLat: Double,
    nearLon: Double,
    limit: Int,
    boost: Map<String, Double> = emptyMap(),
): List<PoiIndex.PoiRecord> {
    val queryTokens = PoiSearch.tokens(query)
    if (queryTokens.isEmpty()) return emptyList()
    val queryNorm = queryTokens.joinToString(" ")

    // ordinal -> best tier so far.
    val tiers = HashMap<Int, Int>()
    if (m.nameIdx != null) {
        collectIndexedMatches(m, queryTokens, queryNorm, tiers)
        collectIndexedFuzzyMatches(m, queryTokens, queryNorm, tiers)
    }
    if (tiers.isEmpty()) {
        collectScanMatches(m, queryNorm, queryTokens, tiers)
    }
    collectCategoryMatches(m, queryTokens, tiers)

    if (tiers.isEmpty()) return emptyList()
    val ranked = ArrayList<Ranked>(tiers.size)
    for ((ordinal, tier) in tiers) {
        if (ordinal < 0 || ordinal >= m.count) continue
        ranked.add(
            Ranked(
                m.record(ordinal),
                tier,
                PoiIndex.distanceSq(
                    m.latE7(ordinal) * PoiIndex.E7_TO_DEGREES,
                    m.lonE7(ordinal) * PoiIndex.E7_TO_DEGREES,
                    nearLat,
                    nearLon,
                ),
            )
        )
    }
    ranked.sortWith(
        compareBy<Ranked> { it.rank }
            .thenByDescending { boostWeight(boost, it.record.name) }
            .thenBy { it.distSq },
    )
    return ranked.take(limit).map { it.record }
}

/**
 * Intersect per-token word-prefix matches from the word index: an ordinal
 * is a candidate only when every query token prefixes some word of its
 * name. Tiers are then scored exactly with [PoiSearch.matchTier].
 */
private fun collectIndexedMatches(
    m: PoiIndex.Mapped,
    queryTokens: List<String>,
    queryNorm: String,
    tiers: HashMap<Int, Int>,
) {
    var candidates: MutableSet<Int>? = null
    for (token in queryTokens) {
        val hits = m.wordPrefixMatches(poiQueryKey(token), PoiIndex.CANDIDATE_CAP)
        val ordinals = hits.mapTo(HashSet()) { it[0] }
        candidates = candidates?.apply { retainAll(ordinals) } ?: ordinals
        if (candidates.isEmpty()) return
    }
    for (ordinal in candidates.orEmpty()) {
        if (ordinal < 0 || ordinal >= m.count) continue
        val tier = PoiSearch.matchTier(m.record(ordinal).name, queryNorm, queryTokens)
        if (tier != PoiSearch.NO_MATCH) tiers[ordinal] = tier
    }
}

/**
 * Indexed fuzzy supplement: a typo'd name ("Piza Kitchen" for "pizza")
 * should surface below the exact hits, not vanish. Runs per query token
 * (deletion variants as prefix lookups, verified by edit distance — see
 * [fuzzyWordMatches]), and tiers are scored exactly with
 * [PoiSearch.matchTier] so ranking stays tier-first. Ordinals the exact
 * pass already found keep their tier; tokens under 3 chars are all
 * typo-noise and skip the pass.
 */
private fun collectIndexedFuzzyMatches(
    m: PoiIndex.Mapped,
    queryTokens: List<String>,
    queryNorm: String,
    tiers: HashMap<Int, Int>,
) {
    for (token in queryTokens) {
        if (token.length < 3) continue
        for (ordinal in m.fuzzyWordMatches(token, PoiIndex.CANDIDATE_CAP).keys) {
            if (ordinal < 0 || ordinal >= m.count || ordinal in tiers) continue
            val tier = PoiSearch.matchTier(m.record(ordinal).name, queryNorm, queryTokens)
            if (tier != PoiSearch.NO_MATCH) tiers[ordinal] = tier
        }
    }
}

/** Name-pool scan: match unique names once, then join records by offset. */
private fun collectScanMatches(
    m: PoiIndex.Mapped,
    queryNorm: String,
    queryTokens: List<String>,
    tiers: HashMap<Int, Int>,
) {
    val matchRank = matchNamePool(m, queryNorm, queryTokens)
    if (matchRank.isEmpty()) return
    var i = 0
    while (i < m.count && tiers.size < PoiIndex.CANDIDATE_CAP) {
        matchRank[m.nameOff(i)]?.let { tiers[i] = it }
        i++
    }
}

private fun matchNamePool(
    m: PoiIndex.Mapped,
    queryNorm: String,
    queryTokens: List<String>,
): Map<Int, Int> {
    val matchRank = HashMap<Int, Int>()
    var pos = 0
    while (pos < m.namesLen) {
        var end = pos
        while (end < m.namesLen && m.names.get(end).toInt() != 0) end++
        if (end > pos) {
            val name = m.nameAt(pos)
            if (name != null) {
                val tier = PoiSearch.matchTier(name, queryNorm, queryTokens)
                if (tier != PoiSearch.NO_MATCH) matchRank[pos] = tier
            }
        }
        pos = end + 1
    }
    return matchRank
}

/**
 * Category candidates: when every query token is a category term ("gas",
 * "station", "coffee"), places of those POI types match at
 * [PoiSearch.TIER_CATEGORY] even though their names do not contain the
 * words. This is what makes the category chips work offline.
 */
private fun collectCategoryMatches(
    m: PoiIndex.Mapped,
    queryTokens: List<String>,
    tiers: HashMap<Int, Int>,
) {
    val types = HashSet<Int>()
    for (token in queryTokens) {
        val found = PoiCategories.typesForTerm(PoiSearch.singular(token))
        if (found.isEmpty()) return // Not a purely categorical query.
        types.addAll(found)
    }
    if (types.isEmpty()) return
    var i = 0
    while (i < m.count && tiers.size < PoiIndex.CANDIDATE_CAP) {
        if (m.type(i) in types) tiers.putIfAbsent(i, PoiSearch.TIER_CATEGORY)
        i++
    }
}

/**
 * Ordinals with a word within one edit of the query [token], as
 * ordinal → word index.
 *
 * The word index only answers prefix questions, so each single-deletion
 * variant of the token is looked up as a prefix and the underlying word is
 * verified by edit distance — a one-edit typo always shares a long-enough
 * prefix with the true word unless the edit is at position 0, which this
 * deliberately misses (documented: first-letter typos need a different
 * index). Tokens under 3 chars are all typo-noise and skip the pass.
 */
private fun PoiIndex.Mapped.fuzzyWordMatches(
    token: String,
    candidateCap: Int,
): Map<Int, Int> {
    val tokenLower = token.lowercase()
    // Strings, not byte arrays: array equality is referential, and repeated
    // letters ("aa" → "a", "a") would otherwise look the same word up twice.
    val variants = LinkedHashSet<String>()
    for (i in tokenLower.indices) {
        tokenLower.removeRange(i, i + 1).takeIf { it.isNotEmpty() }?.let { variants.add(it) }
    }
    // A mid-word substitution ("pizxa" for "pizza") shares no full deletion
    // with the true word, but does share a two-shorter prefix.
    if (tokenLower.length >= 5) {
        variants.add(tokenLower.dropLast(2))
    }
    val out = HashMap<Int, Int>()
    for (variant in variants) {
        val key = poiQueryKey(variant)
        for (hit in wordPrefixMatches(key, minOf(candidateCap, FUZZY_PER_VARIANT_CAP))) {
            val ordinal = hit[0]
            if (ordinal in out) continue
            if (wordWithinOneEdit(ordinal, hit[1], tokenLower)) out[ordinal] = hit[1]
        }
        if (out.size >= FUZZY_CANDIDATE_CAP) break
    }
    return out
}

/** Whether the [wordIdx]th word of record [ordinal] is one edit from [token]. */
private fun PoiIndex.Mapped.wordWithinOneEdit(ordinal: Int, wordIdx: Int, token: String): Boolean {
    val range = wordRange(nameOff(ordinal), wordIdx) ?: return false
    val len = range.last - range.first + 1
    if (kotlin.math.abs(len - token.length) > 1) return false
    val word = CharArray(len) { i ->
        asciiLowerW(names.get(range.first + i)).toChar()
    }
    return editDistanceLE1(token, String(word))
}

/** Per-variant walk cap and total fuzzy-candidate cap for one token. */
private const val FUZZY_PER_VARIANT_CAP = 50
private const val FUZZY_CANDIDATE_CAP = 500
