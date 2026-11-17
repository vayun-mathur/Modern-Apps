package com.vayunmathur.appstore.domain
import com.vayunmathur.appstore.platform.rank

/**
 * Turns a query and a set of candidate listings into an order.
 *
 * The old ranking treated the query as one literal string, which is what issue #595 is
 * about: "Google Maps" only ever reached the `contains` tier, so everything Play returned
 * alongside the real answer — Facebook, Netflix — scored the same and sorted in
 * alphabetically, and "chase mobile" matched nothing at all even though it is the app's
 * exact name, because no single field holds those two words together.
 *
 * So the query is split into words. A result has to account for every one of them before
 * it is shown at all, and where it lands is decided by how the words line up with the
 * name rather than by whether the whole phrase happens to appear verbatim.
 */
object SearchRanking {

    /**
     * The fields ranking looks at, so this stays independent of the store's app model and
     * can be exercised without a Play session.
     */
    data class Candidate(
        val name: String,
        val packageName: String,
        val author: String = "",
        val summary: String = "",
        /** Install count where the source publishes one; separates equally good matches. */
        val popularity: Long = 0L,
    )

    /** Query words, lowercased. Capped so a pasted sentence can't fan out into a scan. */
    fun tokenize(query: String): List<String> = words(query).take(MAX_QUERY_TOKENS)

    /**
     * How well [candidate] answers [query], lower being better, or null when it does not
     * answer it at all.
     *
     * Every query word has to appear somewhere — the author counts, so a search for the
     * publisher plus the product ("Google Maps") still finds a listing simply named "Maps"
     * — but only the name decides the tier, because that is the thing the user is reading
     * down the list.
     */
    fun score(candidate: Candidate, query: String): Int? {
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return null
        val fields = fields(candidate)
        if (tokens.any { token -> fields.none { it.contains(token) } }) return null

        val q = query.trim().lowercase()
        val name = candidate.name.lowercase()
        val nameWords = words(name)
        return when {
            name == q || candidate.packageName.lowercase() == q -> EXACT
            name.startsWith(q) -> NAME_PREFIX
            tokens.all { token -> nameWords.any { it.startsWith(token) } } -> WORD_PREFIX
            tokens.all { token -> name.contains(token) } -> NAME_CONTAINS
            else -> ELSEWHERE
        }
    }

    /**
     * Rank [items], dropping the ones that do not answer [query].
     *
     * When nothing answers all of it, results answering part of it are shown instead:
     * a query a person would call reasonable — "chase mobile" against a store that only
     * lists "Chase Bank" — should not come back as an empty screen.
     */
    fun <T> rank(items: List<T>, query: String, candidate: (T) -> Candidate): List<T> {
        if (query.isBlank()) return items
        val scored = items.mapNotNull { item -> score(candidate(item), query)?.let { item to it } }
            .ifEmpty {
                items.mapNotNull { item -> partialScore(candidate(item), query)?.let { item to it } }
            }
        return scored
            .sortedWith(
                compareBy<Pair<T, Int>> { it.second }
                    .thenByDescending { candidate(it.first).popularity }
                    .thenBy { candidate(it.first).name.lowercase() }
            )
            .map { it.first }
    }

    /** Rank for a result that matches some of the query but not all of it. */
    private fun partialScore(candidate: Candidate, query: String): Int? {
        val tokens = tokenize(query)
        val fields = fields(candidate)
        val missing = tokens.count { token -> fields.none { it.contains(token) } }
        return if (missing == tokens.size) null else PARTIAL + missing
    }

    private fun fields(candidate: Candidate): List<String> = listOf(
        candidate.name.lowercase(),
        candidate.packageName.lowercase(),
        candidate.author.lowercase(),
        candidate.summary.lowercase(),
    )

    private fun words(text: String): List<String> =
        text.lowercase().split(SEPARATORS).filter { it.isNotBlank() }

    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

    private const val MAX_QUERY_TOKENS = 4

    private const val EXACT = 0
    private const val NAME_PREFIX = 1
    private const val WORD_PREFIX = 2
    private const val NAME_CONTAINS = 3
    private const val ELSEWHERE = 4

    /** Floor for the relaxed pass, so a partial match can never outrank a whole one. */
    private const val PARTIAL = 10
}
