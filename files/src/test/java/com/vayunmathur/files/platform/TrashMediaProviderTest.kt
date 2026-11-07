package com.vayunmathur.files.platform

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Serves MediaStore-shaped rows for one real file so the trash queries in
 * [FilesTrashKt] can be exercised without a device media database.
 */
class FakeMediaProvider : ContentProvider() {
    companion object {
        var dataPath: String = ""
        var displayName: String = "trashed.txt"
    }

    override fun onCreate(): Boolean = true

    private fun rows(projection: Array<out String>?): MatrixCursor {
        val columns = projection ?: arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
        )
        val cursor = MatrixCursor(columns)
        val values = mapOf<String, Any>(
            MediaStore.MediaColumns._ID to 42L,
            MediaStore.MediaColumns.DISPLAY_NAME to displayName,
            MediaStore.MediaColumns.DATA to dataPath,
            MediaStore.MediaColumns.SIZE to 123L,
            MediaStore.MediaColumns.DATE_MODIFIED to 1_700_000_000L,
            MediaStore.Files.FileColumns.MEDIA_TYPE to MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE,
        )
        cursor.addRow(columns.map { values[it] })
        return cursor
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor = rows(projection)

    override fun query(
        uri: Uri, projection: Array<out String>?, queryArgs: Bundle?,
        cancellationSignal: CancellationSignal?,
    ): Cursor = rows(projection)

    override fun getType(uri: Uri): String = "image/png"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ): Int = 0
}

@RunWith(RobolectricTestRunner::class)
class TrashMediaProviderTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var file: File

    @BeforeTest
    fun setUp() {
        file = File(ctx.cacheDir, "trashed-${System.nanoTime()}.txt").apply { writeText("trash me") }
        FakeMediaProvider.dataPath = file.absolutePath
        Robolectric.buildContentProvider(FakeMediaProvider::class.java).create("media")
    }

    @Test
    fun `trashed items are read from media rows`() {
        val items = queryTrashedItems(ctx)
        assertEquals(1, items.size)
        val item = items.first()
        assertEquals("trashed.txt", item.name)
        assertEquals(file, item.realFile)
        assertEquals(123L, item.size)
        assertEquals(1_700_000_000_000L, item.lastModified)
        assertEquals("trash:42", item.key)
        assertTrue(item.mediaUri.toString().endsWith("/42"))
    }

    @Test
    fun `trashable uris resolve through the provider`() {
        val resolved = resolveTrashableUris(ctx, listOf(file))
        assertEquals(1, resolved.size)
        assertTrue(resolved[file].toString().endsWith("/42"))
        // directories are never trashable and are skipped before querying
        val dir = File(ctx.cacheDir, "adir-${System.nanoTime()}").apply { mkdirs() }
        assertTrue(resolveTrashableUris(ctx, listOf(dir)).isEmpty())
        // selection resolution prefers the cached media uri, else re-queries
        val cached = FileBrowserItem(
            "x.txt", false, 1, file, null, "k1",
            mediaUri = Uri.parse("content://media/external/images/media/7"),
        )
        val uncached = FileBrowserItem("y.txt", false, 1, file, null, "k2")
        val uris = urisForSelection(ctx, listOf(cached, uncached))
        assertEquals(2, uris.size)
        assertTrue(uris[0].toString().endsWith("/7"))
        assertTrue(uris[1].toString().endsWith("/42"))
    }
}
