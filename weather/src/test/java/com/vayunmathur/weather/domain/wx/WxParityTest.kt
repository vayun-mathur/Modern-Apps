package com.vayunmathur.weather.domain.wx

import com.vayunmathur.weather.network.WxBundle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.zip.GZIPInputStream

/**
 * App vs server parity for the static R2 weather path.
 *
 * Fixtures (`src/test/resources/wx/`, gzipped) come from the server repo's
 * `server wx fixtures`: for each (point, now) the server's own `/wx/v1/bundle`
 * JSON (its real fetch path over the same Open-Meteo chunk files), plus mini
 * `WXP1` packs (run.wxp, hist/<day>.wxp, geo.wxp) laid out exactly like the
 * production objects but holding only the fixtures' tiles. The server already
 * checked its Rust pack/tile path against that JSON; this reads the packs the
 * way [com.vayunmathur.weather.network.WxStatic] does (entry → spans) and
 * checks the Kotlin port produces the same bundle, value for value.
 */
class WxParityTest {
    // encodeDefaults: the server always writes v/model/step; a missing key
    // and an explicit null compare equal below (serde omits None sections).
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun res(path: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("wx/$path")?.use { it.readBytes() }
            ?: error("missing fixture wx/$path")

    private fun gz(path: String): ByteArray = GZIPInputStream(res(path).inputStream()).use { it.readBytes() }

    private val packs = HashMap<String, ByteArray>()
    private fun pack(name: String) = packs.getOrPut(name) { gz("$name.gz") }
    private fun slice(name: String, off: Long, len: Int) = pack(name).copyOfRange(off.toInt(), (off + len).toInt())
    private fun slice(name: String, s: WxPack.Span) = slice(name, s.off, s.len)

    private fun isNumber(e: JsonElement) = e is JsonPrimitive && e !is JsonNull && !e.isString

    /** First difference between two JSON trees (numbers compared as doubles). */
    private fun diff(path: String, a: JsonElement, b: JsonElement): String? = when {
        a is JsonObject && b is JsonObject -> (a.keys + b.keys).firstNotNullOfOrNull { k ->
            diff("$path.$k", a[k] ?: JsonNull, b[k] ?: JsonNull)
        }
        a is JsonArray && b is JsonArray -> diffArrays(path, a, b)
        isNumber(a) && isNumber(b) ->
            if (a.jsonPrimitive.doubleOrNull == b.jsonPrimitive.doubleOrNull) null else "$path: $a != $b"
        else -> if (a == b) null else "$path: $a != $b"
    }

    private fun diffArrays(path: String, a: JsonArray, b: JsonArray): String? =
        if (a.size != b.size) "$path: len ${a.size} != ${b.size}"
        else a.indices.firstNotNullOfOrNull { k -> diff("$path[$k]", a[k], b[k]) }

    /** The bundle for a case, read through the packs like WxStatic. */
    private fun bundleFromPacks(lat: Double, lon: Double, now: Long): WxBundle {
        val (ty, tx) = WxGrid.tileOf(lat, lon, WxPack.NY, WxPack.NX, WxPack.CELLS)
        val e = WxPack.decodeRunEntry(slice("run.wxp", WxPack.runEntryOffset(ty, tx), WxPack.RUN_ENTRY_LEN))
        assertEquals(ty to tx, e.ty to e.tx)
        val tile = WxTiles.decodeTile(slice("run.wxp", e.tile))
        val hist = WxGrid.histDaysNeeded(now, e.histToDay * 24L).map { d ->
            WxTiles.decodeTile(slice("hist/$d.wxp", e.histSpan(d)!!))
        }
        val k = WxGrid.geoOf(lat, lon)!!
        val h = WxPack.decodeHeader(slice("geo.wxp", 0, WxPack.HEADER_LEN.toInt()))
        val at = h.entryOffset(k.y * 4 + k.b / 4, k.x * 4 + k.b % 4)!!
        val geo = WxTiles.decodeGeo(slice("geo.wxp", WxPack.decodeSpan(slice("geo.wxp", at, 8))))
        val series = WxGrid.seriesFromTile(tile, hist, geo, lat, lon, now)
        return WxCompute.computeBundle(lat, lon, now, tile.iconRunIso, series)
    }

    @Test
    fun `every fixture bundle computed on device equals the server bundle`() {
        val cases = json.parseToJsonElement(res("cases.json").decodeToString()).jsonArray
        assertTrue("fixtures present", cases.size >= 30)
        val failures = mutableListOf<String>()
        for (c in cases) {
            val o = c.jsonObject
            val lat = o["lat"]!!.jsonPrimitive.content.toDouble()
            val lon = o["lon"]!!.jsonPrimitive.content.toDouble()
            val now = o["now"]!!.jsonPrimitive.long
            val expected = json.parseToJsonElement(gz(o["expected"]!!.jsonPrimitive.content).decodeToString())
            val ours = json.encodeToJsonElement(WxBundle.serializer(), bundleFromPacks(lat, lon, now))
            diff("", expected, ours)?.let { failures += "$lat,$lon @$now: $it" }
        }
        if (failures.isNotEmpty()) fail("${failures.size}/${cases.size} differ:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `grid math matches the server anchors`() {
        // Berlin: ICON cell (1140, 1547), z10 tile (550, 335).
        assertEquals(1140 to 1547, WxGrid.cellIndex(WxGrid.ICON, 52.52, 13.41, 1441, 2879))
        val k = WxGrid.geoOf(52.52, 13.41)!!
        assertEquals(550 to 335, k.x to k.y)
        // Points past ICON's last column wrap into tile 0 (server: i mod 2879).
        assertEquals(0, WxGrid.tileOf(0.0, 179.99, 1441, 2879, 8).second)
        assertEquals(720L to 2880L, WxGrid.cellNode(0.0, 179.99))
        assertEquals(null, WxGrid.geoOf(-89.0, 0.0))
        // Run-pack entry addressing (server pack::Header::entry_offset).
        assertEquals(64L, WxPack.runEntryOffset(0, 0))
        assertEquals(64L + (142L * 360 + 193) * 120, WxPack.runEntryOffset(142, 193))
    }

    @Test
    fun `rust rounding semantics`() {
        assertEquals(-3.0, WxMath.rround(-2.5), 0.0)
        assertEquals(3.0, WxMath.rround(2.5), 0.0)
        assertEquals(0.0, WxMath.rround(0.49999999999999994), 0.0)
        assertEquals(1.0 / Double.NEGATIVE_INFINITY, WxMath.rround(-0.4), 0.0) // −0.0
        assertEquals(0.01, WxMath.round2(0.005), 0.0)
        assertEquals(359.5, WxMath.remEuclid(-0.5, 360.0), 0.0)
        assertEquals(2.0, WxMath.fmax(Double.NaN, 2.0), 0.0)
    }
}
