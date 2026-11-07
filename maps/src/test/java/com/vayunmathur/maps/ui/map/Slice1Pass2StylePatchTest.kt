package com.vayunmathur.maps.ui.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Slice 1/3 second pass (maps): the dark basemap palette applied by
 * [patchStyleForHybrid] — every layer-id colour rule, reached through the
 * public entry point the app actually calls.
 */
class Slice1Pass2StylePatchTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun styleWithLayers(vararg layers: String): String {
        val layerJson = layers.joinToString(",") { layer ->
            if (layer == "background") {
                """{"id":"background","type":"background","paint":{"background-color":"#eeeeee"}}"""
            } else {
                """{"id":"$layer","type":"fill","source":"protomaps","paint":{"fill-color":"#ffffff","line-width":2}}"""
            }
        }
        return """{"sources":{"old":{}},"layers":[$layerJson],"glyphs":"g","sprite":"s"}"""
    }

    private fun darkColorsById(patched: String): Map<String, String> {
        val root = json.parseToJsonElement(patched).jsonObject
        val layers = root["layers"]!!.jsonArray
        return layers.map { el ->
            val obj = el.jsonObject
            val id = obj["id"]!!.jsonPrimitive.content
            val paint = obj["paint"]!!.jsonObject
            id to (paint["fill-color"]?.jsonPrimitive?.content
                ?: paint["background-color"]?.jsonPrimitive?.content ?: "")
        }.toMap()
    }

    @Test
    fun `dark palette covers every layer family`() {
        val ids = listOf(
            "background", "earth", "water", "water_stream", "water_river",
            "landcover", "landuse_park", "landuse_urban_green", "landuse_hospital",
            "landuse_industrial", "landuse_school", "landuse_beach", "landuse_zoo",
            "landuse_aerodrome", "landuse_runway", "landuse_pedestrian", "landuse_pier",
            "landuse_other", "buildings", "boundaries_country", "roads_rail",
            "roads_runway_a", "roads_taxiway_a", "roads_highway_casing_early",
            "roads_tunnels_motorway", "roads_highway", "roads_major_link",
            "roads_minor", "transit_whatever",
        )
        val patched = patchStyleForHybrid(styleWithLayers(*ids.toTypedArray()), "base://", "hybrid://", dark = true)
        val colors = darkColorsById(patched)
        // each non-background layer appears twice (_base and _hybrid) with the dark colour
        assertEquals("#0d1b2a", colors["water_base"])
        assertEquals("#0d1b2a", colors["water_hybrid"])
        assertEquals("#24455f", colors["water_river_base"])
        assertEquals("#1e2b20", colors["landuse_park_base"])
        assertEquals("#1f2126", colors["landuse_other_base"])
        assertEquals("#22262c", colors["buildings_base"])
        assertEquals("#4a4f57", colors["boundaries_country_base"])
        assertEquals("#3a3e45", colors["roads_rail_base"])
        assertEquals("#2b2d33", colors["roads_runway_a_base"])
        // casing must win over the highway rule it also matches
        assertEquals("#111318", colors["roads_highway_casing_early_base"])
        assertEquals("#2b2e35", colors["roads_tunnels_motorway_base"])
        assertEquals("#464b54", colors["roads_highway_base"])
        assertEquals("#464b54", colors["roads_major_link_base"])
        assertEquals("#34383f", colors["roads_minor_base"])
        assertEquals("#26282e", colors["transit_whatever_base"])
        // background is kept as a single layer with the dark earth colour
        assertEquals("#1b1d22", colors["background"])
        assertFalse(colors.containsKey("background_base"))
        // non-colour paint keys survive untouched
        val root = json.parseToJsonElement(patched).jsonObject
        val waterPaint = root["layers"]!!.jsonArray
            .first { it.jsonObject["id"]!!.jsonPrimitive.content == "water_base" }
            .jsonObject["paint"]!!.jsonObject
        assertEquals("2", waterPaint["line-width"]!!.jsonPrimitive.content)
    }

    @Test
    fun `dark text colours split by label family`() {
        fun symbolLayer(id: String) =
            """{"id":"$id","type":"symbol","paint":{"text-color":"#000","text-halo-color":"#fff","text-size":12}}"""
        val layers = listOf(
            "places_locality", "places_country", "places_region", "places_subplace",
            "earth_label_islands", "water_label_ocean", "water_waterway_label",
            "roads_shields", "roads_labels_major", "address_label", "pois",
        )
        val style = """{"sources":{},"layers":[${layers.joinToString(",") { symbolLayer(it) }}]}"""
        val patched = patchStyleForHybrid(style, "base://", "hybrid://", dark = true)
        val root = json.parseToJsonElement(patched).jsonObject
        val byId = root["layers"]!!.jsonArray.associate { el ->
            val obj = el.jsonObject
            obj["id"]!!.jsonPrimitive.content to obj["paint"]!!.jsonObject
        }
        assertEquals("#e4e8ee", byId["places_locality_base"]!!["text-color"]!!.jsonPrimitive.content)
        assertEquals("#9aa0aa", byId["places_country_base"]!!["text-color"]!!.jsonPrimitive.content)
        assertEquals("#80868f", byId["places_region_base"]!!["text-color"]!!.jsonPrimitive.content)
        assertEquals("#6f8fce", byId["water_label_ocean_base"]!!["text-color"]!!.jsonPrimitive.content)
        assertEquals("#0d1b2a", byId["water_label_ocean_base"]!!["text-halo-color"]!!.jsonPrimitive.content)
        assertEquals("#c8ccd4", byId["roads_shields_base"]!!["text-color"]!!.jsonPrimitive.content)
        assertEquals("#b8bdc6", byId["address_label_base"]!!["text-color"]!!.jsonPrimitive.content)
        // named label families keep their own tints; halos stay near-black
        assertEquals("#9aa0aa", byId["earth_label_islands_base"]!!["text-color"]!!.jsonPrimitive.content)
        assertEquals("#101216", byId["places_locality_hybrid"]!!["text-halo-color"]!!.jsonPrimitive.content)
        assertEquals("12", byId["places_locality_base"]!!["text-size"]!!.jsonPrimitive.content)
        // the suppressed POI layer never reaches the output even in dark mode
        assertFalse(byId.keys.any { it.startsWith("pois") })
    }

    @Test
    fun `light mode keeps original paint and still splits layers`() {
        val patched = patchStyleForHybrid(styleWithLayers("water", "roads"), "b://", "h://", dark = false)
        val colors = darkColorsById(patched)
        assertEquals("#ffffff", colors["water_base"])
        assertEquals("#ffffff", colors["water_hybrid"])
        val root = json.parseToJsonElement(patched).jsonObject
        val sources = root["sources"]!!.jsonObject
        assertTrue(sources.containsKey("protomaps_base"))
        assertTrue(sources.containsKey("protomaps_hybrid"))
        // non-layer, non-source keys survive
        assertEquals("g", root["glyphs"]!!.jsonPrimitive.content)
    }
}
