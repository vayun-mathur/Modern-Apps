package com.vayunmathur.maps.util

import java.text.Normalizer

/**
 * Pure matching logic for offline POI search: text normalisation, token
 * matching tiers, typo tolerance and coordinate queries.
 *
 * Kept free of the mapped index so it is unit-testable without side files.
 * The index code in PoiIndexWords supplies candidates; this file decides
 * what counts as a match and how matches rank against each other.
 *
 * Normalisation is deliberately stronger than the word index's ASCII fold:
 * that fold exists only to reproduce the Rust writer's sort order for
 * binary search. Matching (this file) folds case AND diacritics and drops
 * punctuation, so "Café" finds "Cafe" and "joe's" finds "Joes". Candidate
 * *lookup* in the word index still uses the writer's fold per token; the
 * scan fallback applies this normalisation to the name pool directly.
 */
object PoiSearch {

    /** Match tiers, best first. Lower wins; distance breaks ties. */
    const val TIER_EXACT = 0
    const val TIER_PREFIX = 1
    const val TIER_TOKENS = 2
    const val TIER_SUBSTRING = 3
    const val TIER_CATEGORY = 4
    const val TIER_FUZZY = 5
    const val NO_MATCH = -1

    private val MARKS = Regex("\\p{Mn}+")
    private val NON_WORD = Regex("[^a-z0-9]+")

    /** Lowercase, strip diacritics, and reduce punctuation to spaces. */
    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val withoutMarks = MARKS.replace(decomposed, "")
        return NON_WORD.replace(withoutMarks.lowercase(), " ").trim()
    }

    /** The normalised word tokens of [text], in order, empties removed. */
    fun tokens(text: String): List<String> =
        normalize(text).split(' ').filter { it.isNotEmpty() }

    /** Singular form of a token for category matching ("restaurants" -> "restaurant"). */
    fun singular(token: String): String =
        if (token.length > 3 && token.endsWith('s')) token.dropLast(1) else token

    /**
     * Tier at which the normalised [name] matches the normalised query
     * [queryNorm] with tokens [queryTokens], or [NO_MATCH].
     *
     * Every query token must match some name word (prefix, then substring,
     * then fuzzy) for the token tiers; a single unmatched token rejects the
     * name, which is what makes multi-word queries ("central park") work
     * instead of being looked up as one long word.
     */
    fun matchTier(name: String, queryNorm: String, queryTokens: List<String>): Int {
        if (queryTokens.isEmpty()) return NO_MATCH
        val nameNorm = normalize(name)
        if (nameNorm.isEmpty()) return NO_MATCH
        if (nameNorm == queryNorm) return TIER_EXACT
        if (nameNorm.startsWith(queryNorm)) return TIER_PREFIX
        val words = nameNorm.split(' ')
        var worst = TIER_TOKENS
        for (token in queryTokens) {
            val tier = tokenTier(token, words) ?: return NO_MATCH
            if (tier > worst) worst = tier
        }
        return worst
    }

    /** Best tier one query [token] achieves against [words], or null. */
    private fun tokenTier(token: String, words: List<String>): Int? {
        if (words.any { it.startsWith(token) }) return TIER_TOKENS
        if (words.any { it.contains(token) }) return TIER_SUBSTRING
        if (maxEditDistance(token) > 0 && words.any { editDistanceAtMost(token, it, maxEditDistance(token)) }) {
            return TIER_FUZZY
        }
        return null
    }

    /** Typo budget for a token: none for short tokens, growing with length. */
    fun maxEditDistance(token: String): Int = when {
        token.length < 4 -> 0
        token.length < 8 -> 1
        else -> 2
    }

    /** Levenshtein distance between [a] and [b], early-exiting past [cap]. */
    fun editDistanceAtMost(a: String, b: String, cap: Int): Boolean {
        if (cap < 0) return false
        if (kotlin.math.abs(a.length - b.length) > cap) return false
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            var rowMin = curr[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
                if (curr[j] < rowMin) rowMin = curr[j]
            }
            if (rowMin > cap) return false
            val swap = prev; prev = curr; curr = swap
        }
        return prev[b.length] <= cap
    }

    private val COORD_QUERY = Regex("""^\s*(-?\d{1,3}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)\s*$""")

    /**
     * A "lat,lon" (or "lat lon" via comma) coordinate query as
     * (lat, lon), or null when the text is not a plausible coordinate.
     * Ranges are validated, so a name containing digits never resolves here.
     */
    fun parseCoordinateQuery(query: String): Pair<Double, Double>? {
        val match = COORD_QUERY.matchEntire(query) ?: return null
        val lat = match.groupValues[1].toDoubleOrNull() ?: return null
        val lon = match.groupValues[2].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return lat to lon
    }
}
