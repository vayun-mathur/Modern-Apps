package com.vayunmathur.news.domain

/** How a source's feed document is shaped. */
enum class FeedKind { SITEMAP, RSS }

/**
 * One built-in publisher. [badge] records a caveat when the
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
    /**
     * Category tags for feed filtering; the first entry is the primary
     * category. See [NewsCategory] for the taxonomy and assignment rule.
     * Articles inherit these from their source (no server field).
     */
    val categories: List<NewsCategory>,
    /**
     * Human-readable circulation area for [NewsCategory.LOCAL] sources,
     * most-specific first, comma-separated, country last (e.g.
     * "Los Angeles, California, United States", "Manchester, England,
     * United Kingdom", "Texas, United States" for a statewide outlet).
     * Null for non-local sources and for local sources whose area is not
     * known. Derived client-side from catalog provenance only — the
     * server feed carries no region field, exactly as categories are
     * resolved client-side by source id.
     */
    val region: String? = null,
) {
    /** Primary category (first tag); every source has at least one. */
    val primaryCategory: NewsCategory get() = categories.first()
}

/** Built-in catalog; every URL here was fetched and parsed during development. */
object SourceCatalog {
    // Curated entries below, followed by the bulk-generated catalog in
    // GeneratedSourcesA.kt ... GeneratedSourcesI.kt (700 entries per part,
    // aggregated as `generatedSources` in GeneratedSources.kt, in catalog
    // order). Regenerate the generated files with:
    // analysis/news-scale/curate_r2.py --apply (round 1 alone used
    // curate.py --apply; the round-2 script regenerates every part).
    val sources: List<NewsSource> = listOf(
        NewsSource(
            "bbc", "BBC News",
            "https://www.bbc.com/sitemaps/https-sitemap-com-news-1.xml", FeedKind.SITEMAP,
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource(
            "guardian", "The Guardian",
            "https://www.theguardian.com/sitemaps/news.xml", FeedKind.SITEMAP,
            badge = "No paywall; article pages refused our datacentre verification IP, " +
                "so full text is verified on-device only.",
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource("aljazeera", "Al Jazeera", "https://www.aljazeera.com/news-sitemap.xml", FeedKind.SITEMAP,
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource("techcrunch", "TechCrunch", "https://techcrunch.com/news-sitemap.xml", FeedKind.SITEMAP,
            categories = listOf(NewsCategory.TECHNOLOGY),
        ),
        NewsSource("propublica", "ProPublica", "https://www.propublica.org/news-sitemap.xml", FeedKind.SITEMAP,
            categories = listOf(NewsCategory.US),
        ),
        NewsSource(
            "elpais", "El País", "https://elpais.com/sitemaps/news.xml", FeedKind.SITEMAP, language = "es",
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource("npr", "NPR", "https://feeds.npr.org/1001/rss.xml", FeedKind.RSS,
            categories = listOf(NewsCategory.US),
        ),
        NewsSource("dw", "DW", "https://rss.dw.com/xml/rss-en-all", FeedKind.RSS,
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource("ars", "Ars Technica", "https://feeds.arstechnica.com/arstechnica/index", FeedKind.RSS,
            categories = listOf(NewsCategory.TECHNOLOGY, NewsCategory.SCIENCE),
        ),
        NewsSource("verge", "The Verge", "https://www.theverge.com/rss/index.xml", FeedKind.RSS,
            categories = listOf(NewsCategory.TECHNOLOGY),
        ),
        NewsSource(
            "hn", "Hacker News", "https://news.ycombinator.com/rss", FeedKind.RSS,
            badge = "Links point at third-party sites; reader quality depends on the linked site.",
            categories = listOf(NewsCategory.TECHNOLOGY),
        ),
        // Second verification round (2026-11-23, same bar as above: feed fetched
        // with 200 and parsed; sampled article HTML held the full body).
        NewsSource("tagesschau", "Tagesschau", "https://www.tagesschau.de/newsticker.rdf", FeedKind.RSS, language = "de",
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource("nos", "NOS", "https://feeds.nos.nl/nosnieuwsalgemeen", FeedKind.RSS, language = "nl",
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource("svt", "SVT Nyheter", "https://www.svt.se/nyheter/rss.xml", FeedKind.RSS, language = "sv",
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource("nrk", "NRK", "https://www.nrk.no/toppsaker.rss", FeedKind.RSS, language = "nb",
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource("yle", "Yle News", "https://yle.fi/rss/news", FeedKind.RSS,
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource("rte", "RTÉ News", "https://www.rte.ie/news/rss/news-headlines.xml", FeedKind.RSS,
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource("cbc", "CBC News", "https://www.cbc.ca/webfeed/rss/rss-topstories", FeedKind.RSS,
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource("euronews", "Euronews", "https://www.euronews.com/rss", FeedKind.RSS,
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource("abc-au", "ABC Australia", "https://www.abc.net.au/news/feed/51120/rss.xml", FeedKind.RSS,
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource("rnz", "RNZ", "https://www.rnz.co.nz/rss/national.xml", FeedKind.RSS,
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource("cna", "CNA", "https://www.channelnewsasia.com/rssfeeds/8395986", FeedKind.RSS,
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource("ansa", "ANSA", "https://www.ansa.it/sito/ansait_rss.xml", FeedKind.RSS, language = "it",
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource("mee", "Middle East Eye", "https://www.middleeasteye.net/rss.xml", FeedKind.RSS,
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource("semafor", "Semafor", "https://www.semafor.com/rss.xml", FeedKind.RSS,
            categories = listOf(NewsCategory.WORLD, NewsCategory.US),
        ),
        NewsSource("restofworld", "Rest of World", "https://restofworld.org/feed/", FeedKind.RSS,
            categories = listOf(NewsCategory.WORLD, NewsCategory.TECHNOLOGY),
        ),
        NewsSource("africanarguments", "African Arguments", "https://africanarguments.org/feed/", FeedKind.RSS,
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource("dailymaverick", "Daily Maverick", "https://www.dailymaverick.co.za/dmrss/", FeedKind.RSS,
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource(
            "buenosairesherald", "Buenos Aires Herald", "https://buenosairesherald.com/feed", FeedKind.RSS,
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource(
            "straitstimes", "The Straits Times",
            "https://www.straitstimes.com/news/singapore/rss.xml", FeedKind.RSS,
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource(
            "thehindu", "The Hindu",
            "https://www.thehindu.com/news/national/feeder/default.rss", FeedKind.RSS,
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource(
            "hindustantimes", "Hindustan Times",
            "https://www.hindustantimes.com/feeds/rss/india-news/rssfeed.xml", FeedKind.RSS,
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource(
            "japantimes", "The Japan Times", "https://www.japantimes.co.jp/feed/", FeedKind.RSS,
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource(
            "stat", "STAT", "https://www.statnews.com/feed/", FeedKind.RSS,
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
            categories = listOf(NewsCategory.SCIENCE),
        ),
        NewsSource(
            "wired", "WIRED", "https://www.wired.com/feed/rss", FeedKind.RSS,
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
            categories = listOf(NewsCategory.TECHNOLOGY, NewsCategory.SCIENCE),
        ),
        NewsSource(
            "mittech", "MIT Technology Review", "https://www.technologyreview.com/feed/", FeedKind.RSS,
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
            categories = listOf(NewsCategory.TECHNOLOGY, NewsCategory.SCIENCE),
        ),
        NewsSource(
            "latimes", "Los Angeles Times",
            "https://www.latimes.com/california/rss2.0.xml", FeedKind.RSS,
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
            categories = listOf(NewsCategory.LOCAL), region = "Los Angeles, California, United States",
        ),
        // Third verification round (2026-11-23, same bar: feed fetched 200 and
        // parsed; 2-3 sampled article HTML pages held the full body). Emphasis:
        // local/regional outlets (US metros + public/nonprofit locals, UK Reach
        // and Newsquest-regionals, Postmedia and Nine metros) plus non-English
        // sources. Each LOCAL entry carries its circulation area in
        // NewsSource.region (city/state/country, curated from the outlets'
        // known home markets in the verification round).
        // US local / regional.
        NewsSource(
            "seattle-times", "Seattle Times", "https://www.seattletimes.com/feed/", FeedKind.RSS,
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
            categories = listOf(NewsCategory.LOCAL), region = "Seattle, Washington, United States",
        ),
        NewsSource(
            "dallas-morning", "Dallas Morning News",
            "https://www.dallasnews.com/sitemap/news/local.xml", FeedKind.SITEMAP,
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
            categories = listOf(NewsCategory.LOCAL), region = "Dallas, Texas, United States",
        ),
        NewsSource(
            "houston-chronicle", "Houston Chronicle",
            "https://www.houstonchronicle.com/news-sitemap.xml", FeedKind.SITEMAP,
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
            categories = listOf(NewsCategory.LOCAL), region = "Houston, Texas, United States",
        ),
        NewsSource(
            "sf-chronicle", "San Francisco Chronicle",
            "https://www.sfchronicle.com/news-sitemap.xml", FeedKind.SITEMAP,
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
            categories = listOf(NewsCategory.LOCAL), region = "San Francisco, California, United States",
        ),
        NewsSource(
            "inquirer", "Philadelphia Inquirer",
            "https://www.inquirer.com/arc/outboundfeeds/rss/?outputType=xml", FeedKind.RSS,
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
            categories = listOf(NewsCategory.LOCAL), region = "Philadelphia, Pennsylvania, United States",
        ),
        NewsSource("gothamist", "Gothamist", "https://gothamist.com/feed", FeedKind.RSS,
            categories = listOf(NewsCategory.LOCAL), region = "New York City, New York, United States",
        ),
        NewsSource("laist", "LAist", "https://laist.com/index.atom", FeedKind.RSS,
            categories = listOf(NewsCategory.LOCAL), region = "Los Angeles, California, United States",
        ),
        NewsSource("block-club", "Block Club Chicago", "https://blockclubchicago.org/feed/", FeedKind.RSS,
            categories = listOf(NewsCategory.LOCAL), region = "Chicago, Illinois, United States",
        ),
        NewsSource("wbez", "WBEZ Chicago", "https://www.wbez.org/rss/index.xml", FeedKind.RSS,
            categories = listOf(NewsCategory.LOCAL), region = "Chicago, Illinois, United States",
        ),
        NewsSource("minnpost", "MinnPost", "https://www.minnpost.com/feed/", FeedKind.RSS,
            categories = listOf(NewsCategory.LOCAL), region = "Minnesota, United States",
        ),
        NewsSource("mprnews", "MPR News", "https://www.mprnews.org/feed/homepage", FeedKind.RSS,
            categories = listOf(NewsCategory.LOCAL), region = "Minnesota, United States",
        ),
        NewsSource("texas-tribune", "Texas Tribune", "https://www.texastribune.org/feed/", FeedKind.RSS,
            categories = listOf(NewsCategory.LOCAL), region = "Texas, United States",
        ),
        NewsSource("calmatters", "CalMatters", "https://calmatters.org/feed/", FeedKind.RSS,
            categories = listOf(NewsCategory.LOCAL), region = "California, United States",
        ),
        NewsSource("austin-monitor", "Austin Monitor", "https://www.austinmonitor.com/feed/", FeedKind.RSS,
            categories = listOf(NewsCategory.LOCAL), region = "Austin, Texas, United States",
        ),
        NewsSource(
            "houston-public", "Houston Public Media", "https://www.houstonpublicmedia.org/feed/", FeedKind.RSS,
            categories = listOf(NewsCategory.LOCAL), region = "Houston, Texas, United States",
        ),
        NewsSource("wbur", "WBUR Boston", "https://www.wbur.org/feed", FeedKind.RSS,
            categories = listOf(NewsCategory.LOCAL), region = "Boston, Massachusetts, United States",
        ),
        NewsSource("kqed", "KQED News", "https://ww2.kqed.org/news/feed/", FeedKind.RSS,
            categories = listOf(NewsCategory.LOCAL), region = "San Francisco, California, United States",
        ),
        NewsSource(
            "sltrib", "Salt Lake Tribune",
            "https://www.sltrib.com/arc/outboundfeeds/rss/?outputType=xml", FeedKind.RSS,
            categories = listOf(NewsCategory.LOCAL), region = "Salt Lake City, Utah, United States",
        ),
        NewsSource(
            "adn", "Anchorage Daily News",
            "https://www.adn.com/arc/outboundfeeds/rss/?outputType=xml", FeedKind.RSS,
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
            categories = listOf(NewsCategory.LOCAL), region = "Anchorage, Alaska, United States",
        ),
        NewsSource("civil-beat", "Honolulu Civil Beat", "https://www.civilbeat.org/feed/", FeedKind.RSS,
            categories = listOf(NewsCategory.LOCAL), region = "Honolulu, Hawaii, United States",
        ),
        NewsSource("vtdigger", "VTDigger", "https://vtdigger.org/feed/", FeedKind.RSS,
            categories = listOf(NewsCategory.LOCAL), region = "Vermont, United States",
        ),
        NewsSource("the-city", "THE CITY (New York)", "https://www.thecity.nyc/feed/", FeedKind.RSS,
            categories = listOf(NewsCategory.LOCAL), region = "New York City, New York, United States",
        ),
        NewsSource(
            "bangor", "Bangor Daily News", "https://www.bangordailynews.com/feed/", FeedKind.RSS,
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
            categories = listOf(NewsCategory.LOCAL), region = "Bangor, Maine, United States",
        ),
        // International local / regional (English).
        NewsSource(
            "manchester-evening", "Manchester Evening News",
            "https://www.manchestereveningnews.co.uk/rss.xml", FeedKind.RSS,
            categories = listOf(NewsCategory.LOCAL), region = "Manchester, England, United Kingdom",
        ),
        NewsSource(
            "liverpool-echo", "Liverpool Echo", "https://www.liverpoolecho.co.uk/rss.xml", FeedKind.RSS,
            categories = listOf(NewsCategory.LOCAL), region = "Liverpool, England, United Kingdom",
        ),
        NewsSource("walesonline", "WalesOnline", "https://www.walesonline.co.uk/rss.xml", FeedKind.RSS,
            categories = listOf(NewsCategory.LOCAL), region = "Wales, United Kingdom",
        ),
        NewsSource(
            "chroniclelive", "ChronicleLive (Newcastle)", "https://www.chroniclelive.co.uk/rss.xml", FeedKind.RSS,
            categories = listOf(NewsCategory.LOCAL), region = "Newcastle, England, United Kingdom",
        ),
        NewsSource("standard", "Evening Standard (London)", "https://www.standard.co.uk/rss", FeedKind.RSS,
            categories = listOf(NewsCategory.LOCAL), region = "London, England, United Kingdom",
        ),
        NewsSource(
            "belfast-telegraph", "Belfast Telegraph", "https://www.belfasttelegraph.co.uk/rss", FeedKind.RSS,
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
            categories = listOf(NewsCategory.LOCAL), region = "Belfast, Northern Ireland, United Kingdom",
        ),
        NewsSource(
            "thejournal", "The Journal (Ireland)", "https://www.thejournal.ie/feed/", FeedKind.RSS,
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource(
            "vancouver-sun", "Vancouver Sun", "https://vancouversun.com/feed/", FeedKind.RSS,
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
            categories = listOf(NewsCategory.LOCAL), region = "Vancouver, British Columbia, Canada",
        ),
        NewsSource(
            "ottawa-citizen", "Ottawa Citizen", "https://ottawacitizen.com/feed/", FeedKind.RSS,
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
            categories = listOf(NewsCategory.LOCAL), region = "Ottawa, Ontario, Canada",
        ),
        NewsSource(
            "smh", "Sydney Morning Herald", "https://www.smh.com.au/rss/feed.xml", FeedKind.RSS,
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
            categories = listOf(NewsCategory.LOCAL), region = "Sydney, New South Wales, Australia",
        ),
        NewsSource(
            "the-age", "The Age (Melbourne)", "https://www.theage.com.au/rss/feed.xml", FeedKind.RSS,
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
            categories = listOf(NewsCategory.LOCAL), region = "Melbourne, Victoria, Australia",
        ),
        // Non-English (language = BCP-47 feed language; articles inherit it).
        NewsSource("eldiario", "elDiario.es", "https://www.eldiario.es/rss", FeedKind.RSS, language = "es",
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource("20minutos-es", "20minutos", "https://www.20minutos.es/rss/", FeedKind.RSS, language = "es",
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource(
            "heise", "Heise online", "https://www.heise.de/rss/heise-atom.xml", FeedKind.RSS, language = "de",
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
            categories = listOf(NewsCategory.TECHNOLOGY),
        ),
        NewsSource(
            "spiegel", "DER SPIEGEL",
            "https://www.spiegel.de/schlagzeilen/rss/0,5291,,00.xml", FeedKind.RSS, language = "de",
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource(
            "mopo", "Hamburger Morgenpost", "https://www.mopo.de/feed/", FeedKind.RSS, language = "de",
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
            categories = listOf(NewsCategory.LOCAL), region = "Hamburg, Germany",
        ),
        NewsSource(
            "20minutes-fr", "20 Minutes", "https://www.20minutes.fr/feeds/rss-une.xml", FeedKind.RSS,
            language = "fr",
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource(
            "lefigaro", "Le Figaro", "https://www.lefigaro.fr/rss/figaro_actualites.xml", FeedKind.RSS,
            language = "fr",
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource(
            "ladepeche", "La Dépêche (Toulouse)", "https://www.ladepeche.fr/rss.xml", FeedKind.RSS,
            language = "fr",
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
            categories = listOf(NewsCategory.LOCAL), region = "Toulouse, France",
        ),
        NewsSource(
            "observador", "Observador", "https://observador.pt/feed/", FeedKind.RSS, language = "pt",
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource("g1", "G1 (Globo)", "https://g1.globo.com/rss/g1/", FeedKind.RSS, language = "pt",
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource(
            "folha", "Folha de S.Paulo",
            "https://feeds.folha.uol.com.br/mundo/rss091.xml", FeedKind.RSS, language = "pt",
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource(
            "corriere", "Corriere della Sera", "https://www.corriere.it/rss/homepage.xml", FeedKind.RSS,
            language = "it",
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource("open-it", "Open (Italy)", "https://www.open.online/feed/", FeedKind.RSS, language = "it",
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource(
            "vg", "VG", "https://www.vg.no/rss/feed/", FeedKind.RSS, language = "nb",
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource(
            "aftenposten", "Aftenposten (Oslo)", "https://www.aftenposten.no/rss", FeedKind.RSS, language = "nb",
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource(
            "aftonbladet", "Aftonbladet",
            "https://rss.aftonbladet.se/rss2/small/pages/sections/senastenytt/", FeedKind.RSS, language = "sv",
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
            categories = listOf(NewsCategory.WORLD),
        ),
        NewsSource(
            "iltalehti", "Iltalehti", "https://www.iltalehti.fi/rss/uutiset.xml", FeedKind.RSS, language = "fi",
            badge = "Metered: full article text was present in the raw HTML during " +
                "verification; some articles may still truncate.",
            categories = listOf(NewsCategory.WORLD),
        ),
    ) + generatedSources

    fun byId(id: String): NewsSource? = sources.firstOrNull { it.id == id }

    /** Category tags for [sourceId]; empty for an id not in the catalog. */
    fun categoriesOf(sourceId: String): List<NewsCategory> = byId(sourceId)?.categories.orEmpty()

    /**
     * Whether an article from [sourceId] belongs to [category]. A null
     * [category] is the feed's "All" filter and matches every source,
     * including unresolved ids; a specific category matches only when it
     * is among the source's catalog tags.
     */
    fun matchesCategory(sourceId: String, category: NewsCategory?): Boolean =
        category == null || category in categoriesOf(sourceId)

    /** Region for [sourceId]; null for non-local or unknown sources. */
    fun regionOf(sourceId: String): String? = byId(sourceId)?.region

    /**
     * Distinct regions of LOCAL-tagged sources that carry one, sorted
     * for the Local news location picker (country-grouped in the UI via
     * [LocalNews.countryOf]).
     */
    val localRegions: List<String> = sources
        .filter { NewsCategory.LOCAL in it.categories }
        .mapNotNull { it.region }
        .distinct()
        .sorted()

    /** Whether [sourceId]'s region matches the user's [LocalNews] location. */
    fun matchesLocalLocation(sourceId: String, location: String?): Boolean =
        LocalNews.matches(regionOf(sourceId), location)

    /** Categories present among [sources], in taxonomy order. */
    fun categoriesAmong(sources: List<NewsSource>): List<NewsCategory> =
        NewsCategory.entries.filter { category -> sources.any { category in it.categories } }
}

/** [articles] kept to [category] via their sources' catalog tags; null keeps all. */
fun List<FeedArticle>.inCategory(category: NewsCategory?): List<FeedArticle> =
    filter { SourceCatalog.matchesCategory(it.sourceId, category) }
