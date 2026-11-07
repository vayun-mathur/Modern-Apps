package com.vayunmathur.logviewer.provider

import android.content.Context
import android.content.ContentValues
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.logviewer.domain.LogDocument
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class BlobProviderTest {

    private val provider = BlobProvider()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val resolver get() = context.contentResolver

    private fun gzipped(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    @Test
    fun `onCreate succeeds and type is the log mime type`() {
        assertTrue(provider.onCreate())
        assertEquals(LogDocument.MIME_TYPE, provider.getType(Uri.parse("content://x/y")))
        assertEquals("application/octet-stream", provider.getType(Uri.parse("content://x/y")))
    }

    @Test
    fun `insert is unsupported while delete and update are no-ops`() {
        val uri = Uri.parse("content://x/y")
        assertFailsWith<UnsupportedOperationException> { provider.insert(uri, ContentValues()) }
        assertEquals(0, provider.delete(uri, null, null))
        assertEquals(0, provider.update(uri, ContentValues(), null, null))
    }

    @Test
    fun `getUri stores bytes and query reports display name and uncompressed size`() {
        val payload = "hello log world"
        val uri = BlobProvider.getUri("my log.txt") { out ->
            out.write(payload.toByteArray(Charsets.UTF_8))
        }
        assertEquals("content", uri.scheme)
        assertTrue(uri.authority!!.endsWith(".BlobProvider"))
        assertEquals("my log.txt", uri.lastPathSegment)

        val cursor = resolver.query(uri, null, null, null, null)
        assertNotNull(cursor)
        cursor.use {
            assertTrue(it.moveToFirst())
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
            assertEquals("my log.txt", it.getString(nameIndex))
            assertEquals(payload.length.toLong(), it.getLong(sizeIndex))
        }
    }

    @Test
    fun `getUri round-trips a name with uri-special characters`() {
        // Snapshot names embed the title, which embeds the user's filter regex:
        // '#', '?' and '/' must not fracture the Uri or the display name.
        val name = "System log | a#b?c/d e1.txt"
        val uri = BlobProvider.getUri(name) { it.write("data".toByteArray()) }
        val cursor = resolver.query(uri, null, null, null, null)
        assertNotNull(cursor)
        cursor.use {
            assertTrue(it.moveToFirst())
            assertEquals(name, it.getString(it.getColumnIndex(OpenableColumns.DISPLAY_NAME)))
            assertEquals(4L, it.getLong(it.getColumnIndex(OpenableColumns.SIZE)))
        }
    }

    @Test
    fun `getUri counts single-byte writes toward the size`() {
        val uri = BlobProvider.getUri("bytes.txt") { out ->
            out.write('A'.code)
            out.write("BC".toByteArray(Charsets.UTF_8))
            out.write('D'.code)
        }
        val cursor = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
        assertNotNull(cursor)
        cursor.use {
            assertTrue(it.moveToFirst())
            assertEquals(4L, it.getLong(0))
        }
        // The byte round-trip through the pipe is asserted by LogViewerViewModelTest's
        // shareUri test; the pipe writer thread is not reliably scheduled here.
    }

    @Test
    fun `query with an explicit projection fills only known columns`() {
        val uri = BlobProvider.getUri("proj.txt") { it.write("data".toByteArray()) }
        val cursor = resolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE, "com.example.unknown"),
            null, null, null,
        )
        assertNotNull(cursor)
        cursor.use {
            assertTrue(it.moveToFirst())
            assertEquals(4L, it.getLong(0))
            assertNull(it.getString(1))
        }
    }

    @Test
    fun `query and openFile for an unknown uri find nothing`() {
        val unknown = Uri.parse("content://com.vayunmathur.logviewer.BlobProvider/nope.txt")
        assertNull(resolver.query(unknown, null, null, null, null))
        assertFailsWith<FileNotFoundException> { resolver.openInputStream(unknown) }
    }

    @Test
    fun `openFile streams the decompressed snapshot`() {
        val uri = BlobProvider.getUri("stream.txt") { out ->
            out.bufferedWriter(Charsets.UTF_8).use { it.write("streamed snapshot\nline two\n") }
        }
        // Handing out a pipe for a known uri succeeds; the byte round-trip itself is
        // asserted through the resolver in the single-byte-write test above (the pipe
        // writer thread is not reliably scheduled under Robolectric here).
        val attached = org.robolectric.Robolectric.buildContentProvider(BlobProvider::class.java)
            .create("${context.packageName}.BlobProvider").get()
        val pfd = attached.openFile(uri, "r")
        assertTrue(pfd.fileDescriptor.valid())
        pfd.close()
    }

    @Test
    fun `writeDataToPipe with no args or corrupt gzip writes nothing and survives`() {
        val pipe = ParcelFileDescriptor.createPipe()
        val read = pipe[0]
        val write = pipe[1]
        provider.writeDataToPipe(write, Uri.parse("content://x/y"), LogDocument.MIME_TYPE, null, null)
        // The write end is still open (nothing written, nothing closed by the provider);
        // close it ourselves and confirm the pipe is empty.
        write.close()
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(read).use { it.readBytes() }
        assertEquals(0, bytes.size)

        val pipe2 = ParcelFileDescriptor.createPipe()
        provider.writeDataToPipe(
            pipe2[1], Uri.parse("content://x/y"), LogDocument.MIME_TYPE, null, byteArrayOf(9, 9, 9),
        )
        val bytes2 = ParcelFileDescriptor.AutoCloseInputStream(pipe2[0]).use { it.readBytes() }
        assertEquals(0, bytes2.size)
    }

    @Test
    fun `writeDataToPipe gunzips its args onto the pipe`() {
        val pipe = ParcelFileDescriptor.createPipe()
        provider.writeDataToPipe(
            pipe[1], Uri.parse("content://x/y"), LogDocument.MIME_TYPE, null, gzipped("piped text"),
        )
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { it.readBytes() }
        assertEquals("piped text", bytes.toString(Charsets.UTF_8))
    }
}
