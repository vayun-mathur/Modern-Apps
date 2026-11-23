package com.vayunmathur.news.domain

/** How a source's feed document is shaped. */
enum class FeedKind { SITEMAP, RSS }

/**
 * One built-in publisher. [badge] is shown in the Sources screen when the
 * feed works but article fetching carries a caveat verified at catalog
 * time (see news/AGENTS.md for the per-source verification evidence).
 * Sources whose article bodies are absent from the raw HTML (JS-only
 * shells, bot walls, hard paywalls) are not in this catalog at all.
 */
data class NewsSource(
    val id: String,
    val name: String,
    val feedUrl: String,
    val kind: FeedKind,
    val badge: String? = null,
)

/** Built-in catalog; every URL here was fetched and parsed during development. */
object SourceCatalog {
    val sources: List<NewsSource> = listOf(
        NewsSource(
            "bbc", "BBC News",
            "https://www.bbc.com/sitemaps/https-sitemap-com-news-1.xml", FeedKind.SITEMAP,
        ),
        NewsSource(
            "guardian", "The Guardian",
            "https://www.theguardian.com/sitemaps/news.xml", FeedKind.SITEMAP,
            badge = "No paywall; article pages refused our datacentre verification IP, " +
                "so full text is verified on-device only.",
        ),
        NewsSource("aljazeera", "Al Jazeera", "https://www.aljazeera.com/news-sitemap.xml", FeedKind.SITEMAP),
        NewsSource("techcrunch", "TechCrunch", "https://techcrunch.com/news-sitemap.xml", FeedKind.SITEMAP),
        NewsSource("propublica", "ProPublica", "https://www.propublica.org/news-sitemap.xml", FeedKind.SITEMAP),
        NewsSource(
            "elpais", "El País", "https://elpais.com/sitemaps/news.xml", FeedKind.SITEMAP,
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
        ),
        NewsSource("npr", "NPR", "https://feeds.npr.org/1001/rss.xml", FeedKind.RSS),
        NewsSource("dw", "DW", "https://rss.dw.com/xml/rss-en-all", FeedKind.RSS),
        NewsSource("ars", "Ars Technica", "https://feeds.arstechnica.com/arstechnica/index", FeedKind.RSS),
        NewsSource("verge", "The Verge", "https://www.theverge.com/rss/index.xml", FeedKind.RSS),
        NewsSource(
            "hn", "Hacker News", "https://news.ycombinator.com/rss", FeedKind.RSS,
            badge = "Links point at third-party sites; reader quality depends on the linked site.",
        ),
    )

    fun byId(id: String): NewsSource? = sources.firstOrNull { it.id == id }
}
