package com.vayunmathur.news.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SitemapParserTest {
    @Test
    fun newsUrlsetParsesTitleDateKeywordsAndImage() {
        val xml = """
            <urlset xmlns:news="http://www.google.com/schemas/sitemap-news/0.9"
                    xmlns:image="http://www.google.com/schemas/sitemap-image/1.1">
              <url><loc>https://example.com/a1</loc>
                <news:news><news:publication><news:name>Ex</news:name><news:language>en</news:language></news:publication>
                <news:publication_date>2026-11-23T10:00:00+00:00</news:publication_date>
                <news:title>First &amp; Foremost</news:title><news:keywords>alpha, beta</news:keywords></news:news>
                <image:image><image:loc>https://example.com/a1.jpg</image:loc></image:image></url>
              <url><loc>https://example.com/a2</loc><lastmod>2026-11-22T09:00:00Z</lastmod></url>
            </urlset>
        """.trimIndent()
        val parsed = SitemapParser.parse(xml, "ex")
        assertEquals(2, parsed.articles.size)
        val first = parsed.articles[0]
        assertEquals("First & Foremost", first.title)
        assertEquals(listOf("alpha", "beta"), first.keywords)
        assertEquals("https://example.com/a1.jpg", first.imageUrl)
        assertTrue(first.publishedAtMillis > 0L)
        assertEquals("https://example.com/a2", parsed.articles[1].title)
    }

    @Test
    fun sitemapIndexYieldsChildren() {
        val xml = """
            <sitemapindex><sitemap><loc>https://example.com/s1.xml</loc></sitemap>
            <sitemap><loc>https://example.com/s2.xml</loc></sitemap></sitemapindex>
        """.trimIndent()
        val parsed = SitemapParser.parse(xml, "ex")
        assertEquals(listOf("https://example.com/s1.xml", "https://example.com/s2.xml"), parsed.childSitemaps)
        assertTrue(parsed.articles.isEmpty())
    }

    @Test
    fun rssItemsParse() {
        val xml = """
            <rss><channel><item><title>Story</title><link>https://example.com/s</link>
            <pubDate>Mon, 23 Nov 2026 10:00:00 GMT</pubDate></item></channel></rss>
        """.trimIndent()
        val parsed = SitemapParser.parse(xml, "ex")
        assertEquals(1, parsed.articles.size)
        assertEquals("Story", parsed.articles[0].title)
        assertTrue(parsed.articles[0].publishedAtMillis > 0L)
    }

    @Test
    fun malformedInputNeverThrows() {
        assertTrue(SitemapParser.parse("not xml at all", "ex").articles.isEmpty())
        assertTrue(SitemapParser.parse("", "ex").articles.isEmpty())
    }
}
