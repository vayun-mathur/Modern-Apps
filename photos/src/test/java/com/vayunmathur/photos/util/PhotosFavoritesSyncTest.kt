package com.vayunmathur.photos.util

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotosRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/**
 * #912 sync: IS_FAVORITE flows from MediaStore into the Photo rows (including a
 * change made in another gallery app), favourite-only diffs are written by
 * column instead of re-upserting the row, and the post-upgrade full rescan
 * runs exactly once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosFavoritesSyncTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakePhotosRepository(context: Context) : PhotosRepository(context) {
        var all: List<Photo> = emptyList()
        val deletedBatches = mutableListOf<List<Long>>()
        val upsertedBatches = mutableListOf<List<Photo>>()
        val favoriteWrites = mutableListOf<Pair<List<Long>, Boolean>>()
        override suspend fun getAll(): List<Photo> = all
        override suspend fun deleteByIds(ids: List<Long>) {
            deletedBatches += ids
        }
        override suspend fun upsertAll(photos: List<Photo>) {
            upsertedBatches += photos
        }
        override suspend fun setFavorite(ids: List<Long>, value: Boolean) {
            favoriteWrites += ids to value
        }
    }

    private fun photo(id: Long, favorite: Boolean = false, dateModified: Long = 100L) = Photo(
        id = id, name = "p$id", uri = "content://media/external/images/media/$id",
        date = 1_000L, width = 100, height = 100, dateModified = dateModified,
        exifSet = true, lat = null, long = null, videoData = null, panoData = null,
        isTrashed = false, mimeType = "image/jpeg", album = "Camera",
        clipEmbedding = byteArrayOf(9), clipScanned = true, isFavorite = favorite,
    )

    /** MediaStore stand-in serving rows with IS_FAVORITE and recording each query's selection. */
    private class FakeMediaProvider(
        private val imageRows: List<Array<Any?>>,
        private val videoRows: List<Array<Any?>> = emptyList(),
    ) : ContentProvider() {
        val selections = mutableListOf<String?>()

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor = throw UnsupportedOperationException("bundle query only")

        override fun query(
            uri: Uri, projection: Array<out String>?, queryArgs: Bundle?,
            cancellationSignal: android.os.CancellationSignal?,
        ): Cursor {
            val columns = projection ?: COLUMNS
            // The id-only deletion scan has no selection; record the content queries.
            if (columns.size > 1) selections += queryArgs?.getString(ContentResolver.QUERY_ARG_SQL_SELECTION)
            val rows = if (uri.toString().contains("/video/")) videoRows else imageRows
            val cursor = MatrixCursor(columns)
            for (row in rows) {
                val byName = COLUMNS.zip(row).toMap()
                cursor.addRow(columns.map { byName[it] })
            }
            return cursor
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(
            uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
        ): Int = 0

        companion object {
            val COLUMNS = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_TAKEN,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT,
                MediaStore.MediaColumns.IS_TRASHED,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                MediaStore.MediaColumns.IS_FAVORITE,
                MediaStore.Video.Media.DURATION,
            )
        }
    }

    private fun row(id: Long, favorite: Boolean, dateModified: Long = 100L, duration: Long? = null): Array<Any?> =
        arrayOf(
            id, "p$id", 1_000L, 1L, dateModified, 100, 100, 0, "image/jpeg", "Camera",
            if (favorite) 1 else 0, duration,
        )

    private fun register(provider: FakeMediaProvider): FakeMediaProvider {
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)
        return provider
    }

    @Test
    fun favoriteSetInAnotherAppIsWrittenByColumnNotUpserted() = runBlocking<Unit> {
        // Photo 1 was favourited elsewhere, photo 2 unfavourited; nothing else moved.
        register(FakeMediaProvider(listOf(row(1, favorite = true), row(2, favorite = false))))
        val repository = FakePhotosRepository(context).apply {
            all = listOf(photo(1, favorite = false), photo(2, favorite = true))
        }

        syncPhotos(context, repository, null, 0L)

        // No row rebuild (which would drop the CLIP embedding); one targeted write each way.
        assertTrue(repository.upsertedBatches.isEmpty(), repository.upsertedBatches.toString())
        assertEquals(
            setOf(listOf(1L) to true, listOf(2L) to false),
            repository.favoriteWrites.toSet(),
        )
    }

    @Test
    fun newAndOtherwiseChangedRowsCarryTheirFavoriteFlag() = runBlocking<Unit> {
        register(
            FakeMediaProvider(
                imageRows = listOf(row(1, favorite = true, dateModified = 999L), row(3, favorite = true)),
                videoRows = listOf(row(5, favorite = true, duration = 4_000L)),
            ),
        )
        val repository = FakePhotosRepository(context).apply { all = listOf(photo(1, favorite = false)) }

        syncPhotos(context, repository, null, 0L)

        val upserted = repository.upsertedBatches.single().associateBy { it.id }
        assertEquals(setOf(1L, 3L, 5L), upserted.keys)
        // Videos are favourited the same way as photos.
        assertTrue(upserted.values.all { it.isFavorite })
        assertTrue(repository.favoriteWrites.isEmpty())
    }

    @Test
    fun unchangedFavoriteIsLeftAlone() = runBlocking<Unit> {
        register(FakeMediaProvider(listOf(row(1, favorite = true))))
        val repository = FakePhotosRepository(context).apply { all = listOf(photo(1, favorite = true)) }

        syncPhotos(context, repository, null, 0L)

        assertTrue(repository.upsertedBatches.isEmpty())
        assertTrue(repository.favoriteWrites.isEmpty())
    }

    @Test
    fun favoriteDeletedElsewhereDropsOut() = runBlocking<Unit> {
        register(FakeMediaProvider(listOf(row(1, favorite = false))))
        val repository = FakePhotosRepository(context).apply {
            all = listOf(photo(1), photo(2, favorite = true))
        }

        syncPhotos(context, repository, null, 0L)

        assertEquals(listOf(listOf(2L)), repository.deletedBatches)
    }

    private class FakeFlag : OneTimeFlag {
        var value = false
        var sets = 0
        override suspend fun isSet(): Boolean = value
        override suspend fun set() {
            value = true
            sets++
        }
    }

    @Test
    fun favoritesRescanIsFullOnceThenIncremental() = runBlocking<Unit> {
        val provider = register(FakeMediaProvider(listOf(row(1, favorite = true))))
        val repository = FakePhotosRepository(context).apply { all = listOf(photo(1, favorite = false)) }
        val flag = FakeFlag()

        // First sync after the upgrade: full scan despite a stored generation, so
        // the favourite made before the upgrade is picked up.
        syncLibrary(context, repository, lastGeneration = 42L, needsMimeBackfill = false, favoritesRescan = flag)
        assertTrue(provider.selections.isNotEmpty())
        assertTrue(provider.selections.all { it == null }, provider.selections.toString())
        assertEquals(listOf(listOf(1L) to true), repository.favoriteWrites)
        assertTrue(flag.value)

        // Every later sync is incremental again, and the flag is not rewritten.
        provider.selections.clear()
        syncLibrary(context, repository, lastGeneration = 42L, needsMimeBackfill = false, favoritesRescan = flag)
        assertTrue(provider.selections.isNotEmpty())
        assertTrue(
            provider.selections.all { it == "${MediaStore.MediaColumns.GENERATION_MODIFIED} > 42" },
            provider.selections.toString(),
        )
        assertEquals(1, flag.sets)
    }

    @Test
    fun mimeBackfillStillForcesAFullScanAfterTheRescan() = runBlocking<Unit> {
        val provider = register(FakeMediaProvider(listOf(row(1, favorite = false))))
        val repository = FakePhotosRepository(context).apply { all = listOf(photo(1)) }
        val flag = FakeFlag().apply { value = true }

        syncLibrary(context, repository, lastGeneration = 42L, needsMimeBackfill = true, favoritesRescan = flag)

        assertTrue(provider.selections.all { it == null })
        assertEquals(0, flag.sets)
    }

    @Test
    fun freshInstallFullScanAlsoMarksTheRescanDone() = runBlocking<Unit> {
        val provider = register(FakeMediaProvider(emptyList()))
        val flag = FakeFlag()

        syncLibrary(context, FakePhotosRepository(context), 0L, needsMimeBackfill = false, favoritesRescan = flag)

        assertNull(provider.selections.firstOrNull())
        assertTrue(flag.value)
        assertFalse(provider.selections.any { it != null })
    }
}
