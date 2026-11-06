package com.vayunmathur.maps.ui.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 1/3 (maps): [patchStyleForHybrid] — the runtime style surgery that
 * splits every base layer into a local `_base` (z0–7) and a hybrid `_hybrid`
 * (z7+) variant, suppresses native POIs, and repaints for the dark palette.
 */
class MapStylePatchSlice1Test {

    private fun style(vararg layers: String): String {
        val layerJson = layers.joinToString(",") { id ->
            if (id == "background") {
                """{"id":"background","type":"background","paint":{"background-color":"#ffffff"}}"""
            } else {
                """{"id":"$id","type":"fill","source":"x","paint":{"fill-color":"#aaaaaa","fill-opacity":0.5}}"""
            }
        }
        return """{"version":8,"sources":{"old":{}},"layers":[$layerJson]}"""
    }

    private fun layersOf(patched: String): List<String> =
        Json.parseToJsonElement(patched).jsonObject["layers"]!!.jsonArray.map {
            it.jsonObject["id"]!!.jsonPrimitive.content
        }

    @Test
    fun `every layer doubles into base and hybrid with split sources and zoom gates`() {
        val patched = patchStyleForHybrid(
            style("background", "water", "roads_major"),
            baseLocalUrl = "mbtiles://base",
            hybridUrl = "mbtiles://hybrid",
        )
        val root = Json.parseToJsonElement(patched).jsonObject
        val sources = root["sources"]!!.jsonObject
        assertEquals("mbtiles://base", sources["protomaps_base"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("mbtiles://hybrid", sources["protomaps_hybrid"]!!.jsonObject["url"]!!.jsonPrimitive.content)

        assertEquals(
            listOf("background", "water_base", "water_hybrid", "roads_major_base", "roads_major_hybrid"),
            layersOf(patched),
        )
        val byId = root["layers"]!!.jsonArray.associateBy { it.jsonObject["id"]!!.jsonPrimitive.content }
        val base = byId.getValue("water_base").jsonObject
        val hybrid = byId.getValue("water_hybrid").jsonObject
        assertEquals("protomaps_base", base["source"]!!.jsonPrimitive.content)
        assertEquals("protomaps_hybrid", hybrid["source"]!!.jsonPrimitive.content)
        assertEquals(7, base["maxzoom"]!!.jsonPrimitive.content.toInt())
        assertEquals(7, hybrid["minzoom"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `native pois are suppressed at runtime with their variants`() {
        val patched = patchStyleForHybrid(style("pois", "water"), "b", "h")
        assertEquals(listOf("water_base", "water_hybrid"), layersOf(patched))
    }

    @Test
    fun `dark mode repaints colours but preserves paint expressions`() {
        val patched = patchStyleForHybrid(style("water", "roads_major"), "b", "h", dark = true)
        val root = Json.parseToJsonElement(patched).jsonObject
        val byId = root["layers"]!!.jsonArray.associateBy { it.jsonObject["id"]!!.jsonPrimitive.content }
        val waterBase = byId.getValue("water_base").jsonObject
        val paint = waterBase["paint"]!!.jsonObject
        // Recoloured to the dark navy…
        assertEquals("#0d1b2a", paint["fill-color"]!!.jsonPrimitive.content)
        // …while non-colour keys survive verbatim.
        assertEquals(0.5, paint["fill-opacity"]!!.jsonPrimitive.content.toDouble())

        val roadsBase = byId.getValue("roads_major_base").jsonObject
        // Major roads (highway/major/link rule) land on the dark major grey.
        assertEquals("#464b54", roadsBase["paint"]!!.jsonObject["fill-color"]!!.jsonPrimitive.content)
    }

    @Test
    fun `light mode keeps the original paint untouched`() {
        val patched = patchStyleForHybrid(style("water"), "b", "h", dark = false)
        val paint = Json.parseToJsonElement(patched).jsonObject["layers"]!!
            .jsonArray.single { it.jsonObject["id"]!!.jsonPrimitive.content == "water_base" }
            .jsonObject["paint"]!!.jsonObject
        assertEquals("#aaaaaa", paint["fill-color"]!!.jsonPrimitive.content)
    }

    @Test
    fun `casing wins over highway in the dark colour match`() {
        // `roads_highway_casing_early` contains both tokens: the casing rule
        // must match first or the road draws as a surface.
        val patched = patchStyleForHybrid(style("roads_highway_casing_early"), "b", "h", dark = true)
        val base = Json.parseToJsonElement(patched).jsonObject["layers"]!!
            .jsonArray.single { it.jsonObject["id"]!!.jsonPrimitive.content.endsWith("_base") }
        assertEquals(
            "#111318",
            base.jsonObject["paint"]!!.jsonObject["fill-color"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `a style without layers still gets sources`() {
        val patched = patchStyleForHybrid("""{"version":8}""", "b", "h")
        val root = Json.parseToJsonElement(patched).jsonObject
        assertTrue(root["sources"]!!.jsonObject.containsKey("protomaps_base"))
        assertTrue(root["layers"]!!.jsonArray.isEmpty())
    }
}
