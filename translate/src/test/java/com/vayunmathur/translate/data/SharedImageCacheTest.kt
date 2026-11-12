package com.vayunmathur.translate.data

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Copy, bound, sniff and prune rules of the Share / Open with image cache (#846). */
@RunWith(RobolectricTestRunner::class)
class SharedImageCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000_000_000_000L

    private fun cache(
        dir: File = File(tmp.root, SharedImageCache.DIR_NAME),
        maxBytes: Long = SharedImageCache.MAX_BYTES,
    ) = SharedImageCache(dir = dir, maxBytes = maxBytes, clock = { now })

    private val png = byteArrayOf(0x89.toByte(), 'P'.b, 'N'.b, 'G'.b, '\r'.b, '\n'.b, 0x1A, '\n'.b) +
        ByteArray(200) { it.toByte() }
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(64)

    // --- sniffing ---------------------------------------------------------------------

    @Test
    fun `sniffs supported image formats`() {
        assertEquals("png", SharedImageCache.sniffImageExtension(png))
        assertEquals("jpg", SharedImageCache.sniffImageExtension(jpeg))
        assertEquals("gif", SharedImageCache.sniffImageExtension("GIF89a......".toByteArray()))
        assertEquals("webp", SharedImageCache.sniffImageExtension("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()))
        assertEquals("heic", SharedImageCache.sniffImageExtension(ftyp("heic")))
        assertEquals("heic", SharedImageCache.sniffImageExtension(ftyp("mif1")))
        assertEquals("avif", SharedImageCache.sniffImageExtension(ftyp("avif")))
        assertEquals("bmp", SharedImageCache.sniffImageExtension("BM\u0000\u0000\u0000\u0000".toByteArray()))
    }

    @Test
    fun `rejects non images and truncated headers`() {
        assertNull(SharedImageCache.sniffImageExtension("%PDF-1.7\n".toByteArray()))
        assertNull(SharedImageCache.sniffImageExtension("hello world, plain text".toByteArray()))
        assertNull(SharedImageCache.sniffImageExtension(ftyp("isom"))) // MP4 video
        assertNull(SharedImageCache.sniffImageExtension(ByteArray(0)))
        // Header shorter than the signature: the length bound wins over the array size.
        assertNull(SharedImageCache.sniffImageExtension(jpeg, length = 2))
    }

    // --- store ------------------------------------------------------------------------

    @Test
    fun `stores an image byte for byte with its sniffed extension`() {
        val result = cache().store(ByteArrayInputStream(png))
        val cached = assertIs<SharedImageCache.Result.Cached>(result)
        assertTrue(cached.file.name.endsWith(".png"))
        assertEquals(SharedImageCache.DIR_NAME, cached.file.parentFile?.name)
        assertContentEquals(png, cached.file.readBytes())
    }

    @Test
    fun `stores an image delivered in short reads`() {
        val result = cache().store(TrickleStream(jpeg))
        val cached = assertIs<SharedImageCache.Result.Cached>(result)
        assertContentEquals(jpeg, cached.file.readBytes())
    }

    @Test
    fun `rejects a non image without writing anything`() {
        val dir = File(tmp.root, "nonimage")
        val result = cache(dir).store(ByteArrayInputStream("not an image at all".toByteArray()))
        assertEquals(SharedImageCache.Result.NotAnImage, result)
        assertFalse(dir.exists() && dir.list().orEmpty().isNotEmpty())
    }

    @Test
    fun `rejects an image over the size bound and leaves no partial file`() {
        val dir = File(tmp.root, "big")
        val big = png + ByteArray(1_000)
        assertEquals(SharedImageCache.Result.TooLarge, cache(dir, maxBytes = 500).store(ByteArrayInputStream(big)))
        assertTrue(dir.list().orEmpty().isEmpty())
        // Exactly at the bound is still accepted.
        val exact = png + ByteArray(500 - png.size)
        assertIs<SharedImageCache.Result.Cached>(cache(dir, maxBytes = 500).store(ByteArrayInputStream(exact)))
    }

    @Test
    fun `a failing stream is unreadable and leaves no partial file`() {
        val dir = File(tmp.root, "failing")
        val failing = object : InputStream() {
            private var served = 0
            override fun read(): Int {
                if (served >= png.size) throw IOException("grant revoked")
                return png[served++].toInt() and 0xFF
            }
        }
        assertEquals(SharedImageCache.Result.Unreadable, cache(dir).store(failing))
        assertTrue(dir.list().orEmpty().isEmpty())
    }

    @Test
    fun `two stores in the same millisecond get distinct files`() {
        val a = assertIs<SharedImageCache.Result.Cached>(cache().store(ByteArrayInputStream(png))).file
        val b = assertIs<SharedImageCache.Result.Cached>(cache().store(ByteArrayInputStream(jpeg))).file
        assertTrue(a != b)
        assertTrue(a.exists() && b.exists())
    }

    // --- cleanup ----------------------------------------------------------------------

    @Test
    fun `keeps only the newest copies`() {
        val c = cache()
        val files = (0 until SharedImageCache.MAX_FILES + 3).map {
            now += 1_000
            assertIs<SharedImageCache.Result.Cached>(c.store(ByteArrayInputStream(png))).file
        }
        val survivors = files.filter { it.exists() }
        assertEquals(files.takeLast(SharedImageCache.MAX_FILES), survivors)
    }

    @Test
    fun `drops copies older than a day but never the one just stored`() {
        val c = cache()
        val old = assertIs<SharedImageCache.Result.Cached>(c.store(ByteArrayInputStream(png))).file
        now += SharedImageCache.MAX_AGE_MS + 1
        val fresh = assertIs<SharedImageCache.Result.Cached>(c.store(ByteArrayInputStream(jpeg))).file
        assertFalse(old.exists())
        assertTrue(fresh.exists())
        // A copy named as keep survives cleanup even once stale; without it, it goes.
        now += SharedImageCache.MAX_AGE_MS * 2
        c.cleanUp(keep = fresh)
        assertTrue(fresh.exists())
        c.cleanUp()
        assertFalse(fresh.exists())
    }

    @Test
    fun `cleanup removes leftover partial copies and ignores foreign files`() {
        val dir = File(tmp.root, SharedImageCache.DIR_NAME).apply { mkdirs() }
        val part = File(dir, "shared-1.png.part").apply { writeBytes(png) }
        val foreign = File(dir, "notes.txt").apply { writeText("keep me") }
        cache(dir).cleanUp()
        assertFalse(part.exists())
        assertTrue(foreign.exists())
    }

    @Test
    fun `cleanup on a missing directory is a no-op`() {
        cache(File(tmp.root, "absent")).cleanUp()
    }

    @Test
    fun `inCacheDir nests under the given cache dir`() {
        val result = SharedImageCache.inCacheDir(tmp.root).store(ByteArrayInputStream(png))
        val cached = assertIs<SharedImageCache.Result.Cached>(result)
        assertEquals(File(tmp.root, SharedImageCache.DIR_NAME), cached.file.parentFile)
    }

    private fun ftyp(brand: String): ByteArray =
        byteArrayOf(0, 0, 0, 0x18) + "ftyp$brand".toByteArray() + ByteArray(8)

    private val Char.b: Byte get() = code.toByte()

    /** Serves at most three bytes per read, like a slow provider pipe. */
    private class TrickleStream(private val bytes: ByteArray) : InputStream() {
        private var pos = 0
        override fun read(): Int = if (pos < bytes.size) bytes[pos++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= bytes.size) return -1
            val n = minOf(len, 3, bytes.size - pos)
            System.arraycopy(bytes, pos, b, off, n)
            pos += n
            return n
        }
    }
}
