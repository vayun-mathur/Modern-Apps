package com.vayunmathur.news.domain

/** Feed merging: de-duplicate by normalised URL, newest first. Pure and total. */
object FeedMerger {
    fun merge(feeds: Iterable<List<FeedArticle>>, limit: Int = 500): List<FeedArticle> {
        val seen = HashSet<String>()
        val merged = mutableListOf<FeedArticle>()
        for (article in feeds.flatten()) {
            val key = normaliseUrl(article.url)
            if (key.isEmpty() || !seen.add(key)) continue
            merged += article
        }
        return merged.sortedByDescending { it.publishedAtMillis }.take(limit)
    }

    /** Lower-cases scheme/host, drops fragment and tracking params, trims a trailing slash. */
    fun normaliseUrl(url: String): String {
        val trimmed = url.trim()
        val noFragment = trimmed.substringBefore('#')
        val schemeEnd = noFragment.indexOf("://")
        if (schemeEnd < 0) return noFragment.trimEnd('/')
        val scheme = noFragment.substring(0, schemeEnd).lowercase()
        val rest = noFragment.substring(schemeEnd + 3)
        val host = rest.substringBefore('/').lowercase()
        val pathAndQuery = rest.substring(host.length)
        val path = pathAndQuery.substringBefore('?').ifEmpty { "/" }
        val query = pathAndQuery.substringAfter('?', "")
            .split('&').filter { part ->
                val name = part.substringBefore('=').lowercase()
                name.isNotEmpty() && !name.startsWith("utm_") && name != "ref" && name != "fbclid"
            }.sorted().joinToString("&")
        val base = "$scheme://$host${path.trimEnd('/')}"
        return if (query.isEmpty()) base else "$base?$query"
    }
}
