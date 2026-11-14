package com.vayunmathur.weather.network

import com.vayunmathur.weather.domain.wx.WxGrid
import com.vayunmathur.weather.domain.wx.WxPack
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.zip.GZIPInputStream

/**
 * [WxStatic] request behaviour against the fixture packs served by a fake
 * R2 (Range + If-Match + ETag, no network): the first bundle costs entry +
 * tile + the finished days + the geo block (header, entry, block); the same
 * run later costs nothing until `next_check`, then one entry GET; a new run
 * costs entry + tile; a run replaced between the two GETs is caught by
 * If-Match; an ocean tile is a clean [WxStatic.NotCovered] (→ API fallback)
 * and is not re-checked for 6 h.
 */
class WxStaticTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val requests = mutableListOf<String>()
    private val objects = HashMap<String, Pair<ByteArray, String>>() // key → (bytes, etag)

    private fun res(path: String): ByteArray? =
        javaClass.classLoader!!.getResourceAsStream("wx/$path")?.use { it.readBytes() }

    private fun gz(path: String): ByteArray = GZIPInputStream(res(path)!!.inputStream()).use { it.readBytes() }

    private val case = json.parseToJsonElement(res("cases.json")!!.decodeToString()).jsonArray[0].jsonObject
    private val lat = case["lat"]!!.jsonPrimitive.content.toDouble()
    private val lon = case["lon"]!!.jsonPrimitive.content.toDouble()
    private val now = case["now"]!!.jsonPrimitive.long

    @Before
    fun setUp() {
        WxStatic.resetForTest()
        WxStatic.baseOverride = "https://r2.test/wx/v1/"
        objects["run.wxp"] = gz("run.wxp.gz") to "\"run1\""
        objects["geo.wxp"] = gz("geo.wxp.gz") to "\"geo\""
        for (d in 20762..20769) objects["hist/$d.wxp"] = gz("hist/$d.wxp.gz") to "\"h$d\""
        WxStatic.fetcher = { url, headers ->
            val key = url.removePrefix("https://r2.test/wx/v1/")
            val obj = objects[key]
            val (a, b) = headers.getValue("Range").removePrefix("bytes=").split('-').map { it.toLong() }
            requests += "$key@$a"
            when {
                obj == null -> WxStatic.Resp(404, emptyMap(), ByteArray(0))
                headers["If-Match"]?.let { it != obj.second } == true -> WxStatic.Resp(412, emptyMap(), ByteArray(0))
                else -> WxStatic.Resp(206, mapOf("etag" to obj.second), obj.first.copyOfRange(a.toInt(), b.toInt() + 1))
            }
        }
    }

    @After
    fun tearDown() {
        WxStatic.resetForTest()
        WxStatic.baseOverride = null
    }

    private fun entryAt(): Long {
        val (ty, tx) = WxGrid.tileOf(lat, lon, WxPack.NY, WxPack.NX, WxPack.CELLS)
        return WxPack.runEntryOffset(ty, tx)
    }

    private fun entry(): WxPack.RunEntry {
        val at = entryAt().toInt()
        return WxPack.decodeRunEntry(objects.getValue("run.wxp").first.copyOfRange(at, at + WxPack.RUN_ENTRY_LEN))
    }

    /** A "new run": same tiles, the entry's gen bumped, a new ETag. */
    private fun publishNewRun(etag: String) {
        val b = objects.getValue("run.wxp").first.copyOf()
        b[entryAt().toInt()] = (b[entryAt().toInt()] + 1).toByte()
        objects["run.wxp"] = b to etag
    }

    @Test
    fun `a refresh is zero, one or two ranged GETs`() = runBlocking {
        val b = WxStatic.bundle(lat, lon, now)
        assertTrue(b.hourly != null && b.current != null)
        val e = entry()
        val days = WxGrid.histDaysNeeded(now, e.histToDay * 24L).toList()
        assertTrue("first fetch needs the finished days", days.size >= 7)
        val expected = listOf("run.wxp@${entryAt()}", "run.wxp@${e.tile.off}") +
            days.map { d -> "hist/$d.wxp@${e.histSpan(d)!!.off}" } + listOf("geo.wxp@0")
        assertEquals(expected, requests.take(3 + days.size))
        assertEquals("geo entry + block", 2, requests.size - (3 + days.size))
        val first = requests.size
        // Same run, an hour later: computed locally, zero requests.
        WxStatic.bundle(lat, lon, now + 3600)
        assertEquals(first, requests.size)
        // Past next_check, no new run: one entry GET.
        val t = maxOf(e.nextCheck, now) + 20 * 60
        WxStatic.bundle(lat, lon, t)
        assertEquals(listOf("run.wxp@${entryAt()}"), requests.drop(first))
        // A new run: entry + tile.
        publishNewRun("\"run2\"")
        WxStatic.bundle(lat, lon, t + ENTRY_MAX_AGE)
        assertEquals(listOf("run.wxp@${entryAt()}", "run.wxp@${e.tile.off}"), requests.drop(first + 1))
    }

    @Test
    fun `a run replaced between entry and tile is caught by If-Match`() = runBlocking {
        val real = WxStatic.fetcher
        var swapped = false
        WxStatic.fetcher = { url, headers ->
            val r = real(url, headers)
            if (!swapped && url.endsWith("run.wxp")) {
                swapped = true
                publishNewRun("\"run2\"") // lands right after the entry read
            }
            r
        }
        assertTrue(WxStatic.bundle(lat, lon, now).current != null)
        assertEquals("entry, tile (412), entry, tile", 4, requests.count { it.startsWith("run.wxp@") })
    }

    @Test
    fun `an ocean tile is NotCovered and not re-checked for hours`() = runBlocking {
        repeat(2) {
            try {
                WxStatic.bundle(-40.0, -130.0, now) // South Pacific: zero entry
                fail("expected NotCovered")
            } catch (_: WxStatic.NotCovered) {
            }
        }
        assertEquals(1, requests.count { it.startsWith("run.wxp@") })
    }

    private companion object {
        const val ENTRY_MAX_AGE = 12 * 3600L
    }
}
