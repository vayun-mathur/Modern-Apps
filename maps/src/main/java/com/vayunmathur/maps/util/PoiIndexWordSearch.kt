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

/** Shortest token whose possessive ("joes" -> "joe's") is looked up too. */
private const val POSSESSIVE_MIN_LEN = 3

/** How often (in entries) a long scan polls its cancel token. */
private const val SCAN_POLL_MASK = 0xFFFF

/** Entries `[first, last]` whose word starts with [key]; empty when none. */
internal fun PoiIndex.Mapped.prefixRange(key: ByteArray, within: IntRange = 0 until entryCount): IntRange {
    if (nameIdx == null || entryCount == 0) return IntRange.EMPTY
    if (key.isEmpty() || within.isEmpty()) return IntRange.EMPTY
    val end = within.last + 1
    val lo = lowerBoundWord(key, within.first, end)
    // Most spellings (an accented form nobody uses) are empty: one probe settles it
    // instead of a second binary search.
    if (lo >= end || !wordStartsWithAt(lo, key)) return IntRange.EMPTY
    val hi = nextPrefix(key)?.let { lowerBoundWord(it, lo, end) } ?: end
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

/** The tokens of [query], each with its spellings resolved to ranges. */
internal fun PoiIndex.Mapped.queryTokens(query: String): List<QueryToken> =
    splitTokens(query.trim()).map { token ->
        val ranges = tokenSpellings(token).flatMap { spellingRanges(it) }.filterNot { it.isEmpty() }
        QueryToken(token, disjoint(ranges))
    }

/**
 * Ranges of [spelling] and of its one-accent variants ([ACCENTS]): each ASCII letter that
 * commonly carries an accent is tried with those accented forms, one position at a time
 * (the first letter in upper case: only ASCII is case-folded in the index). Most variants
 * exist nowhere and cost one binary search that shares nearly all its pages with the
 * plain spelling's own search; [prefixRange] settles an empty one with a single probe.
 */
private fun PoiIndex.Mapped.spellingRanges(spelling: String): List<IntRange> {
    val out = ArrayList<IntRange>()
    out.add(prefixRange(poiQueryKey(spelling)))
    if (spelling.length < ACCENT_MIN_LEN) return out
    spelling.forEachIndexed { i, c ->
        val forms = if (i == 0) ACCENTS_UPPER[c] else ACCENTS[c]
        forms?.forEach { f ->
            out.add(prefixRange(poiQueryKey(spelling.substring(0, i) + f + spelling.substring(i + 1))))
        }
    }
    return out
}

/** Shortest spelling whose accented forms are looked up (shorter prefixes match broadly already). */
private const val ACCENT_MIN_LEN = 3

/** [ranges] with duplicates and any range nested inside another dropped, so no entry counts twice. */
private fun disjoint(ranges: List<IntRange>): List<IntRange> {
    val unique = ranges.distinct()
    return unique.filter { r -> unique.none { o -> o != r && o.first <= r.first && r.last <= o.last } }
}

/**
 * Every spelling the token is looked up under (each also with one-accent variants, see
 * [spellingRanges]).
 *
 * The index stores raw UTF-8 with only ASCII case folded, so the spelling a user types and
 * the spelling OSM carries differ in ways the index cannot see:
 *
 *  * possessives — "mcdonalds" vs "McDonald's" (and the typographic ’). Typing the brand
 *    without the apostrophe used to find nothing nearby, because no word starts with
 *    "mcdonalds"; the 139 places that do were all far away;
 *  * apostrophes typed but not in the name — "joe's" vs "Joes";
 *  * diacritics — "cafe" vs "Café", "ecole" vs "École" (an ASCII letter is tried with each
 *    accented form, one position at a time; the first letter in upper case, since only
 *    ASCII is case-folded in the index), and "café" vs "Cafe" (the folded spelling).
 */
internal fun tokenSpellings(token: String): List<String> {
    val lower = token.lowercase()
    val spellings = LinkedHashSet<String>()
    spellings.add(lower)
    val bare = lower.filterNot { it in APOSTROPHES }
    if (bare != lower && bare.isNotEmpty()) spellings.add(bare)
    if (lower.length >= POSSESSIVE_MIN_LEN && lower.endsWith('s') && lower.none { it in APOSTROPHES }) {
        val stem = lower.dropLast(1)
        APOSTROPHES.forEach { spellings.add("$stem${it}s") }
    }
    val folded = PoiSearch.foldDiacritics(lower)
    if (folded != lower && folded.isNotEmpty()) spellings.add(folded)
    return spellings.toList()
}

/** Sort keys of [tokenSpellings], for checking a token against a decoded name. */
internal fun tokenKeyVariants(token: String): List<ByteArray> = tokenSpellings(token).map { poiQueryKey(it) }

/** Apostrophes OSM names use: ASCII and the typographic right single quote. */
private val APOSTROPHES = charArrayOf('\'', '\u2019')

/**
 * The accented lower-case forms tried per ASCII letter: the most frequent ones in planet4's
 * names (é 658 k, ó 341 k, á 327 k, í 315 k, ü 249 k, ä 226 k, ö 194 k, è 144 k, ç 120 k,
 * ñ 87 k), not every Latin-1 form — each costs a lookup on every query.
 */
private val ACCENTS: Map<Char, String> = mapOf(
    'a' to "áä", 'c' to "ç", 'e' to "éè", 'i' to "í", 'n' to "ñ", 'o' to "óö", 'u' to "ü",
)

/** Upper-case accented forms, tried at a word's first letter only (É 84 k, Á 21 k, Ö 20 k). */
private val ACCENTS_UPPER: Map<Char, String> = mapOf('a' to "Á", 'e' to "É", 'o' to "Ö")

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
