package com.vayunmathur.news.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerFeedParserTest {
    private val fixture = """
        {"generated_at":"2026-11-23T12:00:00Z","articles":[
          {"title":"Server Story","url":"https://example.com/a","source":"bbc",
           "published_at":"2026-11-23T10:00:00Z","image_url":"https://example.com/a.jpg","metered":true,"language":"pt-BR"},
          {"title":"Named Source","url":"https://example.com/b","source":"The Guardian",
           "published_at":1795420000},
          {"title":"No Image","url":"https://example.com/c","source":"npr",
           "published_at":"2026-11-23T08:00:00+00:00"},
          {"title":"German Story","url":"https://example.com/d","source":"tagesschau",
           "published_at":"2026-11-23T07:00:00Z"}
        ]}
    """.trimIndent()

    @Test fun parsesContractFixture() {
        val articles = ServerFeedParser.parse(fixture)
        assertEquals(4, articles.size)
        val first = articles[0]
        assertEquals("bbc", first.sourceId)
        assertEquals("https://example.com/a.jpg", first.imageUrl)
        assertTrue(first.metered)
        assertTrue(first.publishedAtMillis > 0)
        assertEquals("guardian", articles[1].sourceId, "exact catalog name must resolve to catalog id")
        assertEquals(1_795_420_000_000L, articles[1].publishedAtMillis, "epoch seconds must become millis")
        assertEquals(null, articles[2].imageUrl)
        assertEquals("pt-BR", first.language, "server-stated language wins")
        assertEquals("en", articles[1].language, "absent language defaults from the catalog")
        assertEquals("de", articles[3].language, "non-English catalog default")
    }

    @Test fun malformedInputIsEmptyNotThrowing() {
        assertTrue(ServerFeedParser.parse("not json").isEmpty())
        assertTrue(ServerFeedParser.parse("""{"articles":[{"title":"x"}]}""").isEmpty())
        assertTrue(ServerFeedParser.parse("""{}""").isEmpty())
    }
}
