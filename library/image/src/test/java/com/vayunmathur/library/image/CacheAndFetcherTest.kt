package com.vayunmathur.library.image

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.image.fetchers.AssetFetcher
import com.vayunmathur.library.image.fetchers.BitmapFetcher
import com.vayunmathur.library.image.fetchers.ByteArrayFetcher
import com.vayunmathur.library.image.fetchers.ContentResolverFetcher
import com.vayunmathur.library.image.fetchers.FetchResult
import com.vayunmathur.library.image.fetchers.FileFetcher
import com.vayunmathur.library.image.util.ensureExists
import com.vayunmathur.library.image.util.sha256
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class CacheAndFetcherTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private fun bitmap() = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)

    @Test fun `sha256 matches known vectors`() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", "".sha256())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "abc".sha256())
    }

    @Test fun `memory cache put get remove clear and eviction by bytes`() {
        val cache = MemoryCache.Builder().build()
        val bmp = bitmap()
        assertNull(cache.get("k"))
        cache.put("k", bmp)
        assertEquals(bmp, cache.get("k"))
        cache.remove("k"); assertNull(cache.get("k"))
        cache.put("a", bmp); cache.clear(); assertNull(cache.get("a"))
        assertEquals(10 * 1024 * 1024, MemoryCache.MAX_BYTES)
    }

    @Test fun `disk cache round trip, overwrite, remove, clear`() {
        val dir = File(ctx.cacheDir, "disk-test-${System.nanoTime()}").ensureExists()
        val cache = DiskCache(dir, 1024 * 1024)
        assertNull(cache.get("missing"))
        cache.put("k1", byteArrayOf(1, 2, 3))
        assertTrue(cache.get("k1")!!.contentEquals(byteArrayOf(1, 2, 3)))
        cache.put("k1", byteArrayOf(9, 9))
        assertTrue(cache.get("k1")!!.contentEquals(byteArrayOf(9, 9)))
        cache.put("k2", ByteArray(10) { it.toByte() })
        assertEquals(10, cache.get("k2")!!.size)
        cache.remove("k1"); assertNull(cache.get("k1"))
        cache.remove("missing") // no-op
        cache.clear(); assertNull(cache.get("k2"))
        dir.deleteRecursively()
    }

    @Test fun `disk cache trims oldest entries over the byte limit`() {
        val dir = File(ctx.cacheDir, "disk-trim-${System.nanoTime()}").ensureExists()
        val cache = DiskCache(dir, 100)
        cache.put("old", ByteArray(60))
        Thread.sleep(5)
        dir.listFiles()!!.first().setLastModified(1000)
        cache.put("new", ByteArray(60)) // 120 > 100 triggers trim of the oldest
        val total = dir.listFiles()!!.sumOf { it.length() }
        assertTrue(total <= 100, "expected trimmed to <=100 bytes, was $total")
        assertTrue(cache.get("new") != null)
        dir.deleteRecursively()
    }

    @Test fun `disk cache builder requires a directory`() {
        assertFailsWith<IllegalStateException> { DiskCache.Builder().build() }
        val dir = File(ctx.cacheDir, "disk-builder-${System.nanoTime()}")
        assertTrue(DiskCache.Builder().directory(dir).build().get("x") == null)
        assertTrue(dir.exists())
        dir.deleteRecursively()
    }

    @Test fun `byte array and bitmap fetchers only handle their types`(): Unit = runBlocking {
        val bytes = byteArrayOf(1, 2)
        assertTrue((ByteArrayFetcher().fetch(bytes, ctx) as FetchResult.Bytes).bytes.contentEquals(bytes))
        assertNull(ByteArrayFetcher().fetch("text", ctx))
        val bmp = bitmap()
        assertEquals(bmp, (BitmapFetcher().fetch(bmp, ctx) as FetchResult.BitmapResult).bitmap)
        assertNull(BitmapFetcher().fetch(bytes, ctx))
    }

    @Test fun `file fetcher reads files by file, uri and path, rejects others`(): Unit = runBlocking {
        val file = File(ctx.cacheDir, "fetch-${System.nanoTime()}.bin").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        assertTrue((FileFetcher().fetch(file, ctx) as FetchResult.Bytes).bytes.contentEquals(byteArrayOf(7, 8, 9)))
        assertTrue((FileFetcher().fetch(Uri.fromFile(file), ctx) as FetchResult.Bytes).bytes.contentEquals(byteArrayOf(7, 8, 9)))
        assertTrue((FileFetcher().fetch("file://${file.absolutePath}", ctx) as FetchResult.Bytes).bytes.contentEquals(byteArrayOf(7, 8, 9)))
        assertTrue((FileFetcher().fetch(file.absolutePath, ctx) as FetchResult.Bytes).bytes.contentEquals(byteArrayOf(7, 8, 9)))
        assertNull(FileFetcher().fetch(File(ctx.cacheDir, "missing.bin"), ctx))
        assertNull(FileFetcher().fetch("https://example.org/x.png", ctx))
        assertNull(FileFetcher().fetch("relative/path.png", ctx))
        assertNull(FileFetcher().fetch(42, ctx))
        file.delete()
    }

    @Test fun `asset and content fetchers gate on scheme and path`(): Unit = runBlocking {
        assertNull(AssetFetcher().fetch("file:///android_asset/missing.png", ctx))
        assertNull(AssetFetcher().fetch("file:///android_asset/", ctx))
        assertNull(AssetFetcher().fetch("https://example.org/a.png", ctx))
        assertNull(AssetFetcher().fetch(42, ctx))
        val content = ContentResolverFetcher().fetch("content://media/external/images/1", ctx)
        assertTrue(content is FetchResult.Source)
        assertNull(ContentResolverFetcher().fetch("file:///x.png", ctx))
        assertNull(ContentResolverFetcher().fetch(42, ctx))
    }

    @Test fun `size and ensureExists helpers`() {
        assertTrue(Size.Original.isOriginal())
        assertTrue(Size(-1, 100).isOriginal())
        assertTrue(!Size(100, 100).isOriginal())
        assertEquals(Size(50, 50), Size(50))
        val dir = File(ctx.cacheDir, "ensure-${System.nanoTime()}/nested")
        assertEquals(dir, dir.ensureExists())
        assertTrue(dir.exists())
        dir.deleteRecursively()
    }
}
