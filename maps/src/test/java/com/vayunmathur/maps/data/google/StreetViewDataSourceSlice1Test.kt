package com.vayunmathur.maps.data.google

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 1/3 (maps): [StreetViewDataSource] metadata parsing + tile-grid
 * selection, driven through the `httpGet`/`httpGetBytes` seams — no network.
 *
 * Robolectric (not plain JVM) because [StreetViewDataSource.loadPanorama]
 * builds a real [android.graphics.Bitmap]/[android.graphics.Canvas], which
 * are null stubs without it.
 *
 * Payloads mirror the calibrated positional shapes: pano node at root[1]
 * (SingleImageSearch) or root[1][0] (photometa), position at [5,0,1], the
 * local graph at [5,0,3,0].
 */
@RunWith(RobolectricTestRunner::class)
class StreetViewDataSourceSlice1Test {

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

        fun json(): JsonElement = toEl(root)

        /** SingleImageSearch envelope: `cb(<json>)`. */
        fun callbackBody(): String = "/**/cb && cb(${toEl(root)})"

        /** photometa envelope: XSSI guard + JSON. */
        fun guardBody(): String = ")]}'\n${toEl(root)}"
    }

    /**
     * A pano node under [nodeAt] (root[1] for SingleImageSearch, root[1,0]
     * for photometa). Paths below are node-relative.
     */
    private fun Payload.fullPano(
        vararg nodeAt: Int,
        id: String = "CAoSLEFGMVFpcE1yM19XbG9D",
        lat: Double = 37.7749,
        lng: Double = -122.4194,
        heading: Double = 90.0,
        graph: JsonElement? = null,
    ) {
        val p: Payload = this
        val base = if (nodeAt.isEmpty()) intArrayOf(1) else nodeAt
        fun put(vararg path: Int, value: JsonElement) {
            p.set(*base, *path, value = value)
        }
        put(1, 1, value = JsonPrimitive(id))
        put(2, 3, 1, 0, value = JsonPrimitive(512))
        put(
            2, 3, 0, value = JsonArray(
                listOf(
                    JsonArray(listOf(JsonArray(listOf(JsonPrimitive(256), JsonPrimitive(512))))),
                    JsonArray(listOf(JsonArray(listOf(JsonPrimitive(512), JsonPrimitive(1024))))),
                ),
            ),
        )
        put(5, 0, 1, 0, 2, value = JsonPrimitive(lat))
        put(5, 0, 1, 0, 3, value = JsonPrimitive(lng))
        put(5, 0, 1, 2, 0, value = JsonPrimitive(heading))
        put(3, 2, 0, 0, value = JsonPrimitive("Market St"))
        put(4, 0, 0, 0, 0, value = JsonPrimitive("© 2026 Google"))
        put(6, 7, 0, value = JsonPrimitive(2024))
        put(6, 7, 1, value = JsonPrimitive(5))
        if (graph != null) put(5, 0, 3, 0, value = graph)
    }

    /** One local-graph node: id at [0,1], position at [2,0,2]/[2,0,3]. */
    private fun graphNode(id: String, lat: Double, lng: Double): JsonElement =
        Payload().apply {
            set(0, 1, value = JsonPrimitive(id))
            set(2, 0, 2, value = JsonPrimitive(lat))
            set(2, 0, 3, value = JsonPrimitive(lng))
        }.json()

    private val defaultGet = StreetViewDataSource.httpGet
    private val defaultBytes = StreetViewDataSource.httpGetBytes

    private fun runWithMeta(
        body: String?,
        block: suspend (requested: MutableList<String>) -> Unit,
    ) = runBlocking {
        val requested = mutableListOf<String>()
        StreetViewDataSource.httpGet = { url -> requested += url; body }
        StreetViewDataSource.httpGetBytes = { null }
        try {
            block(requested)
        } finally {
            StreetViewDataSource.httpGet = defaultGet
            StreetViewDataSource.httpGetBytes = defaultBytes
        }
    }

    @Test
    fun `nearest parses the full pano record`() = runWithMeta(
        Payload().apply { fullPano() }.callbackBody(),
    ) {
        val pano = assertNotNull(StreetViewDataSource.nearest(11.1111, 22.2222))
        assertEquals("CAoSLEFGMVFpcE1yM19XbG9D", pano.panoId)
        assertEquals(37.7749, pano.lat)
        assertEquals(-122.4194, pano.lng)
        assertEquals(90.0, pano.headingDeg)
        assertEquals(512, pano.tileSize)
        assertEquals(listOf(512 to 256, 1024 to 512), pano.levelDims)
        assertEquals("Market St", pano.addressLabel)
        assertEquals("© 2026 Google", pano.copyright)
        assertEquals(2024, pano.captureYear)
        assertEquals(5, pano.captureMonth)
        assertTrue(pano.neighbors.isEmpty())
    }

    @Test
    fun `a missing response position falls back to the query point`() = runWithMeta(
        Payload().apply {
            set(1, 1, 1, value = JsonPrimitive("CAoSLEFGMVFpcE1yM19XbG9D"))
        }.callbackBody(),
    ) {
        val pano = assertNotNull(StreetViewDataSource.nearest(33.3333, -44.4444))
        assertEquals(33.3333, pano.lat)
        assertEquals(-44.4444, pano.lng)
        assertEquals(512, pano.tileSize, "tile size degrades to the modern default")
        assertTrue(pano.levelDims.isEmpty(), "no level dims degrades to the assumed shape")
    }

    @Test
    fun `byPano reads the one-deeper photometa nesting`() = runWithMeta(
        Payload().apply {
            // root[1][0] carries the pano node; root[1] itself has no id.
            fullPano(1, 0, id = "DBcTLEFGMVFpcE2yM29XbG9E")
        }.guardBody(),
    ) {
        // Direct id lookup: the one-deeper nesting resolves through panoNode
        // to the same record a SingleImageSearch probe would parse.
        val direct = assertNotNull(StreetViewDataSource.byPano("DBcTLEFGMVFpcE2yM29XbG9E"))
        assertEquals("DBcTLEFGMVFpcE2yM29XbG9E", direct.panoId)
        assertEquals(37.7749, direct.lat)
    }

    @Test
    fun `missing positions fall back to the query point, never to zero`() = runWithMeta(
        Payload().apply {
            // No position node at all (all posNode paths miss).
            set(1, 1, 1, value = JsonPrimitive("NOFALLBACKNEEDED000001"))
        }.callbackBody(),
    ) {
        val pano = assertNotNull(StreetViewDataSource.nearest(93.9393, 94.9494))
        assertEquals(93.9393, pano.lat)
        assertEquals(94.9494, pano.lng)
    }

    @Test
    fun `byPano refuses an explicit zero position`() = runWithMeta(
        Payload().apply {
            fullPano(1, 0, id = "FALLBACKREFUSED0123456", lat = 0.0, lng = 0.0)
        }.guardBody(),
    ) {
        // The record carries (0,0) explicitly and the by-id fetch has no
        // query point to fall back on: refused, never a Gulf-of-Guinea pano.
        assertNull(StreetViewDataSource.byPano("FALLBACKREFUSED0123456"))
    }

    @Test
    fun `neighbours declutter to one walkable pano per direction`() {
        // ~11 m per 0.0001 deg lat: east ≈ 11 m, north ≈ 22 m, near-east ≈ 12 m
        // (same bucket as east, farther → dropped), south ≈ 33 m, far ≈ 111 m.
        val self = "CAoSLEFGMVFpcE1yM19XbG9D"
        val graph = JsonArray(
            listOf(
                graphNode(self, 37.7749, -122.4194),
                graphNode("EASTpanoId000000000001", 37.7749, -122.4193),
                graphNode("NEAREASTpanoId00000002", 37.7749, -122.41929),
                graphNode("NORTHpanoId00000000003", 37.7751, -122.4194),
                graphNode("SOUTHpanoId00000000004", 37.7746, -122.4194),
                graphNode("CLOSEpanoId000000000005", 37.77491, -122.4194),
                graphNode("FARpanoId0000000000006", 37.7759, -122.4194),
            ),
        )
        runWithMeta(Payload().apply { fullPano(graph = graph) }.callbackBody()) {
            val pano = assertNotNull(StreetViewDataSource.nearest(55.5555, 66.6666))
            val ids = pano.neighbors.map { it.panoId }
            assertTrue(self !in ids, "the pano itself is not its own neighbour")
            assertTrue("CLOSEpanoId000000000005" !in ids, "same-spot panos are dropped")
            assertTrue("FARpanoId0000000000006" !in ids, "beyond-walk panos are dropped")
            assertTrue("EASTpanoId000000000001" in ids)
            assertTrue("NEAREASTpanoId00000002" !in ids, "the nearer east pano wins its bucket")
            assertTrue("NORTHpanoId00000000003" in ids)
            assertTrue("SOUTHpanoId00000000004" in ids)
            // Sorted by bearing: north (0) < east (90) < south (180).
            assertEquals(pano.neighbors.map { it.bearingDeg }.sorted(), pano.neighbors.map { it.bearingDeg })
            val east = pano.neighbors.single { it.panoId == "EASTpanoId000000000001" }
            assertTrue(abs(east.bearingDeg - 90.0) < 5.0, "east reads ~90°, got ${east.bearingDeg}")
            assertTrue(east.distanceM in 4.0..45.0)
        }
    }

    @Test
    fun `garbage bodies are null and negatively cached`() = runWithMeta("cb(not json") { requested ->
        assertNull(StreetViewDataSource.nearest(77.7777, 88.8888))
        assertNull(StreetViewDataSource.nearest(77.7777, 88.8888))
        assertEquals(1, requested.size)
    }

    @Test
    fun `nearest caches by probe and shares with byPano`() = runWithMeta(
        Payload().apply { fullPano() }.callbackBody(),
    ) { requested ->
        val first = assertNotNull(StreetViewDataSource.nearest(12.1212, 13.1313))
        assertNotNull(StreetViewDataSource.nearest(12.1212, 13.1313))
        // The pano id was cached alongside the probe: no second fetch.
        assertEquals(first, StreetViewDataSource.byPano("CAoSLEFGMVFpcE1yM19XbG9D"))
        assertEquals(1, requested.size)
    }

    @Test
    fun `loadPanorama with no fetchable tiles is null and asks for the bounded grid`() {
        runBlocking {
            val tileUrls = mutableListOf<String>()
            StreetViewDataSource.httpGetBytes = { url -> tileUrls += url; null }
            try {
                val pano = StreetViewPano(
                    panoId = "CAoSLEFGMVFpcE1yM19XbG9D",
                    lat = 37.0,
                    lng = -122.0,
                    // Old-capture shape: the grid follows THESE dims, not the modern one.
                    levelDims = listOf(832 to 416, 1664 to 832),
                )
                assertNull(StreetViewDataSource.loadPanorama(pano))
                // 1664-wide level wins (largest within 4096): 4 cols x 2 rows at zoom 1.
                assertEquals(8, tileUrls.size)
                assertTrue(tileUrls.all { it.contains("zoom=1") }, "$tileUrls")
                assertTrue(tileUrls.any { it.contains("x=3") && it.contains("y=1") })
                assertTrue(tileUrls.all { it.contains("panoid=CAoSLEFGMVFpcE1yM19XbG9D") })
            } finally {
                StreetViewDataSource.httpGetBytes = defaultBytes
            }
        }
    }
}
