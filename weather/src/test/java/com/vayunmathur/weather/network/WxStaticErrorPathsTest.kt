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
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * [WxStatic] failure and cache-policy branches the happy-path fixture test
 * does not reach: disabled, HTTP error mapping, short bodies, layout
 * changes, off-grid points, missing history, and entry re-check timing.
 */
class WxStaticErrorPathsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val requests = mutableListOf<String>()
    private val objects = HashMap<String, Pair<ByteArray, String>>()

    private fun res(path: String): ByteArray? =
        javaClass.classLoader!!.getResourceAsStream("wx/$path")?.use { it.readBytes() }
    private fun gz(path: String): ByteArray = GZIPInputStream(res(path)!!.inputStream()).use { it.readBytes() }

    private val case = json.parseToJsonElement(res("cases.json")!!.decodeToString()).jsonArray[0].jsonObject
    private val lat = case["lat"]!!.jsonPrimitive.content.toDouble()
    private val lon = case["lon"]!!.jsonPrimitive.content.toDouble()
    private val now = case["now"]!!.jsonPrimitive.long

    @Before fun setUp() {
        WxStatic.resetForTest()
        WxStatic.enabled = true
        WxStatic.baseOverride = "https://r2.test/wx/v1/"
        objects["run.wxp"] = gz("run.wxp.gz") to "\"run1\""
        objects["geo.wxp"] = gz("geo.wxp.gz") to "\"geo\""
        for (d in 20762..20769) objects["hist/$d.wxp"] = gz("hist/$d.wxp.gz") to "\"h$d\""
        installFetcher()
    }

    @After fun tearDown() {
        WxStatic.resetForTest()
        WxStatic.baseOverride = null
        WxStatic.enabled = true
    }

    private fun installFetcher() {
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

    private fun entryOffset(): Int {
        val (ty, tx) = WxGrid.tileOf(lat, lon, WxPack.NY, WxPack.NX, WxPack.CELLS)
        return WxPack.runEntryOffset(ty, tx).toInt()
    }

    private inline fun <reified T : Throwable> expectBundleFails(lat: Double, lon: Double, at: Long): T {
        try {
            runBlocking { WxStatic.bundle(lat, lon, at) }
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("expected ${T::class.simpleName}, got $e", e)
        }
        fail("expected ${T::class.simpleName}")
        throw AssertionError("unreachable")
    }

    @Test fun `disabled is NotCovered without any request`() {
        WxStatic.enabled = false
        val e = expectBundleFails<WxStatic.NotCovered>(lat, lon, now)
        assertEquals("disabled", e.message)
        assertTrue(requests.isEmpty())
    }

    @Test fun `off the DEM grid is NotCovered without any request`() {
        // Far south beyond the DEM grid: geoOf returns null before any fetch.
        expectBundleFails<WxStatic.NotCovered>(-89.0, 0.0, now)
        assertTrue(requests.isEmpty())
    }

    @Test fun `missing run pack is NotCovered`() {
        objects.remove("run.wxp")
        expectBundleFails<WxStatic.NotCovered>(lat, lon, now)
        assertEquals(1, requests.size)
    }

    @Test fun `forbidden run pack is NotCovered`() {
        WxStatic.fetcher = { _, _ -> WxStatic.Resp(403, emptyMap(), ByteArray(0)) }
        expectBundleFails<WxStatic.NotCovered>(lat, lon, now)
    }

    @Test fun `server error on entry is IOException without cached entry`() {
        WxStatic.fetcher = { _, _ -> WxStatic.Resp(500, emptyMap(), ByteArray(0)) }
        val e = expectBundleFails<IOException>(lat, lon, now)
        assertTrue(e.message!!.contains("HTTP 500"))
    }

    @Test fun `short ranged body is IOException`() {
        WxStatic.fetcher = { _, _ -> WxStatic.Resp(206, mapOf("etag" to "\"x\""), ByteArray(3)) }
        val e = expectBundleFails<IOException>(lat, lon, now)
        assertTrue(e.message!!.contains("bytes, want"))
    }

    @Test fun `fetcher IOException propagates when nothing is cached`() {
        WxStatic.fetcher = { _, _ -> throw IOException("offline") }
        val e = expectBundleFails<IOException>(lat, lon, now)
        assertEquals("offline", e.message)
    }

    @Test fun `entry for a different tile is a layout change`() {
        // Rewrite the entry's ty field so decodeRunEntry disagrees with the
        // requested tile: bundle must refuse rather than serve wrong data.
        val b = objects.getValue("run.wxp").first.copyOf()
        val at = entryOffset()
        b[at + 24] = (b[at + 24] + 1).toByte() // ty low byte
        objects["run.wxp"] = b to "\"run1\""
        val e = expectBundleFails<WxStatic.NotCovered>(lat, lon, now)
        assertEquals("run pack layout changed", e.message)
    }

    @Test fun `missing geo pack fails after tile fetch`() {
        objects.remove("geo.wxp")
        // Entry+tile+history succeed; the geo header GET 404s -> NotCovered.
        expectBundleFails<WxStatic.NotCovered>(lat, lon, now)
        assertTrue(requests.any { it.startsWith("geo.wxp@") })
    }

    @Test fun `missing history day is NotCovered`() {
        // Drop every history object; the first needed day's span exists in
        // the entry but the object itself is gone (404 -> NotCovered).
        for (d in 20762..20769) objects.remove("hist/$d.wxp")
        expectBundleFails<WxStatic.NotCovered>(lat, lon, now)
        assertTrue(requests.any { it.startsWith("hist/") })
    }

    @Test fun `cached entry survives a later entry-fetch IOException`() = runBlocking {
        WxStatic.bundle(lat, lon, now)
        val first = requests.size
        // Move past next_check so an entry re-check is due, then go offline:
        // the cached entry/tile/history/geo must serve the bundle again.
        val real = WxStatic.fetcher
        var offline = false
        WxStatic.fetcher = { url, headers -> if (offline) throw IOException("offline") else real(url, headers) }
        offline = true
        val entry = WxPack.decodeRunEntry(
            objects.getValue("run.wxp").first.copyOfRange(entryOffset(), entryOffset() + WxPack.RUN_ENTRY_LEN)
        )
        val t = maxOf(entry.nextCheck, now) + 20 * 60
        val b = WxStatic.bundle(lat, lon, t)
        assertTrue(b.current != null)
        // While offline the real fetcher is never reached, so no new
        // ranged requests are recorded; the bundle came from cache.
        assertEquals(first, requests.size)
    }

    @Test fun `poll minimum suppresses recheck shortly after next check`() = runBlocking {
        val entry = WxPack.decodeRunEntry(
            objects.getValue("run.wxp").first.copyOfRange(entryOffset(), entryOffset() + WxPack.RUN_ENTRY_LEN)
        )
        // First fetch happens just after next_check, so a second call a
        // minute later is due (past next_check) but inside POLL_MIN_SECS
        // of the fetch and must reuse the cached entry.
        val t0 = maxOf(entry.nextCheck, now) + 60
        WxStatic.bundle(lat, lon, t0)
        val first = requests.size
        WxStatic.bundle(lat, lon, t0 + 60)
        assertEquals(first, requests.size)
    }
}
