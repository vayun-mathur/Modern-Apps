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

internal fun PoiIndex.Mapped.entryOrdinal(i: Int): Int =
    nameIdx!!.getInt(PoiIndex.NAME_INDEX_HEADER_BYTES + INT_SLOT_BYTES * i)

internal fun PoiIndex.Mapped.entryWordIdx(i: Int): Int {
    val off = PoiIndex.NAME_INDEX_HEADER_BYTES + INT_SLOT_BYTES * entryCount + i
    return nameIdx!!.get(off).toInt() and WORD_BYTE_MASK
}

/**
 * Byte range of the [wordIdx]th whitespace-separated word of the name at [off], or
 * null when the name has fewer words. Must match `word_at` in `poi_side.rs`.
 */
internal fun PoiIndex.Mapped.wordRange(off: Int, wordIdx: Int): IntRange? {
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

internal fun PoiIndex.Mapped.wordStartsWith(range: IntRange, key: ByteArray): Boolean {
    if (range.last - range.first + 1 < key.size) return false
    for (k in key.indices) {
        if (asciiLowerW(names.get(range.first + k)) != (key[k].toInt() and WORD_BYTE_MASK)) return false
    }
    return true
}

/** First entry whose word is >= [key], or [entryCount]. */
internal fun PoiIndex.Mapped.lowerBoundWord(key: ByteArray): Int {
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

private class WordRanked(
    val record: PoiIndex.PoiRecord,
    val boost: Double,
    val rank: Int,
    val distSq: Double,
)

private class Ranked(
    val record: PoiIndex.PoiRecord,
    val boost: Double,
    val rank: Int,
    val distSq: Double,
)

/** Whitespace-separated, non-empty query tokens; callers fold case first. */
private fun splitTokens(q: String): List<String> =
    q.split(' ', '\t', '\n', '\r', '', '').filter { it.isNotEmpty() }

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

internal fun searchByScan(
    m: PoiIndex.Mapped,
    query: String,
    nearLat: Double,
    nearLon: Double,
    limit: Int,
    boost: Map<String, Double> = emptyMap(),
): List<PoiIndex.PoiRecord> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    val tokens = splitTokens(q)

    // Pass 1: walk the name pool once, recording matching offsets and their
    // rank (0 = phrase prefix, 1 = every token a substring, 2 = every token a
    // substring or a one-edit typo of a word). Decoding each unique name once
    // is the dedup win the side-file layout is designed for.
    val matchRank = matchNamePool(m, q, tokens)
    if (matchRank.isEmpty()) return emptyList()

    // Pass 2: scan records, keeping those whose name offset matched.
    val out = collectScanMatches(m, matchRank, nearLat, nearLon, boost)
    out.sortWith(compareBy<Ranked> { it.rank }.thenByDescending { it.boost }.thenBy { it.distSq })
    return out.take(limit).map { it.record }
}

private fun matchNamePool(m: PoiIndex.Mapped, q: String, tokens: List<String>): Map<Int, Int> {
    val matchRank = HashMap<Int, Int>()
    var pos = 0
    while (pos < m.namesLen) {
        var end = pos
        while (end < m.namesLen && m.names.get(end).toInt() != 0) end++
        if (end > pos) {
            rankName(m.nameAt(pos), q, tokens)?.let { matchRank[pos] = it }
        }
        pos = end + 1
    }
    return matchRank
}

/**
 * Rank of [name] against the query (0 = phrase prefix, 1 = every token a
 * substring, 2 = every token a substring or a one-edit typo of a word), or
 * null. Single-token queries behave exactly as before; multi-token queries
 * used to require the whole spaced string as one substring, so "ferry
 * building" matched nothing — now each word only has to appear.
 */
private fun rankName(name: String?, q: String, tokens: List<String>): Int? {
    if (name == null) return null
    val lower = name.lowercase()
    if (lower.startsWith(q)) return 0
    if (tokens.all { lower.contains(it) }) return 1
    if (tokens.size > 1 || (tokens.size == 1 && tokens[0].length >= MIN_TYPO_TOKEN_LEN)) {
        val words = splitTokens(lower)
        if (tokens.all { token -> tokenMatchesWord(token, lower, words) }) return 2
    }
    return null
}

/** Whether [token] is a substring of the name or a typo of one of its words. */
private fun tokenMatchesWord(token: String, lowerName: String, words: List<String>): Boolean {
    if (lowerName.contains(token)) return true
    // Tokens under 3 chars are all typo-noise ("al" is one edit from "ax",
    // "at", "an", ...); they only match exactly.
    if (token.length < MIN_TYPO_TOKEN_LEN) return false
    return words.any { editDistanceLE1(token, it) }
}

private fun collectScanMatches(
    m: PoiIndex.Mapped,
    matchRank: Map<Int, Int>,
    nearLat: Double,
    nearLon: Double,
    boost: Map<String, Double>,
): ArrayList<Ranked> {
    val out = ArrayList<Ranked>(minOf(PoiIndex.CANDIDATE_CAP, m.count))
    var i = 0
    while (i < m.count && out.size < PoiIndex.CANDIDATE_CAP) {
        scanMatchAt(m, i, matchRank, nearLat, nearLon, boost)?.let { out.add(it) }
        i++
    }
    return out
}

private fun scanMatchAt(
    m: PoiIndex.Mapped,
    i: Int,
    matchRank: Map<Int, Int>,
    nearLat: Double,
    nearLon: Double,
    boost: Map<String, Double>,
): Ranked? {
    val off = m.nameOff(i)
    val rank = matchRank[off] ?: return null
    val latE7 = m.latE7(i)
    val lonE7 = m.lonE7(i)
    val name = m.nameAt(off) ?: ""
    return Ranked(
        PoiIndex.PoiRecord(latE7, lonE7, m.type(i), name, i, m.regionId(i)),
        boostWeight(boost, name),
        rank,
        PoiIndex.distanceSq(
            latE7 * PoiIndex.E7_TO_DEGREES,
            lonE7 * PoiIndex.E7_TO_DEGREES,
            nearLat,
            nearLon,
        ),
    )
}

/**
 * Ordinals whose name matches every query token, each paired with its rank:
 * 0 = the name starts with the whole phrase ("blue bottle" → "Blue Bottle
 * Coffee"), 1 = every token starts a word, 2 = every token starts a word or
 * is a one-edit typo of one.
 *
 * Single-token queries behave exactly as before. Multi-token queries used to
 * look up the whole spaced string as one key and always miss; fuzzy matches
 * are new. The fuzzy pass only runs per token (deletion variants of the token
 * as prefix lookups, verified by edit distance), so its cost stays in the
 * number of near-matches rather than the size of the word pool.
 */
internal fun searchByWordIndex(
    m: PoiIndex.Mapped,
    query: String,
    nearLat: Double,
    nearLon: Double,
    limit: Int,
    candidateCap: Int,
    boost: Map<String, Double> = emptyMap(),
): List<PoiIndex.PoiRecord> {
    val tokens = splitTokens(query.trim())
    if (tokens.isEmpty()) return emptyList()
    val keys = tokens.map { poiQueryKey(it) }

    // Exact pass, planet-safe: walk the RAREST token's whole prefix range and verify the
    // other tokens against each candidate's own name ([rarestTokenMatches]). The old
    // per-token walks stopped at [candidateCap] entries in word order, which at planet scale
    // (30.9 M records, 69.6 M word entries in planet4) is an arbitrary Morton slice of the
    // world: "golden" alone has tens of thousands of entries, so "Golden Gate Bridge" from
    // SF intersected to a Lego model in Carlsbad and "British Museum" to nothing. When even
    // the rarest token is too common to walk whole ("cafe"), fall back to the capped walks;
    // the local pass below then supplies the nearby hits.
    val exactRanks: Map<Int, Int> = m.rarestTokenMatches(keys, FULL_WALK_CAP)
        ?: cappedExactMatches(m, keys, candidateCap)
    val exact = keys.map { key ->
        if (keys.size == 1) {
            emptyMap()
        } else {
            m.wordPrefixMatches(key, FUZZY_EXACT_SEED_CAP).associate { it[0] to it[1] }
        }
    }
    val local = m.localWordMatches(keys, nearLat, nearLon, limit)
    val ordinals: Set<Int> = exactRanks.keys + local.keys
    fun isPhrasePrefix(o: Int): Boolean = (exactRanks[o] ?: local[o]) == 0

    val fuzzy = fuzzyMatches(m, tokens, exact, ordinals, candidateCap)
    val shortlist = m.shortlist(
        ordinals.map { it to if (isPhrasePrefix(it)) 0 else 1 } + fuzzy.toList(),
        nearLat,
        nearLon,
    )
    val out = ArrayList<WordRanked>(shortlist.size)
    for ((o, rank) in shortlist) {
        out.add(wordRanked(m, o, rank, nearLat, nearLon, boost))
    }
    // Within a rank, a name that IS the query comes first when it is a place label (type 51:
    // "Oakland" the city, among hundreds of "Oakland …" businesses nearer the centre — a city
    // is searched for from anywhere) or is local (a same-named bar on another continent must
    // not beat the "Tokyo Station Cafe" next to the user); then the user's signal; then
    // distance.
    val whole = query.trim()
    fun exactTier(r: WordRanked): Boolean =
        r.record.name.equals(whole, ignoreCase = true) &&
            (r.record.type == PoiCategories.TYPE_PLACE || r.distSq <= EXACT_LOCAL_DEG_SQ)
    out.sortWith(
        compareBy<WordRanked> { it.rank }
            .thenBy { !exactTier(it) }
            .thenByDescending { it.boost }
            .thenBy { it.distSq },
    )
    return out.take(limit).map { it.record }
}

/** "Local" for the exact-name tier: ~0.5° (≈55 km) from the centre, squared. */
private const val EXACT_LOCAL_DEG_SQ = 0.25


private fun wordRanked(
    m: PoiIndex.Mapped,
    ordinal: Int,
    rank: Int,
    nearLat: Double,
    nearLon: Double,
    boost: Map<String, Double>,
): WordRanked {
    val record = m.record(ordinal)
    return WordRanked(
        record,
        boostWeight(boost, record.name),
        rank,
        PoiIndex.distanceSq(
            nearLat,
            nearLon,
            m.latE7(ordinal) * PoiIndex.E7_TO_DEGREES,
            m.lonE7(ordinal) * PoiIndex.E7_TO_DEGREES,
        ),
    )
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
internal fun PoiIndex.Mapped.fuzzyWordMatches(
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
    if (tokenLower.length >= SUBSTITUTION_MIN_LEN) {
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

/**
 * Entries the exact pass will walk for the rarest query token before it falls back to the
 * capped walks: a planet's rare words ("tartine", "golden") are well under this, its common
 * ones ("cafe", "street") well over.
 */
private const val FULL_WALK_CAP = 200_000

/** Per-token exact seed for the fuzzy intersection (its exact half), kept small. */
private const val FUZZY_EXACT_SEED_CAP = 2_000

/** Shortest token the typo passes consider (shorter ones are all typo-noise). */
internal const val MIN_TYPO_TOKEN_LEN = 3

/** Shortest token whose mid-word substitution is looked up via a two-shorter prefix. */
private const val SUBSTITUTION_MIN_LEN = 5

/** Per-variant walk cap and total fuzzy-candidate cap for one token. */
private const val FUZZY_PER_VARIANT_CAP = 50
private const val FUZZY_CANDIDATE_CAP = 500
