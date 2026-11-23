package com.vayunmathur.news.domain

/** Result of parsing one feed document: articles, or child sitemaps to follow. */
data class ParsedFeed(
    val articles: List<FeedArticle> = emptyList(),
    val childSitemaps: List<String> = emptyList(),
)

/**
 * Pure parser for Google News sitemaps (urlset + news extension), plain
 * urlsets, sitemap indexes, RSS 2.0 and Atom.
 *
 * Linear scan only: entries are located with [String.indexOf] and each
 * entry block is scanned in isolation. There is deliberately no
 * whole-document regex `findAll` and no regex is compiled per entry —
 * a previous regex-over-the-whole-document implementation burned 22s
 * of main-thread CPU on a ~996-URL BBC sitemap (ANR 2026-11-23). This
 * parser is O(document length) with small constants and never throws;
 * malformed entries are skipped. Callers must still run it off the
 * main thread (see NewsRepository, which parses on Dispatchers.Default).
 */
object SitemapParser {

    fun parse(xml: String, sourceId: String): ParsedFeed {
        val doc = xml.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        return when {
            doc.contains("<sitemapindex") -> ParsedFeed(childSitemaps = extractSitemapChildren(doc))
            doc.contains("<urlset") -> ParsedFeed(articles = extractUrlset(doc, sourceId))
            doc.contains("<rss") || doc.contains("<channel") -> ParsedFeed(articles = extractRss(doc, sourceId))
            doc.contains("<feed") -> ParsedFeed(articles = extractAtom(doc, sourceId))
            else -> ParsedFeed()
        }
    }

    private fun extractSitemapChildren(doc: String): List<String> {
        val out = mutableListOf<String>()
        forEachBlock(doc, "<sitemap>", "</sitemap>") { block ->
            val loc = tagText(block, "loc") ?: return@forEachBlock
            if (loc.startsWith("http")) out += loc
        }
        return out
    }

    private fun extractUrlset(doc: String, sourceId: String): List<FeedArticle> {
        val out = mutableListOf<FeedArticle>()
        forEachBlock(doc, "<url>", "</url>") { block ->
            val loc = tagText(block, "loc") ?: return@forEachBlock
            if (!loc.startsWith("http")) return@forEachBlock
            val title = tagText(block, "news:title") ?: tagText(block, "title") ?: loc
            val date = tagText(block, "news:publication_date") ?: tagText(block, "lastmod")
            val keywords = tagText(block, "news:keywords")
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            val image = tagText(block, "image:loc")
            out += FeedArticle(
                url = loc, sourceId = sourceId, title = title,
                publishedAtMillis = DateParsing.parseMillis(date) ?: 0L,
                imageUrl = image, keywords = keywords,
                language = languageOf(block, sourceId),
            )
        }
        return out
    }

    private fun extractRss(doc: String, sourceId: String): List<FeedArticle> {
        // Channel-level <language> (before the first item) is the feed default.
        val head = doc.substringBefore("<item")
        val feedLanguage = tagText(head, "language") ?: tagText(head, "dc:language")
        val out = mutableListOf<FeedArticle>()
        forEachBlock(doc, "<item>", "</item>") { block ->
            val link = tagText(block, "link") ?: return@forEachBlock
            val title = tagText(block, "title") ?: link
            val date = tagText(block, "pubDate") ?: tagText(block, "dc:date")
            val image = attributeAfter(block, "media:content", "url")
                ?: attributeAfter(block, "enclosure", "url")
            val language = tagText(block, "dc:language") ?: tagText(block, "language")
                ?: feedLanguage ?: SourceCatalog.byId(sourceId)?.language
            out += FeedArticle(link, sourceId, title, DateParsing.parseMillis(date) ?: 0L, image, language = language)
        }
        return out
    }

    private fun extractAtom(doc: String, sourceId: String): List<FeedArticle> {
        val out = mutableListOf<FeedArticle>()
        forEachBlock(doc, "<entry>", "</entry>") { block ->
            val link = attributeAfter(block, "link", "href") ?: return@forEachBlock
            val title = tagText(block, "title") ?: link
            val date = tagText(block, "published") ?: tagText(block, "updated")
            out += FeedArticle(
                decodeEntities(link), sourceId, title, DateParsing.parseMillis(date) ?: 0L,
                language = languageOf(block, sourceId),
            )
        }
        return out
    }

    /**
     * Language stated by the feed for one entry (`news:language` /
     * `dc:language` / `language`), else the source catalog default.
     * Hreflang alternates are not used: they name other editions.
     */
    private fun languageOf(block: String, sourceId: String): String? =
        tagText(block, "news:language") ?: tagText(block, "dc:language") ?: tagText(block, "language")
            ?: SourceCatalog.byId(sourceId)?.language

    /**
     * Visits each `<open>…</close>` block in order, in one forward pass.
     * Unterminated trailing blocks are ignored.
     */
    private inline fun forEachBlock(doc: String, open: String, close: String, visit: (String) -> Unit) {
        var from = 0
        while (true) {
            val start = doc.indexOf(open, from)
            if (start < 0) return
            val contentStart = start + open.length
            val end = doc.indexOf(close, contentStart)
            if (end < 0) return
            visit(doc.substring(contentStart, end))
            from = end + close.length
        }
    }

    /** Text of the first `<tag>…</tag>` inside [block], or null. Linear in the block. */
    private fun tagText(block: String, tag: String): String? {
        val open = "<$tag>"
        val start = block.indexOf(open)
        if (start < 0) return null
        val contentStart = start + open.length
        val end = block.indexOf("</$tag>", contentStart)
        if (end < 0) return null
        val raw = block.substring(contentStart, end)
        val cdata = raw.trim().removePrefix("<![CDATA[").removeSuffix("]]>")
        return decodeEntities(cdata.trim()).takeIf { it.isNotEmpty() }
    }

    /** Value of attribute [attr] on the first `<tag …>` inside [block], or null. */
    private fun attributeAfter(block: String, tag: String, attr: String): String? {
        val tagStart = block.indexOf("<$tag")
        if (tagStart < 0) return null
        val attrStart = block.indexOf("$attr=\"", tagStart)
        if (attrStart < 0) return null
        val valueStart = attrStart + attr.length + 2
        val end = block.indexOf('"', valueStart)
        if (end < 0) return null
        return decodeEntities(block.substring(valueStart, end)).takeIf { it.isNotEmpty() }
    }

    internal fun decodeEntities(text: String): String {
        var out = text
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")
        out = out.replace(NUMERIC_HEX_ENTITY) {
            it.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: it.value
        }
        return out.replace(NUMERIC_DEC_ENTITY) {
            it.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: it.value
        }
    }

    // Compiled once, applied only to individual field strings (never the document).
    private val NUMERIC_HEX_ENTITY = Regex("""&#x([0-9a-fA-F]+);""")
    private val NUMERIC_DEC_ENTITY = Regex("""&#(\d+);""")
}
