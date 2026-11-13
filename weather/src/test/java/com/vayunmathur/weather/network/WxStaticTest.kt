package com.vayunmathur.weather.network

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

/**
 * [WxStatic] request behaviour against the fixture objects (no network):
 * the first bundle costs latest.json + tile + the finished-day history
 * objects + geo block, later refreshes of the same run cost nothing until
 * `next_check`, a missing tile is a clean
 * [WxStatic.NotCovered] (→ API fallback) and is not re-requested.
 */
class WxStaticTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val requests = mutableListOf<String>()

    private fun res(path: String): ByteArray? =
        javaClass.classLoader!!.getResourceAsStream("wx/$path")?.use { it.readBytes() }

    @Before
    fun setUp() {
        WxStatic.resetForTest()
        WxStatic.baseOverride = "https://r2.test/wx/v1/"
        WxStatic.fetcher = { url ->
            requests += url.removePrefix("https://r2.test/wx/v1/")
            val body = res(url.removePrefix("https://r2.test/wx/v1/"))
            if (body == null) 404 to ByteArray(0) else 200 to body
        }
    }

    @After
    fun tearDown() {
        WxStatic.resetForTest()
        WxStatic.baseOverride = null
        WxStatic.fetcher = { url -> com.vayunmathur.library.network.NetworkClient.performRequestBytes(url) }
    }

    @Test
    fun `refresh costs one manifest, one tile and one geo block, then nothing`() = runBlocking {
        val case = json.parseToJsonElement(res("cases.json")!!.decodeToString()).jsonArray[0].jsonObject
        val lat = case["lat"]!!.jsonPrimitive.content.toDouble()
        val lon = case["lon"]!!.jsonPrimitive.content.toDouble()
        val now = case["now"]!!.jsonPrimitive.long
        val b = WxStatic.bundle(lat, lon, now)
        assertTrue(b.hourly != null && b.current != null)
        val hist = case["hist"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("first fetch needs the finished days", hist.size >= 7)
        assertEquals(
            listOf("latest.json", case["tile"]!!.jsonPrimitive.content) + hist + case["geo"]!!.jsonPrimitive.content,
            requests,
        )
        val first = requests.size
        // Same run, an hour later: computed locally, zero requests.
        WxStatic.bundle(lat, lon, now + 3600)
        assertEquals(first, requests.size)
        // Past next_check the manifest is re-read (once), tiles stay cached.
        val latest = json.parseToJsonElement(res("latest.json")!!.decodeToString()).jsonObject
        val next = latest["next_check"]!!.jsonPrimitive.long
        WxStatic.bundle(lat, lon, maxOf(next, now) + 20 * 60)
        assertEquals(listOf("latest.json"), requests.drop(first))
    }

    @Test
    fun `an ocean point without a tile is NotCovered and not refetched`() = runBlocking {
        val first = json.parseToJsonElement(res("cases.json")!!.decodeToString()).jsonArray[0].jsonObject
        val now = first["now"]!!.jsonPrimitive.long
        repeat(2) {
            try {
                WxStatic.bundle(-40.0, -130.0, now) // South Pacific
                fail("expected NotCovered")
            } catch (_: WxStatic.NotCovered) {
            }
        }
        assertEquals(1, requests.count { it.endsWith(".wxt") })
    }
}
