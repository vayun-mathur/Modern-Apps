package com.vayunmathur.weather.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [WxStatic] with a real on-disk cache directory (Robolectric cacheDir):
 * the first bundle writes the entry/tile/history/geo files, and after the
 * in-memory caches are reset a second bundle is served entirely from disk
 * (loadEntry, runTile, history and geo file reads) with zero fetches.
 */
@RunWith(AndroidJUnit4::class)
class WxStaticDiskCacheTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val requests = mutableListOf<String>()
    private val objects = HashMap<String, Pair<ByteArray, String>>()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun res(path: String): ByteArray? =
        javaClass.classLoader!!.getResourceAsStream("wx/$path")?.use { it.readBytes() }
    private fun gz(path: String): ByteArray = GZIPInputStream(res(path)!!.inputStream()).use { it.readBytes() }

    private val case = json.parseToJsonElement(res("cases.json")!!.decodeToString()).jsonArray[0].jsonObject
    private val lat = case["lat"]!!.jsonPrimitive.content.toDouble()
    private val lon = case["lon"]!!.jsonPrimitive.content.toDouble()
    private val now = case["now"]!!.jsonPrimitive.long

    @BeforeTest fun setUp() {
        WxStatic.resetForTest()
        WxStatic.enabled = true
        WxStatic.baseOverride = "https://r2.test/wx/v1/"
        // Start from an empty wx-static cache dir.
        File(ctx.cacheDir, "wx-static").deleteRecursively()
        WxStatic.init(ctx)
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

    @AfterTest fun tearDown() {
        WxStatic.resetForTest()
        WxStatic.baseOverride = null
        WxStatic.enabled = true
    }

    @Test fun secondBundleIsServedEntirelyFromDiskCache() = runBlocking {
        val first = WxStatic.bundle(lat, lon, now)
        assertNotNull(first.current)
        assertTrue(requests.isNotEmpty())
        val dir = File(ctx.cacheDir, "wx-static")
        // Entry, tile, history and geo files were written atomically.
        assertTrue(File(dir, "e").listFiles()?.isNotEmpty() == true, "entry files: ${dir.walkTopDown().map { it.name }.toList()}")
        assertTrue(File(dir, "t").listFiles()?.isNotEmpty() == true)
        assertTrue(File(dir, "h").listFiles()?.isNotEmpty() == true)
        assertTrue(File(dir, "g").listFiles()?.isNotEmpty() == true)
        assertTrue(File(dir, "g/header").exists())

        // Drop all in-memory state (as a process restart would), keep disk.
        WxStatic.resetForTest()
        WxStatic.baseOverride = "https://r2.test/wx/v1/"
        WxStatic.init(ctx)
        WxStatic.fetcher = { _, _ -> throw AssertionError("network must not be touched on a warm disk cache") }
        requests.clear()
        val second = WxStatic.bundle(lat, lon, now + 3600)
        assertNotNull(second.current)
        // Recomputed locally for the new hour from the disk-cached tile:
        // the current timestamp advances by exactly the elapsed hour.
        assertEquals(first.current!!.time + 3600, second.current!!.time)
        assertTrue(requests.isEmpty())
    }

    @Test fun corruptEntryFileIsIgnoredAndRefetched() = runBlocking {
        WxStatic.bundle(lat, lon, now)
        val entryFile = File(ctx.cacheDir, "wx-static/e").listFiles()!!.first()
        entryFile.writeBytes(ByteArray(4) { 0x7f }) // too short to decode
        WxStatic.resetForTest()
        WxStatic.baseOverride = "https://r2.test/wx/v1/"
        WxStatic.init(ctx)
        // Fetcher is still the fixture fetcher from setUp? No: resetForTest
        // does not touch fetcher, so ranged GETs work again.
        val before = requests.size
        val bundle = WxStatic.bundle(lat, lon, now + 3600)
        assertNotNull(bundle.current)
        assertTrue(requests.size > before, "corrupt entry must trigger a refetch")
    }
}
