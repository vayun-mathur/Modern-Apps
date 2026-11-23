package com.vayunmathur.news.domain

/**
 * The user's Local news location: a manually picked preference (never
 * device location / GPS), stored as the same comma-separated region
 * string the catalog carries in [NewsSource.region] — most-specific
 * first, country last (e.g. "Los Angeles, California, United States").
 * The user may also type a free place name ("California", "Manchester",
 * "Toulouse"); matching is component-wise, so a state/country-constituent
 * typed alone matches its cities, and a city pick also matches statewide
 * outlets for that state.
 *
 * Server contract unchanged: `news.json` carries no region (or category)
 * field; region is resolved client-side from [SourceCatalog] by source
 * id, exactly like categories (see [SourceCatalog.regionOf]).
 */
object LocalNews {

    /** Country (last comma component) of [region], or null when blank. */
    fun countryOf(region: String?): String? =
        region?.split(",")?.lastOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Whether a source from [sourceRegion] is "from" [location].
     * Null/blank on either side never matches (an unset location boosts
     * nothing, and a region-less source is never boosted).
     *
     * Rule: drop the trailing country component from both sides when a
     * comma hierarchy is present (country alone is not local enough to
     * boost), then a match is any remaining component of one side that
     * equals, or token-wise contains / is contained by, a component of
     * the other side. This makes "California" match both
     * "California, United States" and "Los Angeles, California, United
     * States", and "Los Angeles, California, United States" match the
     * statewide "California, United States" (its California component).
     */
    fun matches(sourceRegion: String?, location: String?): Boolean {
        val chosen = specificComponents(location)
        val region = specificComponents(sourceRegion)
        if (chosen.isEmpty() || region.isEmpty()) return false
        return chosen.any { c ->
            region.any { r -> c == r || c.containsAll(r) || r.containsAll(c) }
        }
    }

    /**
     * Stable partition: items whose region matches [location] first, in
     * their original (already ranked) order, then the rest unchanged.
     * A null/blank [location] returns [items] untouched, so the unset
     * behaviour is exactly the pre-feature feed order.
     */
    fun <T> matchingFirst(
        items: List<T>,
        location: String?,
        regionOf: (T) -> String?,
    ): List<T> {
        if (location.isNullOrBlank() || items.isEmpty()) return items
        val (matching, rest) = items.partition { matches(regionOf(it), location) }
        return matching + rest
    }

    /** Normalised token-set components, country component dropped when hierarchical. */
    private fun specificComponents(value: String?): List<Set<String>> {
        if (value.isNullOrBlank()) return emptyList()
        val parts = value.split(",")
            .map { part -> normalise(part) }
            .filter { it.isNotEmpty() }
        val specific = if (parts.size > 1) parts.dropLast(1) else parts
        return specific.map { it.split(" ").toSet() }
    }

    private fun normalise(text: String): String =
        text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
}

/** [articles] reordered for the Local feed: chosen-location sources first. */
fun List<FeedArticle>.orderedForLocal(location: String?): List<FeedArticle> =
    LocalNews.matchingFirst(this, location) { SourceCatalog.regionOf(it.sourceId) }
