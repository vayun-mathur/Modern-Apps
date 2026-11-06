package com.vayunmathur.maps.data.google

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 1/3 (maps): [GooglePoiDataSource] result selection + parsing, driven
 * through the [GooglePoiDataSource.httpGet]/`httpWarm` seams with canned
 * positional-JSON payloads — no network.
 *
 * The payloads mirror the calibrated shapes documented in the source (RESULTS
 * list vs SINGLE geocoded node, place node at entry[1]). The point under test
 * is the defensive contract: near matches win, far ones are refused, and every
 * field degrades to null rather than throwing.
 */
class GooglePoiDataSourceSlice1Test {

    /** Sparse nested-array builder: set(1, 11, value) then read back as JSON. */
    private class Payload {
        private val root = mutableListOf<Any?>()

        fun set(vararg path: Int, value: JsonElement) {
            var cur = root
            for (i in path.dropLast(1)) {
                while (cur.size <= i) cur.add(null)
                @Suppress("UNCHECKED_CAST")
                val nxt = (cur[i] as? MutableList<Any?>)
                    ?: mutableListOf<Any?>().also { cur[i] = it }
                cur = nxt
            }
            val last = path.last()
            while (cur.size <= last) cur.add(null)
            cur[last] = value
        }

        private fun toEl(v: Any?): JsonElement = when (v) {
            null -> JsonNull
            is JsonElement -> v
            is List<*> -> JsonArray(v.map { toEl(it) })
            else -> error("unexpected $v")
        }

        fun node(): JsonElement = toEl(root)

        /** Wrap place [node] as a result entry (place node at [1]). */
        fun entryOf(node: JsonElement): JsonElement =
            JsonArray(listOf(JsonNull, node))

        /** A guarded search body whose RESULTS list holds [entries]. */
        fun searchBody(vararg entries: JsonElement): String {
            set(64, value = JsonArray(entries.toList()))
            return body()
        }

        fun body(): String = ")]}'\n${toEl(root)}"
    }

    /** A place node (NAME at [11], LAT at [9,2], …) for the query point by default. */
    private fun place(
        name: String = "Slice1 Cafe",
        lat: Double = 37.7749,
        lng: Double = -122.4194,
        extra: Payload.() -> Unit = {},
    ): JsonElement = Payload().apply {
        set(11, value = JsonPrimitive(name))
        set(9, 2, value = JsonPrimitive(lat))
        set(9, 3, value = JsonPrimitive(lng))
        extra()
    }.node()

    private fun entry(node: JsonElement): JsonElement =
        JsonArray(listOf(JsonNull, node))

    private val defaultGet = GooglePoiDataSource.httpGet
    private val defaultWarm = GooglePoiDataSource.httpWarm

    private fun runWithFetch(
        searchBody: String?,
        block: suspend (requested: MutableList<String>) -> Unit,
    ) = runBlocking {
        val requested = mutableListOf<String>()
        GooglePoiDataSource.httpWarm = { "" }
        GooglePoiDataSource.httpGet = { url -> requested += url; searchBody }
        try {
            block(requested)
        } finally {
            GooglePoiDataSource.httpGet = defaultGet
            GooglePoiDataSource.httpWarm = defaultWarm
        }
    }

    @Test
    fun `blank names never reach the network`() = runWithFetch("[]") { requested ->
        assertNull(GooglePoiDataSource.fetch("  ", 37.0, -122.0))
        assertTrue(requested.isEmpty())
    }

    @Test
    fun `a full entry parses every field`() {
        val node = place(extra = {
            set(4, 7, value = JsonPrimitive(4.5))
            set(4, 8, value = JsonPrimitive(230))
            set(4, 2, value = JsonPrimitive("$10–20"))
            set(7, 0, value = JsonPrimitive("https://cafe.example"))
            set(178, 0, 0, value = JsonPrimitive("+14155550123"))
            set(203, 1, 4, 0, value = JsonPrimitive("Open · Closes 9 PM"))
            set(13, 0, value = JsonPrimitive("Cafe"))
            set(32, 1, 1, value = JsonPrimitive("  A sunny corner spot. "))
            set(142, 1, 0, 1, 0, 0, value = JsonPrimitive("\"Best oat latte.\""))
            set(10, value = JsonPrimitive("0x808580:0x808581"))
        })
        runWithFetch(Payload().searchBody(entry(node))) {
            val info = assertNotNull(GooglePoiDataSource.fetch("Slice1 Cafe", 37.7749, -122.4194))
            assertEquals(4.5, info.rating)
            assertEquals(230, info.reviewCount)
            assertEquals("$10–20", info.priceText)
            assertEquals(2, info.priceLevel)
            assertEquals("https://cafe.example", info.website)
            assertEquals("+14155550123", info.phone)
            assertEquals(true, info.openNow)
            assertEquals("Open · Closes 9 PM", info.statusText)
            assertEquals("Cafe", info.category)
            assertEquals("A sunny corner spot.", info.editorialSummary)
            assertEquals("Best oat latte.", info.featuredReview)
            assertEquals("0x808580:0x808581", info.featureId)
        }
    }

    @Test
    fun `the nearest entry inside the match radius wins`() {
        val near = entry(place(name = "Slice1 Near", lat = 37.7750, lng = -122.4195) {
            set(4, 7, value = JsonPrimitive(4.0))
        })
        val far = entry(place(name = "Slice1 Far", lat = 37.7900, lng = -122.4194) {
            set(4, 7, value = JsonPrimitive(1.0))
        })
        runWithFetch(Payload().searchBody(far, near)) {
            val info = assertNotNull(GooglePoiDataSource.fetch("Slice1 Pick", 37.7749, -122.4194))
            assertEquals(4.0, info.rating, "the near entry must win over the far one")
        }
    }

    @Test
    fun `no close match is a negative-cacheable no-match`() {
        val far = entry(place(name = "Slice1 Elsewhere", lat = 38.0, lng = -122.0) {
            set(4, 7, value = JsonPrimitive(4.0))
        })
        runWithFetch(Payload().searchBody(far, far)) { requested ->
            assertNull(GooglePoiDataSource.fetch("Slice1 Nowhere", 37.7749, -122.4194))
            assertNull(GooglePoiDataSource.fetch("Slice1 Nowhere", 37.7749, -122.4194))
            assertEquals(1, requested.size, "a no-match must be cached, not refetched")
        }
    }

    @Test
    fun `a lone far entry is still accepted`() {
        val lone = entry(place(name = "Slice1 Lone", lat = 38.5, lng = -121.0) {
            set(4, 7, value = JsonPrimitive(3.5))
        })
        runWithFetch(Payload().searchBody(lone)) {
            val info = assertNotNull(GooglePoiDataSource.fetch("Slice1 Lone", 37.7749, -122.4194))
            assertEquals(3.5, info.rating)
        }
    }

    @Test
    fun `a failed fetch is not cached and retries`() = runWithFetch(null) { requested ->
        assertNull(GooglePoiDataSource.fetch("Slice1 Down", 37.0, -122.0))
        assertNull(GooglePoiDataSource.fetch("Slice1 Down", 37.0, -122.0))
        assertEquals(2, requested.size, "failures must not poison the cache")
    }

    @Test
    fun `a name-only entry is dropped by the signal gate`() {
        runWithFetch(Payload().searchBody(entry(place())) ) {
            assertNull(GooglePoiDataSource.fetch("Slice1 Bare", 37.7749, -122.4194))
        }
    }

    @Test
    fun `fuel price reads the shared slot only for gas stations`() {
        fun fuelled(kind: String?, tag: String) = entry(place(name = "Slice1 Fuel $tag") {
            set(88, 1, value = kind?.let { JsonPrimitive(it) } ?: JsonNull)
            set(88, 0, value = JsonPrimitive("$6.20/Regular"))
            set(4, 7, value = JsonPrimitive(4.0))
        })
        runWithFetch(Payload().searchBody(fuelled("SearchResult.TYPE_GAS_STATION", "gas"))) {
            val info = assertNotNull(GooglePoiDataSource.fetch("Slice1 Gas", 37.7749, -122.4194))
            assertEquals(FuelPrice("$6.20", "Regular"), info.fuelPrice)
        }
        runWithFetch(Payload().searchBody(fuelled("SearchResult.TYPE_RESTAURANT", "food"))) {
            val info = assertNotNull(GooglePoiDataSource.fetch("Slice1 Bistro", 37.7749, -122.4194))
            assertNull(info.fuelPrice, "a restaurant tagline must not become a fuel price")
        }
    }

    @Test
    fun `hours read today only with an optional note`() {
        val node = place(extra = {
            // HOURS_203 day: [name, _, _, [[time]], _, _, [_, note]].
            set(
                203, 0, value = JsonArray(
                    listOf(
                        JsonArray(
                            listOf(
                                JsonPrimitive("Tuesday"),
                                JsonNull, JsonNull,
                                JsonArray(listOf(JsonArray(listOf(JsonPrimitive("6 AM–10 PM"))))),
                                JsonNull, JsonNull,
                                JsonArray(listOf(JsonNull, JsonPrimitive("Holiday hours"))),
                            ),
                        ),
                    ),
                ),
            )
            set(4, 7, value = JsonPrimitive(4.0))
        })
        runWithFetch(Payload().searchBody(entry(node))) {
            val info = assertNotNull(GooglePoiDataSource.fetch("Slice1 Hours", 37.7749, -122.4194))
            assertEquals(listOf("Tuesday: 6 AM–10 PM · Holiday hours"), info.hours)
        }
    }

    @Test
    fun `photos dedupe resize and drop non-google urls`() {
        fun hero(url: String) = JsonArray(
            listOf(JsonNull, JsonNull, JsonNull, JsonNull, JsonNull, JsonNull, JsonArray(listOf(JsonPrimitive(url)))),
        )
        val node = place(extra = {
            set(
                72, 0, value = JsonArray(
                    listOf(
                        hero("https://lh3.googleusercontent.com/p/1=w100-h100-k"),
                        hero("https://lh3.googleusercontent.com/p/1=w100-h100-k"),
                        hero("https://other.example/x.jpg"),
                    ),
                ),
            )
            set(4, 7, value = JsonPrimitive(4.0))
        })
        runWithFetch(Payload().searchBody(entry(node))) {
            val info = assertNotNull(GooglePoiDataSource.fetch("Slice1 Photos", 37.7749, -122.4194))
            assertEquals(
                listOf("https://lh3.googleusercontent.com/p/1=w500-h350"),
                info.photoUrls,
            )
        }
    }

    @Test
    fun `popular times parse the day histogram`() {
        val node = place(extra = {
            set(
                84, value = JsonArray(
                    listOf(
                        JsonArray(
                            listOf(
                                JsonArray(
                                    listOf(
                                        JsonPrimitive(2),
                                        JsonArray(
                                            listOf(
                                                JsonArray(listOf(JsonPrimitive(9), JsonPrimitive(80))),
                                                JsonArray(listOf(JsonPrimitive(10), JsonPrimitive(95))),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            set(4, 7, value = JsonPrimitive(4.0))
        })
        runWithFetch(Payload().searchBody(entry(node))) {
            val info = assertNotNull(GooglePoiDataSource.fetch("Slice1 Busy", 37.7749, -122.4194))
            assertEquals(
                PoiPopularTimes(
                    listOf(
                        PoiDayBusyness(
                            2,
                            listOf(PoiHourBusyness(9, 80), PoiHourBusyness(10, 95)),
                        ),
                    ),
                ),
                info.popularTimes,
            )
        }
    }

    @Test
    fun `status text decides open-now with closed words first`() = runBlocking {
        suspend fun fetchStatus(name: String, status: String): GooglePoiInfo? {
            val node = place(name = name, extra = {
                set(203, 1, 4, 0, value = JsonPrimitive(status))
                set(4, 7, value = JsonPrimitive(4.0))
            })
            GooglePoiDataSource.httpWarm = { "" }
            GooglePoiDataSource.httpGet = { Payload().searchBody(entry(node)) }
            try {
                return GooglePoiDataSource.fetch(name, 37.7749, -122.4194)
            } finally {
                GooglePoiDataSource.httpGet = defaultGet
                GooglePoiDataSource.httpWarm = defaultWarm
            }
        }
        assertEquals(true, fetchStatus("Slice1 StA", "Open · Closes 9 PM")?.openNow)
        assertEquals(false, fetchStatus("Slice1 StB", "Closed · Opens 8 AM Wed")?.openNow)
        assertEquals(false, fetchStatus("Slice1 StC", "Temporarily closed")?.openNow)
        assertNull(fetchStatus("Slice1 StD", "See hours")?.openNow)
    }

    @Test
    fun `price symbols count dollar signs when no amount is present`() = runBlocking {
        suspend fun fetchLevel(name: String, price: String): Int? {
            val node = place(name = name, extra = {
                set(4, 2, value = JsonPrimitive(price))
                set(4, 7, value = JsonPrimitive(4.0))
            })
            GooglePoiDataSource.httpWarm = { "" }
            GooglePoiDataSource.httpGet = { Payload().searchBody(entry(node)) }
            try {
                return GooglePoiDataSource.fetch(name, 37.7749, -122.4194)?.priceLevel
            } finally {
                GooglePoiDataSource.httpGet = defaultGet
                GooglePoiDataSource.httpWarm = defaultWarm
            }
        }
        assertEquals(3, fetchLevel("Slice1 PrA", "$$$"))
        assertEquals(1, fetchLevel("Slice1 PrB", "$5"))
        assertEquals(4, fetchLevel("Slice1 PrC", "$40 tasting menu"))
        assertNull(fetchLevel("Slice1 PrD", "Free"))
    }

    @Test
    fun `a single geocoded node resolves like a list entry`() {
        // SINGLE = [0,1,0,14]: root[0][1][0][14] carries the place node itself.
        val node = place(name = "Slice1 Single", extra = {
            set(4, 7, value = JsonPrimitive(4.2))
        })
        val body = Payload().apply { set(0, 1, 0, 14, value = node) }.body()
        runWithFetch(body) {
            val info = assertNotNull(GooglePoiDataSource.fetch("Slice1 Single", 37.7749, -122.4194))
            assertEquals(4.2, info.rating)
        }
    }
}
