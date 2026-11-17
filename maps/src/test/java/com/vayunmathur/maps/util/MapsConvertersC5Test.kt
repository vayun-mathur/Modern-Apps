package com.vayunmathur.maps.util
import com.vayunmathur.maps.data.MapOverlay

import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.maps.data.Feature1
import com.vayunmathur.maps.data.Geometry
import com.vayunmathur.maps.data.LineString
import com.vayunmathur.maps.data.Point
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.ui.toSelectedSavedPlace
import com.vayunmathur.maps.ui.toSelectedSearchResult
import com.vayunmathur.maps.ui.toTransitStop
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * C5 feature converters and manifest section parsing: transit-stop,
 * saved-place and search-result feature resolution, plus overlay
 * manifests carrying sections and tombstones.
 */
class MapsConvertersC5Test {

    private var temp: File? = null

    @AfterTest
    fun tearDown() {
        temp?.deleteRecursively()
        temp = null
    }

    private fun feature(
        geometry: Geometry,
        props: Map<String, String>?,
    ): Feature1 = Feature1(
        geometry = geometry,
        properties = props?.let { map ->
            buildJsonObject { map.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }
        },
    )

    private fun point(lat: Double, lon: Double) = Point(GeoPoint(lon, lat))

    // -- toTransitStop ---------------------------------------------------------

    @Test
    fun `transit stop prefers motis id and falls back to name`() {
        val withMotis = feature(
            point(37.8, -122.4),
            mapOf("name" to "Civic Center", "motis_id" to "de:08212:1234"),
        ).toTransitStop()
        assertNotNull(withMotis)
        assertEquals("de:08212:1234", withMotis.id)
        assertEquals("Civic Center", withMotis.name)
        assertEquals(37.8, withMotis.lat)
        assertEquals(-122.4, withMotis.lon)

        val nameOnly = feature(point(37.8, -122.4), mapOf("name" to "Civic Center")).toTransitStop()
        assertEquals("Civic Center", nameOnly?.id)
        assertEquals("Civic Center", nameOnly?.name)

        val motisOnly = feature(point(37.8, -122.4), mapOf("motis_id" to "m-1")).toTransitStop()
        assertEquals("m-1", motisOnly?.id)
        assertEquals("m-1", motisOnly?.name)
    }

    @Test
    fun `transit stop rejects missing properties geometry and ids`() {
        assertNull(feature(point(37.8, -122.4), null).toTransitStop())
        assertNull(
            feature(
                LineString(listOf(GeoPoint(-122.4, 37.8), GeoPoint(-122.3, 37.9))),
                mapOf("name" to "Line"),
            ).toTransitStop(),
        )
        assertNull(feature(point(37.8, -122.4), mapOf("name" to "  ", "motis_id" to "")).toTransitStop())
        assertNull(feature(point(37.8, -122.4), emptyMap()).toTransitStop())
    }

    // -- saved / search converters ----------------------------------------------

    @Test
    fun `saved and search features resolve to generic places`() {
        val saved = feature(
            point(37.8, -122.4),
            mapOf("name" to "Home", "lat" to "37.8", "lng" to "-122.4"),
        ).toSelectedSavedPlace() as SpecificFeature.GenericPlace
        assertEquals("Home", saved.name)
        assertEquals(37.8, saved.position.latitude)
        assertEquals(-122.4, saved.position.longitude)

        val search = feature(
            point(37.8, -122.4),
            mapOf("name" to "Cafe", "lat" to "37.8", "lng" to "-122.4"),
        ).toSelectedSearchResult() as SpecificFeature.GenericPlace
        assertEquals("Cafe", search.name)
    }

    @Test
    fun `saved and search features reject blank names and bad coordinates`() {
        assertNull(
            feature(point(0.0, 0.0), mapOf("name" to " ", "lat" to "37.8", "lng" to "-122.4"))
                .toSelectedSavedPlace(),
        )
        assertNull(
            feature(point(0.0, 0.0), mapOf("name" to "Home", "lat" to "north", "lng" to "-122.4"))
                .toSelectedSavedPlace(),
        )
        assertNull(
            feature(point(0.0, 0.0), mapOf("name" to "Home", "lat" to "37.8"))
                .toSelectedSearchResult(),
        )
        assertNull(feature(point(0.0, 0.0), null).toSelectedSearchResult())
        assertNull(
            feature(point(0.0, 0.0), mapOf("lat" to "37.8", "lng" to "-122.4"))
                .toSelectedSavedPlace(),
        )
    }

    // -- manifest sections ---------------------------------------------------------

    @Test
    fun `manifest with sections and tombstones parses sorted`() {
        val text = """
            {"version": 1, "min_client": 1, "overlay_seq": 12,
             "overlay_build_id": "0xFFFFFFFFFFFFFFFF",
             "base": {"url": "https://example.invalid/base.mamaps",
                      "build_id": "0x8000000000000001", "len": 1024},
             "segments": [
               {"url": "https://example.invalid/s1", "len": 8, "sha256": "bb", "seq": 1},
               {"url": "https://example.invalid/s0", "len": 4, "sha256": "aa", "seq": 0}
             ],
             "sections": [
               {"kind": 16, "url": "https://example.invalid/sec16", "len": 64,
                "sha256": "cc", "chunk_size": 16, "chunk_hashes": ["h3", "h4"]},
               {"kind": 3, "url": "https://example.invalid/sec3", "len": 32,
                "sha256": "dd", "chunk_size": 8, "chunk_hashes": ["h1", "h2"]}
             ],
             "tombstones": [104, 85]}
        """.trimIndent()
        val manifest = assertNotNull(MapOverlay.parse(text))
        assertEquals(12L, manifest.overlaySeq)
        // Segments sort by seq, sections by kind, ids keep their high bit.
        assertEquals(listOf(0, 1), manifest.segments.map { it.seq })
        assertEquals(listOf(3, 16), manifest.sections.map { it.kind })
        assertEquals(listOf("h1", "h2"), manifest.sections[0].chunkHashes)
        assertEquals(8L, manifest.sections[0].chunkSize)
        assertEquals(listOf(104L, 85L), manifest.tombstones)
        assertEquals(-1L, manifest.overlayBuildId)
        assertTrue(manifest.baseBuildId != 0L)
    }

    @Test
    fun `manifest refuses wrong version future client and malformed bodies`() {
        val base = """"base": {"url": "u", "build_id": "0x1", "len": 1}"""
        assertNull(MapOverlay.parse("""{"version": 2, $base, "overlay_seq": 1, "overlay_build_id": "0x1", "segments": []}"""))
        assertNull(MapOverlay.parse("""{"version": 1, "min_client": 9, $base, "overlay_seq": 1, "overlay_build_id": "0x1", "segments": []}"""))
        assertNull(MapOverlay.parse("{ not json"))
        assertNull(MapOverlay.parse("""{"version": 1}"""))
        // Build-id parsing: hex with and without prefix, high bit, garbage.
        assertEquals(0x1234L, MapOverlay.parseBuildId("0x1234"))
        assertEquals(0x1234L, MapOverlay.parseBuildId("1234"))
        assertEquals(-1L, MapOverlay.parseBuildId("0xFFFFFFFFFFFFFFFF"))
        assertEquals(0L, MapOverlay.parseBuildId("banana"))
        assertEquals(0L, MapOverlay.parseBuildId(""))
    }

    @Test
    fun `pin writes and reads back through the overlay root`() {
        val dir = Files.createTempDirectory("mapoverlayc5").toFile()
        temp = dir
        assertNull(MapOverlay.pinned(dir))
        val text = """
            {"version": 1, "base": {"url": "u", "build_id": "0x2a", "len": 7},
             "overlay_seq": 3, "overlay_build_id": "0x2b", "segments": []}
        """.trimIndent()
        MapOverlay.pin(dir, text)
        assertEquals(3L, MapOverlay.pinned(dir)?.overlaySeq)
        assertTrue(MapOverlay.isApplied(dir, assertNotNull(MapOverlay.pinned(dir))))
        assertEquals(0L, MapOverlay.pendingBytes(dir, assertNotNull(MapOverlay.pinned(dir))))
    }
}
