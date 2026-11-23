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
    /** BCP-47 language of this source's feed (e.g. "en", "de"); default for its articles. */
    val language: String = "en",
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
            "elpais", "El País", "https://elpais.com/sitemaps/news.xml", FeedKind.SITEMAP, language = "es",
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
        // Second verification round (2026-11-23, same bar as above: feed fetched
        // with 200 and parsed; sampled article HTML held the full body).
        NewsSource("tagesschau", "Tagesschau", "https://www.tagesschau.de/newsticker.rdf", FeedKind.RSS, language = "de"),
        NewsSource("nos", "NOS", "https://feeds.nos.nl/nosnieuwsalgemeen", FeedKind.RSS, language = "nl"),
        NewsSource("svt", "SVT Nyheter", "https://www.svt.se/nyheter/rss.xml", FeedKind.RSS, language = "sv"),
        NewsSource("nrk", "NRK", "https://www.nrk.no/toppsaker.rss", FeedKind.RSS, language = "nb"),
        NewsSource("yle", "Yle News", "https://yle.fi/rss/news", FeedKind.RSS),
        NewsSource("rte", "RTÉ News", "https://www.rte.ie/news/rss/news-headlines.xml", FeedKind.RSS),
        NewsSource("cbc", "CBC News", "https://www.cbc.ca/webfeed/rss/rss-topstories", FeedKind.RSS),
        NewsSource("euronews", "Euronews", "https://www.euronews.com/rss", FeedKind.RSS),
        NewsSource("abc-au", "ABC Australia", "https://www.abc.net.au/news/feed/51120/rss.xml", FeedKind.RSS),
        NewsSource("rnz", "RNZ", "https://www.rnz.co.nz/rss/national.xml", FeedKind.RSS),
        NewsSource("cna", "CNA", "https://www.channelnewsasia.com/rssfeeds/8395986", FeedKind.RSS),
        NewsSource("ansa", "ANSA", "https://www.ansa.it/sito/ansait_rss.xml", FeedKind.RSS, language = "it"),
        NewsSource("mee", "Middle East Eye", "https://www.middleeasteye.net/rss.xml", FeedKind.RSS),
        NewsSource("semafor", "Semafor", "https://www.semafor.com/rss.xml", FeedKind.RSS),
        NewsSource("restofworld", "Rest of World", "https://restofworld.org/feed/", FeedKind.RSS),
        NewsSource("africanarguments", "African Arguments", "https://africanarguments.org/feed/", FeedKind.RSS),
        NewsSource("dailymaverick", "Daily Maverick", "https://www.dailymaverick.co.za/dmrss/", FeedKind.RSS),
        NewsSource(
            "buenosairesherald", "Buenos Aires Herald", "https://buenosairesherald.com/feed", FeedKind.RSS,
        ),
        NewsSource(
            "straitstimes", "The Straits Times",
            "https://www.straitstimes.com/news/singapore/rss.xml", FeedKind.RSS,
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
        ),
        NewsSource(
            "thehindu", "The Hindu",
            "https://www.thehindu.com/news/national/feeder/default.rss", FeedKind.RSS,
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
        ),
        NewsSource(
            "hindustantimes", "Hindustan Times",
            "https://www.hindustantimes.com/feeds/rss/india-news/rssfeed.xml", FeedKind.RSS,
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
        ),
        NewsSource(
            "japantimes", "The Japan Times", "https://www.japantimes.co.jp/feed/", FeedKind.RSS,
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
        ),
        NewsSource(
            "stat", "STAT", "https://www.statnews.com/feed/", FeedKind.RSS,
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
        ),
        NewsSource(
            "wired", "WIRED", "https://www.wired.com/feed/rss", FeedKind.RSS,
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
        ),
        NewsSource(
            "mittech", "MIT Technology Review", "https://www.technologyreview.com/feed/", FeedKind.RSS,
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
        ),
        NewsSource(
            "latimes", "Los Angeles Times",
            "https://www.latimes.com/california/rss2.0.xml", FeedKind.RSS,
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
        ),
    )

    fun byId(id: String): NewsSource? = sources.firstOrNull { it.id == id }
}
