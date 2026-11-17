package com.vayunmathur.code.domain
/**
 * A subsequence fuzzy matcher for quick-open and the command palette.
 *
 * [fuzzyScore] returns how well [query] matches [candidate] (higher is better), or null when the
 * query is not a subsequence of the candidate. Matching is case-insensitive; matches at word
 * boundaries (string start, after a separator, or a camelCase hump), consecutive runs and
 * exact-case hits score higher, while gaps before and between matches are penalized. Pure — no
 * Android dependencies — so it can be unit-tested in isolation.
 *
 * Scoring uses a sparse dynamic program over the positions where each query character occurs, so
 * it finds the best alignment (e.g. "ce" prefers the C/E camel humps of "CodeEditor") rather than
 * greedily taking the first occurrence.
 */

private val SEPARATORS = charArrayOf('/', '\\', '_', '-', '.', ' ')

private const val MATCH_SCORE = 16
private const val BOUNDARY_BONUS = 12
private const val CONSECUTIVE_BONUS = 15
private const val CASE_BONUS = 2
private const val GAP_START_PENALTY = 3
private const val GAP_EXTEND_PENALTY = 1
private const val MAX_LEADING_GAP_PENALTY = 10
private const val LENGTH_TIE_BREAKER = 8

private fun Char.matchesIgnoreCase(other: Char): Boolean =
    this == other || this.lowercaseChar() == other.lowercaseChar()

/** True when [candidate] index [i] begins a "word": string start, post-separator, or camel hump. */
private fun isBoundary(candidate: String, i: Int): Boolean {
    if (i == 0) return true
    val prev = candidate[i - 1]
    if (prev in SEPARATORS) return true
    return (prev.isLowerCase() || prev.isDigit()) && candidate[i].isUpperCase()
}

private fun gapPenalty(gap: Int): Int =
    if (gap <= 0) 0 else GAP_START_PENALTY + (gap - 1) * GAP_EXTEND_PENALTY

/**
 * Relevance of [query] against [candidate], or null when [query] is not a subsequence. An empty
 * query matches everything with a neutral score of 0.
 */
fun fuzzyScore(query: String, candidate: String): Int? {
    if (query.isEmpty()) return 0
    if (candidate.length < query.length) return null
    return FuzzyScorer(query, candidate).score()
}

private class FuzzyScorer(val query: String, val candidate: String) {
    // For query char qi, `positions` are the candidate indices where it matches and `scores` the
    // best score for an alignment ending at that index. Each layer only depends on the previous.
    var prevScores = IntArray(0)
    var prevPositions = IntArray(0)

    fun score(): Int? {
        for (qi in query.indices) {
            if (!scoreLayer(qi)) return null
        }
        val best = prevScores.maxOrNull() ?: return null
        // Mild preference for shorter candidates when scores are otherwise equal.
        return best - candidate.length / LENGTH_TIE_BREAKER
    }

    private fun scoreLayer(qi: Int): Boolean {
        val qc = query[qi]
        val positions = ArrayList<Int>()
        val scores = ArrayList<Int>()
        for (ci in candidate.indices) {
            if (!candidate[ci].matchesIgnoreCase(qc)) continue
            scorePosition(qi, qc, ci)?.let { best ->
                positions.add(ci)
                scores.add(best)
            }
        }
        if (positions.isEmpty()) return false
        prevPositions = positions.toIntArray()
        prevScores = scores.toIntArray()
        return true
    }

    private fun scorePosition(qi: Int, qc: Char, ci: Int): Int? {
        val charScore = charScore(qc, ci)
        if (qi == 0) return charScore - minOf(ci, MAX_LEADING_GAP_PENALTY)
        return bestPredecessor(ci, charScore)
    }

    private fun charScore(qc: Char, ci: Int): Int {
        var score = MATCH_SCORE
        if (isBoundary(candidate, ci)) score += BOUNDARY_BONUS
        if (candidate[ci] == qc) score += CASE_BONUS
        return score
    }

    private fun bestPredecessor(ci: Int, charScore: Int): Int? {
        var best = Int.MIN_VALUE
        for (k in prevPositions.indices) {
            val pj = prevPositions[k]
            if (pj >= ci) break // positions are ascending; no earlier predecessor remains
            val gap = ci - pj - 1
            val consecutive = if (gap == 0) CONSECUTIVE_BONUS else 0
            val cand = prevScores[k] + charScore + consecutive - gapPenalty(gap)
            if (cand > best) best = cand
        }
        return if (best == Int.MIN_VALUE) null else best
    }
}

/**
 * Filters and ranks [candidates] by [fuzzyScore] of [key] against [query], best first. Ties are
 * broken by shorter candidates, then by original order (the sort is stable). A blank query returns
 * the list unchanged (callers show it as-is).
 */
fun <T> fuzzyRank(query: String, candidates: List<T>, key: (T) -> String): List<T> {
    if (query.isBlank()) return candidates
    return candidates
        .mapNotNull { item -> fuzzyScore(query, key(item))?.let { Triple(item, it, key(item).length) } }
        .sortedWith(compareByDescending<Triple<T, Int, Int>> { it.second }.thenBy { it.third })
        .map { it.first }
}
