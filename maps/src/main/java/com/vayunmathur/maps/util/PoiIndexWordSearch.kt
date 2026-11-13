package com.vayunmathur.maps.util

/**
 * Query tokens against `poi_name_index.bin`: each token's sort-key variants, their prefix
 * ranges, and sequential scans of those ranges.
 *
 * A range is read as two parallel arrays — `ordinals[i]` (4 bytes) and `word_idx[i]`
 * (1 byte) — which sit contiguously in the section, so scanning even a large range is a
 * sequential read of a few hundred KiB and never touches a name. That is the whole point:
 * the previous search verified every entry of a range against its record's NAME, and the
 * names pool is ordered by first appearance in the OSM file, so a range of 80 k "pizza"
 * entries meant ~70 k scattered 4 KiB reads (283 MB of distinct pages on the planet) before
 * a single result could be ranked. The search engine ([searchByWordIndex]) works on
 * ordinals and word positions from these scans and decodes names only for the rows it is
 * about to return.
 */

private const val BYTE_MASK = 0xFF

/** How often (in entries) a long scan polls its cancel token. */
private const val SCAN_POLL_MASK = 0xFFFF

/** Entries `[first, last]` whose word starts with [key]; empty when none. */
internal fun PoiIndex.Mapped.prefixRange(key: ByteArray): IntRange {
    if (nameIdx == null || entryCount == 0 || key.isEmpty()) return IntRange.EMPTY
    val lo = lowerBoundWord(key)
    // Most variants (an accented spelling nobody uses) are empty: one probe settles it
    // instead of a second binary search.
    if (lo >= entryCount || !wordStartsWithAt(lo, key)) return IntRange.EMPTY
    val hi = nextPrefix(key)?.let { lowerBoundWord(it) } ?: entryCount
    return lo until hi
}

/** Whether entry [i]'s word starts with [key] (an unresolvable word does not). */
private fun PoiIndex.Mapped.wordStartsWithAt(i: Int, key: ByteArray): Boolean {
    val range = wordRange(nameOff(entryOrdinal(i)), entryWordIdx(i)) ?: return false
    return wordStartsWith(range, key)
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
 * One query token: the text the user typed, every sort-key spelling it should match
 * ([tokenKeyVariants]) and the word-index ranges of those spellings.
 */
internal class QueryToken(val text: String, val ranges: List<IntRange>) {
    /** Entries across all of this token's ranges. */
    val size: Int = ranges.sumOf { it.last - it.first + 1 }
}

/** The tokens of [query], each with its key variants resolved to ranges. */
internal fun PoiIndex.Mapped.queryTokens(query: String): List<QueryToken> =
    splitTokens(query.trim()).map { token ->
        val ranges = tokenKeyVariants(token).map { prefixRange(it) }.filterNot { it.isEmpty() }
        QueryToken(token, disjoint(ranges))
    }

/** [ranges] with duplicates and any range nested inside another dropped, so no entry counts twice. */
private fun disjoint(ranges: List<IntRange>): List<IntRange> {
    val unique = ranges.distinct()
    return unique.filter { r -> unique.none { o -> o != r && o.first <= r.first && r.last <= o.last } }
}

/** Every index sort key the token is looked up under: its ASCII-folded bytes. */
internal fun tokenKeyVariants(token: String): List<ByteArray> = listOf(poiQueryKey(token))

/**
 * Visit every entry of [token]'s ranges, in index order, as (ordinal, entry index); the
 * visitor reads the entry's word position ([entryWordIdx]) only when it wants it. Reads
 * only the entry arrays, sequentially. Returns false when [cancelled] fired mid-scan.
 */
internal inline fun PoiIndex.Mapped.scanToken(
    token: QueryToken,
    cancelled: () -> Boolean,
    visit: (ordinal: Int, entry: Int) -> Unit,
): Boolean {
    for (range in token.ranges) {
        for (i in range) {
            if (i and SCAN_POLL_MASK == 0 && cancelled()) return false
            val ordinal = entryOrdinal(i)
            if (ordinal in 0 until count) visit(ordinal, i)
        }
    }
    return true
}

/**
 * Whether some word of record [ordinal]'s name starts with one of [token]'s spellings —
 * the name-side check for a token too common to scan (a one- or two-letter tail such as
 * "golden g"). Same word split and ASCII fold as the index itself.
 */
internal fun PoiIndex.Mapped.nameHasWordPrefix(ordinal: Int, keys: List<ByteArray>): Boolean {
    val off = nameOff(ordinal)
    var w = 0
    var found = false
    while (!found) {
        val range = wordRange(off, w) ?: break
        found = keys.any { wordStartsWith(range, it) }
        w++
    }
    return found
}
