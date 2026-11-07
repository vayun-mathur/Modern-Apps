package com.vayunmathur.web.platform

import com.vayunmathur.web.data.WebSearchKind
import com.vayunmathur.web.data.WebSearchResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SearchUrlsAndWebSearchTest {

    @Test fun nativeSearchUrlRoundTrips() {
        val url = nativeSearchUrl("hello world & cats")
        assertTrue(url.startsWith("search://results?q="))
        assertTrue(isNativeSearchUrl(url))
        assertEquals("hello world & cats", nativeSearchQuery(url))
    }

    @Test fun nonNativeUrlsHaveNoQuery() {
        assertFalse(isNativeSearchUrl("https://example.com/?q=x"))
        assertEquals("", nativeSearchQuery("https://example.com/?q=x"))
        assertEquals("", nativeSearchQuery("search://results"))
    }

    @Test fun displayTextForUrlVariants() {
        assertEquals("", displayTextForUrl(""))
        assertEquals("", displayTextForUrl("about:blank"))
        assertEquals("cats", displayTextForUrl(nativeSearchUrl("cats")))
        assertEquals("https://example.com", displayTextForUrl("https://example.com"))
    }

    @Test fun webSearchKindParams() {
        assertEquals("text", WebSearchKind.TEXT.param)
        assertEquals("images", WebSearchKind.IMAGES.param)
        assertEquals("news", WebSearchKind.NEWS.param)
    }

    @Test fun webSearchResponseDecodesServerShape() {
        val json = Json { ignoreUnknownKeys = true }
        val resp = json.decodeFromString<WebSearchResponse>(
            """{"type":"images","query":"cats","images":[{"title":"Cat","image_url":"https://i/1.png","thumbnail":"https://i/t.png","source_url":"https://s/1"}],"text":null,"news":null,"fallback_url":"https://ddg.example"}"""
        )
        assertEquals("images", resp.kind)
        assertEquals("cats", resp.query)
        assertEquals("https://i/1.png", resp.images?.single()?.imageUrl)
        assertEquals("https://s/1", resp.images?.single()?.sourceUrl)
        assertEquals("https://ddg.example", resp.fallbackUrl)
    }

    @Test fun webSearchResponseDefaultsAndError() {
        val json = Json { ignoreUnknownKeys = true }
        val resp = json.decodeFromString<WebSearchResponse>("""{"error":"upstream blocked"}""")
        assertEquals("text", resp.kind)
        assertEquals("upstream blocked", resp.error)
        assertEquals(null, resp.text)
    }

    @Test fun webSearchTextAndNewsItemsDecode() {
        val json = Json { ignoreUnknownKeys = true }
        val resp = json.decodeFromString<WebSearchResponse>(
            """{"type":"news","query":"q","news":[{"title":"N","url":"https://n","snippet":"s","date":123,"source":"Src"}],"text":[{"title":"T","url":"https://t","snippet":"sn"}]}"""
        )
        assertEquals(123L, resp.news?.single()?.date)
        assertEquals("Src", resp.news?.single()?.source)
        assertEquals("sn", resp.text?.single()?.snippet)
    }
}
