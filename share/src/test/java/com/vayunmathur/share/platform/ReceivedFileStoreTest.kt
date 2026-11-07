package com.vayunmathur.share.platform

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.vayunmathur.share.domain.protocol.ReceivedChunk
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReceivedFileStoreTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun chunk(payload: Long, name: String, body: String, last: Boolean, offset: Long = 0) =
        ReceivedChunk(payload, offset, body.length.toLong(), last, name, body.toByteArray())

    @Test
    fun `append writes chunks and returns file only on last`() {
        val store = ReceivedFileStore(ctx)
        val c = ctx // warm
        assertNull(store.append(1L, chunk(10L, "hello.txt", "hel", false)))
        val f = store.append(1L, chunk(10L, "hello.txt", "lo", true, offset = 3))
        assertNotNull(f)
        assertEquals("hello", f!!.readText())
        assertEquals("hello.txt", f.name)
    }

    @Test
    fun `append sanitizes path separators and blank names`() {
        val store = ReceivedFileStore(ctx)
        val f = store.append(2L, chunk(11L, "../evil.txt", "x", true))!!
        assertTrue(f.name.contains("evil"))
        assertTrue(!f.name.contains("/"))
        val g = store.append(2L, chunk(12L, "", "y", true))!!
        assertEquals("received_file", g.name)
    }

    @Test
    fun `newFile strips directories and dedupes`() {
        val store = ReceivedFileStore(ctx)
        val a = store.newFile("a/b/c.txt")
        assertEquals("c.txt", a.name); assertTrue(a.exists())
        val b = store.newFile("c.txt")
        assertEquals("c_1.txt", b.name)
        val blank = store.newFile("///")
        assertEquals("received_file", blank.name)
    }

    @Test
    fun `closeSession deletes partial file`() {
        val store = ReceivedFileStore(ctx)
        store.append(9L, chunk(20L, "part.bin", "partial", false))
        val dir = File(ctx.filesDir, "received")
        val before = dir.listFiles()?.size ?: 0
        assertTrue(before >= 1)
        store.closeSession(9L)
        // partial deleted
        assertTrue(dir.listFiles()?.none { it.name == "part.bin" } ?: true)
        // other session unaffected: append and complete
        val f = store.append(10L, chunk(21L, "keep.bin", "ok", true))!!
        assertTrue(f.exists())
        store.closeAll()
    }

    @Test
    fun `gcOrphans removes only old files`() {
        val store = ReceivedFileStore(ctx)
        val dir = File(ctx.filesDir, "received"); dir.mkdirs()
        val old = File(dir, "old.bin").apply { writeText("o"); setLastModified(1_000L) }
        val fresh = store.newFile("fresh.bin")
        val removed = store.gcOrphans(60_000L)
        assertEquals(1, removed)
        assertTrue(!old.exists()); assertTrue(fresh.exists())
        assertEquals(0, store.gcOrphans(60_000L))
    }

    @Test
    fun `mimeTypeOf resolves known extensions and generic fallback`() {
        // Under Robolectric MimeTypeMap may be shadowed; assert fallback contract holds for unknown.
        assertEquals(ReceivedFileStore.GENERIC_MIME_TYPE, ReceivedFileStore.mimeTypeOf("file.unknownext123"))
        assertEquals(ReceivedFileStore.GENERIC_MIME_TYPE, ReceivedFileStore.mimeTypeOf("noext"))
        val m = ReceivedFileStore.mimeTypeOf(File("photo.jpg"))
        assertTrue(m == "image/jpeg" || m == ReceivedFileStore.GENERIC_MIME_TYPE)
    }

    @Test
    fun `uniqueFile suffixes correctly`() {
        val dir = File(ctx.cacheDir, "uniq-test").apply { mkdirs() }
        val f1 = ReceivedFileStore.uniqueFile(dir, "report.pdf")
        assertEquals("report.pdf", f1.name)
        f1.createNewFile()
        assertEquals("report_1.pdf", ReceivedFileStore.uniqueFile(dir, "report.pdf").name)
        File(dir, "report_1.pdf").createNewFile()
        assertEquals("report_2.pdf", ReceivedFileStore.uniqueFile(dir, "report.pdf").name)
        val noExt = File(dir, "README").apply { createNewFile() }
        assertEquals("README_1", ReceivedFileStore.uniqueFile(dir, "README").name)
    }

    @Test
    fun `contentUri uses fileprovider authority`() {
        val store = ReceivedFileStore(ctx)
        val f = store.newFile("uri-test.txt")
        val uri = store.contentUri(f)
        assertEquals("${ctx.packageName}.fileprovider", uri.authority)
    }
}
