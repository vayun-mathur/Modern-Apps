package com.vayunmathur.logviewer.provider

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.vayunmathur.library.log.Log
import android.util.LruCache
import com.vayunmathur.logviewer.BuildConfig
import com.vayunmathur.logviewer.domain.LogDocument
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Hands a log to another app as a `content:` Uri without it ever touching storage.
 *
 * Sharing needs a Uri, and the two obvious ways to make one are both wrong here. Putting the bytes
 * in the Intent breaks the binder transaction limit on any real log. Writing a file means a log
 * sitting in cache afterwards, readable by anything with the Uri, and something has to delete it.
 *
 * So the bytes stay in this process, gzipped - a log compresses by roughly an order of magnitude,
 * and the cache is bounded at 40 MiB of *compressed* data so that a few large shares cannot grow
 * the heap without limit. A Uri stays valid until the process dies or its entry is evicted, which
 * is the whole life of a share.
 */
class BlobProvider : ContentProvider(), ContentProvider.PipeDataWriter<ByteArray> {

    override fun onCreate(): Boolean = true

    /**
     * Streamed through a pipe rather than a `SharedMemory` fd. The receiving app sees an ordinary
     * readable stream either way, and `openPipeHelper` is public API where `SharedMemory.getFdDup`
     * is not.
     */
    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        Log.dev(TAG, "openFile $uri, mode $mode, caller $callingPackage")
        val entry = entryFor(uri) ?: throw FileNotFoundException()
        return openPipeHelper(uri, LogDocument.MIME_TYPE, null, entry.gzipped, this)
    }

    override fun writeDataToPipe(
        output: ParcelFileDescriptor,
        uri: Uri,
        mimeType: String,
        opts: Bundle?,
        args: ByteArray?,
    ) {
        if (args == null) return
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(output).use { stream ->
                GZIPInputStream(ByteArrayInputStream(args)).use { it.copyTo(stream) }
            }
        } catch (e: IOException) {
            // Normal: the reader is free to stop early, which closes its end of the pipe.
            Log.dev(TAG, "pipe closed while writing $uri", e)
        }
    }

    override fun getType(uri: Uri): String = LogDocument.MIME_TYPE

    /**
     * The share sheet asks for the display name and size before it asks for the bytes, and shows
     * the Uri's last path segment if it gets no answer.
     */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        Log.dev(TAG, "query $uri, caller $callingPackage")
        val entry = entryFor(uri) ?: return null
        val columns = projection
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = arrayOfNulls<Any>(columns.size)
        for (i in columns.indices) {
            row[i] = when (columns[i]) {
                OpenableColumns.DISPLAY_NAME -> uri.lastPathSegment
                OpenableColumns.SIZE -> entry.size.toLong()
                else -> null
            }
        }
        return MatrixCursor(columns).apply { addRow(row) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun entryFor(uri: Uri): Entry? = synchronized(entries) { entries.get(uri) }

    private class Entry(val gzipped: ByteArray, val size: Int)

    companion object {
        private const val TAG = "BlobProvider"

        /**
         * Must match `android:authorities` in the manifest. Derived from the applicationId rather
         * than from this class's name, which is what the app being replaced used - there the two
         * happened to coincide, and here they do not.
         */
        private val AUTHORITY = BuildConfig.APPLICATION_ID + ".BlobProvider"

        private val entries = object : LruCache<Uri, Entry>(40 shl 20) {
            override fun sizeOf(key: Uri, value: Entry): Int = value.gzipped.size
        }

        /**
         * Registers whatever [write] produces under a Uri named [blobName] and returns that Uri.
         * Compressed as it is written, so the uncompressed log never exists as one array.
         */
        fun getUri(blobName: String, write: (OutputStream) -> Unit): Uri {
            val uri = Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(AUTHORITY)
                .path(blobName)
                .build()

            val compressed = ByteArrayOutputStream()
            var size = 0
            GZIPOutputStream(compressed).use { gzip ->
                write(object : FilterOutputStream(gzip) {
                    override fun write(b: Int) {
                        out.write(b)
                        size++
                    }

                    override fun write(b: ByteArray, off: Int, len: Int) {
                        out.write(b, off, len)
                        size += len
                    }
                })
            }

            synchronized(entries) { entries.put(uri, Entry(compressed.toByteArray(), size)) }
            return uri
        }
    }
}
