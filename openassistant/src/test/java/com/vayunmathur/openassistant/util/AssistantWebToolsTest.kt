package com.vayunmathur.openassistant.util

import com.vayunmathur.library.intents.contacts.ContactData
import com.vayunmathur.library.intents.maps.DirectionsLatLng
import com.vayunmathur.library.intents.maps.DirectionsResult
import com.vayunmathur.library.intents.maps.DirectionsSegment
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.vayunmathur.openassistant.network.AssistantWebTools
import com.vayunmathur.openassistant.platform.filterContacts
import com.vayunmathur.openassistant.platform.summarizeDirections

/** Phase W web tools over a fake transport (no live network), plus P2 pure helpers. */
class AssistantWebToolsTest {

    @BeforeTest fun setUp() {
        AssistantWebTools.clearSharedForTest()
    }

    private fun searchJson(n: Int): String {
        val items = (1..n).joinToString(",") { i ->
            """{"title":"Result $i","url":"https://example$i.com/page$i","snippet":"Snippet $i about topic"}"""
        }
        return """{"type":"text","query":"q","text":[$items]}"""
    }

    @Test fun webSearchParsesAndNumbersResults() = runBlocking {
        val tools = AssistantWebTools(transport = { searchJson(3) }, nowMillis = { 1_000L })
        val out = tools.webSearch("q", count = 5)
        assertTrue(out.contains("[1] Result 1 — example1.com"), out)
        assertTrue(out.contains("https://example1.com/page1"), out)
        assertTrue(out.contains("[3] Result 3"), out)
        assertEquals(3, tools.lastResults().size)
        // The searchResults card is stashed for the engine to attach.
        val template = tools.takeTemplate()
        assertTrue(template != null && template.contains("web.read"), "$template")
        assertEquals(null, tools.takeTemplate())
    }

    @Test fun webSearchClampsCount() = runBlocking {
        val tools = AssistantWebTools(transport = { searchJson(10) }, nowMillis = { 1_000L })
        val clampedLow = tools.webSearch("q", count = 0)
        assertTrue(clampedLow.contains("[1]") && !clampedLow.contains("[2]"), clampedLow)
        val clampedDefault = AssistantWebTools(transport = { searchJson(10) }, nowMillis = { 1_000L })
            .webSearch("q", count = 99)
        assertTrue(clampedDefault.contains("[10]"), clampedDefault)
        val two = AssistantWebTools(transport = { searchJson(10) }, nowMillis = { 1_000L })
            .webSearch("q", count = 2)
        assertTrue(two.contains("[2]") && !two.contains("[3]"), two)
    }

    @Test fun webSearchCapsTotalChars() = runBlocking {
        val big = (1..10).joinToString(",") { i ->
            """{"title":"T$i ${"x".repeat(400)}","url":"https://e$i.com/","snippet":"${"y".repeat(400)}"}"""
        }
        val tools = AssistantWebTools(
            transport = { """{"type":"text","query":"q","text":[$big]}""" },
            nowMillis = { 1_000L },
        )
        val out = tools.webSearch("q", count = 10)
        assertTrue(out.length <= AssistantWebTools.MAX_SEARCH_CHARS, "len=${out.length}")
    }

    @Test fun webSearchNewsKind() = runBlocking {
        val news = """{"type":"news","query":"q","news":[{"title":"Headline","url":"https://news.example.com/a","snippet":"Breaking","date":1,"source":"Wire"}]}"""
        val tools = AssistantWebTools(transport = { news }, nowMillis = { 1_000L })
        val out = tools.webSearch("q", kind = "news")
        assertTrue(out.contains("[1] Headline — news.example.com"), out)
    }

    @Test fun webSearchErrorFieldBecomesUnavailable() = runBlocking {
        val body = """{"type":"text","query":"q","error":"temporarily unavailable","fallback_url":"https://html.duckduckgo.com/html/?q=q"}"""
        val tools = AssistantWebTools(transport = { body }, nowMillis = { 1_000L })
        val out = tools.webSearch("q")
        assertEquals(
            "Error: search unavailable: temporarily unavailable (fallback: https://html.duckduckgo.com/html/?q=q)",
            out,
        )
        assertEquals(null, tools.takeTemplate())
    }

    @Test fun webSearchTransportFailureIsClean() = runBlocking {
        val tools = AssistantWebTools(
            transport = { throw java.io.IOException("down") },
            nowMillis = { 1_000L },
        )
        assertEquals("Error: search unavailable: down", tools.webSearch("q"))
    }

    @Test fun webReadSuccessAndCaps() = runBlocking {
        val longText = "a".repeat(9_000)
        val body = """{"title":"Page Title","url":"https://example.com/a","text":"$longText","truncated":true,"next_offset":6000}"""
        val tools = AssistantWebTools(transport = { body }, nowMillis = { 1_000L })
        val out = tools.webRead("https://example.com/a")
        assertTrue(out.startsWith("Page Title\nhttps://example.com/a"), out.take(80))
        assertTrue(out.contains("[truncated - continue with offset 6000]"), out.takeLast(80))
        // Header + capped text + note stays near the cap.
        assertTrue(out.length <= AssistantWebTools.MAX_READ_CHARS + 120, "len=${out.length}")
    }

    @Test fun webReadFailureIsClean() = runBlocking {
        val tools = AssistantWebTools(
            transport = { throw java.io.IOException("no route") },
            nowMillis = { 1_000L },
        )
        assertEquals("Error: could not read page: no route", tools.webRead("https://x.example/"))
        assertTrue(tools.webRead("").startsWith("Error: could not read page:"))
    }

    @Test fun webReadResultResolvesAndValidates() = runBlocking {
        var now = 1_000L
        val tools = AssistantWebTools(
            transport = { url ->
                if (url.contains("/api/search")) {
                    searchJson(2)
                } else {
                    """{"title":"T","url":"https://example2.com/page2","text":"hello page","truncated":false}"""
                }
            },
            nowMillis = { now },
        )
        tools.webSearch("q")
        val read = tools.webReadResult(2)
        assertTrue(read.contains("hello page"), read)
        assertTrue(tools.webReadResult(9).startsWith("Error: no result at index 9"))
        // Expiry: past 30 min the stored search no longer resolves.
        now += AssistantWebTools.EXPIRY_MILLIS + 1
        assertEquals(
            "Error: no recent search results - run web_search first",
            tools.webReadResult(1),
        )
    }

    @Test fun webReadResultWithoutSearchErrors() = runBlocking {
        val tools = AssistantWebTools(transport = { "{}" }, nowMillis = { 1_000L })
        assertEquals(
            "Error: no recent search results - run web_search first",
            tools.webReadResult(1),
        )
    }

    @Test fun filterContactsMatchesNameAndPhoneCaseInsensitively() {
        val contacts = listOf(
            ContactData("Alice Smith", "555-0100"),
            ContactData("bob jones", "555-0199"),
            ContactData("Carol Alice", "555-0111"),
            ContactData("Dan", "555-ALICE"),
        )
        assertEquals(
            listOf("Alice Smith", "Carol Alice", "Dan"),
            filterContacts("alice", contacts).map { it.name },
        )
        assertEquals(listOf("bob jones"), filterContacts("BOB", contacts).map { it.name })
        assertEquals(listOf("Alice Smith"), filterContacts("0100", contacts).map { it.name })
        assertTrue(filterContacts("zzz", contacts).isEmpty())
        // Cap holds at 10.
        val many = (1..20).map { ContactData("Ann $it", "1$it") }
        assertEquals(10, filterContacts("ann", many).size)
    }

    @Test fun summarizeDirectionsDropsPolyline() {
        val points = (1..500).map { DirectionsLatLng(1.0 + it * 0.001, 2.0) }
        val result = DirectionsResult(
            polyline = points,
            segments = listOf(
                DirectionsSegment(points.take(250), 1.0),
                DirectionsSegment(points.drop(250), 0.4),
            ),
            distanceMeters = 12_340.0,
            durationSeconds = 2_700.0,
        )
        val out = summarizeDirections(result)
        assertEquals("Route: 12.3 km, 45 min, 2 segment(s), 500 point(s)", out)
        // No coordinate text leaks into the model-visible summary.
        assertFalse(out.contains("DirectionsLatLng"), out)
        assertEquals("No route found", summarizeDirections(DirectionsResult(emptyList(), emptyList(), 0.0, 0.0)))
    }
}
