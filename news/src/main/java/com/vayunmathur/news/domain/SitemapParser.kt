package com.vayunmathur.news.domain

/** Result of parsing one feed document: articles, or child sitemaps to follow. */
data class ParsedFeed(
    val articles: List<FeedArticle> = emptyList(),
    val childSitemaps: List<String> = emptyList(),
)

/**
 * Pure parser for Google News sitemaps (urlset + news extension), plain
 * urlsets, sitemap indexes, RSS 2.0 and Atom. Deliberately string-based
 * (no XML library) so it runs identically on device and in plain JVM unit
 * tests; it tolerates malformed entries by skipping them, never throwing.
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

    private fun extractSitemapChildren(doc: String): List<String> =
        Regex("""<sitemap>\s*<loc>([^<]+)</loc>""").findAll(doc)
            .map { decodeEntities(it.groupValues[1].trim()) }.filter { it.startsWith("http") }.toList()

    private fun extractUrlset(doc: String, sourceId: String): List<FeedArticle> {
        val out = mutableListOf<FeedArticle>()
        for (match in Regex("""<url>(.*?)</url>""", RegexOption.DOT_MATCHES_ALL).findAll(doc)) {
            val block = match.groupValues[1]
            val loc = tagText(block, "loc") ?: continue
            if (!loc.startsWith("http")) continue
            val title = tagText(block, "news:title") ?: tagText(block, "title") ?: loc
            val date = tagText(block, "news:publication_date") ?: tagText(block, "lastmod")
            val keywords = tagText(block, "news:keywords")
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            val image = Regex("""<image:image>\s*<image:loc>([^<]+)</image:loc>""")
                .find(block)?.groupValues?.get(1)?.let { decodeEntities(it.trim()) }
            out += FeedArticle(
                url = loc, sourceId = sourceId, title = title,
                publishedAtMillis = DateParsing.parseMillis(date) ?: 0L,
                imageUrl = image, keywords = keywords,
            )
        }
        return out
    }

    private fun extractRss(doc: String, sourceId: String): List<FeedArticle> {
        val out = mutableListOf<FeedArticle>()
        for (match in Regex("""<item>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL).findAll(doc)) {
            val block = match.groupValues[1]
            val link = tagText(block, "link") ?: continue
            val title = tagText(block, "title") ?: link
            val date = tagText(block, "pubDate") ?: tagText(block, "dc:date")
            val image = Regex("""<media:content[^>]*url="([^"]+)"""").find(block)?.groupValues?.get(1)
                ?: Regex("""enclosure[^>]*url="([^"]+)"""").find(block)?.groupValues?.get(1)
            out += FeedArticle(link, sourceId, title, DateParsing.parseMillis(date) ?: 0L, image)
        }
        return out
    }

    private fun extractAtom(doc: String, sourceId: String): List<FeedArticle> {
        val out = mutableListOf<FeedArticle>()
        for (match in Regex("""<entry>(.*?)</entry>""", RegexOption.DOT_MATCHES_ALL).findAll(doc)) {
            val block = match.groupValues[1]
            val link = Regex("""<link[^>]*href="([^"]+)"""").find(block)?.groupValues?.get(1) ?: continue
            val title = tagText(block, "title") ?: link
            val date = tagText(block, "published") ?: tagText(block, "updated")
            out += FeedArticle(decodeEntities(link), sourceId, title, DateParsing.parseMillis(date) ?: 0L)
        }
        return out
    }

    private fun tagText(block: String, tag: String): String? {
        val raw = Regex("""<$tag>(.*?)</$tag>""", RegexOption.DOT_MATCHES_ALL)
            .find(block)?.groupValues?.get(1) ?: return null
        val cdata = raw.trim().removePrefix("<![CDATA[").removeSuffix("]]>")
        return decodeEntities(cdata.trim()).takeIf { it.isNotEmpty() }
    }

    internal fun decodeEntities(text: String): String {
        var out = text
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")
        out = out.replace(Regex("""&#x([0-9a-fA-F]+);""")) {
            it.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: it.value
        }
        return out.replace(Regex("""&#(\d+);""")) {
            it.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: it.value
        }
    }
}
