package com.vayunmathur.photos.util

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.ExifResult
import com.vayunmathur.photos.data.FaceRepository
import com.vayunmathur.photos.data.OcrResult
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotoExifTarget
import com.vayunmathur.photos.data.PhotoScanRepository
import com.vayunmathur.photos.data.PhotoScanTarget
import com.vayunmathur.photos.data.PhotosRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/**
 * Slice 1/3 second pass: [syncPhotos] deletion/update diffing over a fake
 * MediaStore provider, plus the inert indexing loops (no ML models here).
 * Repositories are recording fakes (open test seam) - no SQLCipher, no mocks
 * of suspend functions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosSlice1SyncTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakePhotosRepository(context: Context) : PhotosRepository(context) {
        var all: List<Photo> = emptyList()
        val deletedBatches = mutableListOf<List<Long>>()
        val upsertedBatches = mutableListOf<List<Photo>>()
        override suspend fun getAll(): List<Photo> = all
        override suspend fun deleteByIds(ids: List<Long>) {
            deletedBatches += ids
        }
        override suspend fun upsertAll(photos: List<Photo>) {
            upsertedBatches += photos
        }
    }

    private class FakeScanRepository(context: Context) : PhotoScanRepository(context) {
        var exifTargets: List<PhotoExifTarget> = emptyList()
        var ocrTargets: List<PhotoScanTarget> = emptyList()
        val exifWrites = mutableListOf<List<ExifResult>>()
        val ocrWrites = mutableListOf<List<OcrResult>>()
        var facesRead = false
        var clipRead = false
        override suspend fun getUnscannedForExif(): List<PhotoExifTarget> = exifTargets
        override suspend fun getUnscannedForOCR(): List<PhotoScanTarget> = ocrTargets
        override suspend fun getUnscannedForFaces(): List<PhotoScanTarget> {
            facesRead = true
            return emptyList()
        }
        override suspend fun getUnscannedForClip(): List<PhotoScanTarget> {
            clipRead = true
            return emptyList()
        }
        override suspend fun setExifResults(results: List<ExifResult>) {
            exifWrites += results
        }
        override suspend fun setOcrResults(results: List<OcrResult>) {
            ocrWrites += results
        }
    }

    private fun photo(id: Long, name: String = "p$id", dateModified: Long = 100L) = Photo(
        id = id, name = name, uri = "content://media/external/images/media/$id",
        date = 1_000L, width = 100, height = 100, dateModified = dateModified,
        exifSet = true, lat = null, long = null, videoData = null, panoData = null,
        isTrashed = false, mimeType = "image/jpeg", album = "Camera",
    )

    /** Serves fixed image/video rows for both the id scan and the full query. */
    private class FakeMediaProvider(
        private val imageRows: List<Array<Any?>>,
        private val videoRows: List<Array<Any?>>,
    ) : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor = throw UnsupportedOperationException("bundle query only")

        override fun query(
            uri: Uri, projection: Array<out String>?, queryArgs: Bundle?,
            cancellationSignal: android.os.CancellationSignal?,
        ): Cursor {
            val isVideo = uri.toString().contains("/video/")
            val rows = if (isVideo) videoRows else imageRows
            val columns = projection ?: FULL_COLUMNS
            val cursor = MatrixCursor(columns)
            for (row in rows) {
                val byName = FULL_COLUMNS.zip(row).toMap()
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
            val FULL_COLUMNS = arrayOf(
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
                MediaStore.Video.Media.DURATION,
            )
        }
    }

    private fun row(
        id: Long, name: String, dateModified: Long = 100L, duration: Long? = null,
    ): Array<Any?> = arrayOf(
        id, name, 1_000L, 1L, dateModified, 100, 100, 0, "image/jpeg", "Camera", duration,
    )

    private fun registerProvider(images: List<Array<Any?>>, videos: List<Array<Any?>> = emptyList()) {
        ShadowContentResolver.registerProviderInternal(
            MediaStore.AUTHORITY, FakeMediaProvider(images, videos),
        )
    }

    @Test
    fun fullSyncDeletesMissingAndUpsertsNewAndChanged() = runBlocking<Unit> {
        registerProvider(listOf(row(1, "p1"), row(3, "p3")))
        val repository = FakePhotosRepository(context).apply { all = listOf(photo(1), photo(2)) }
        syncPhotos(context, repository, null, 0L)
        assertEquals(listOf(listOf(2L)), repository.deletedBatches)
        assertEquals(1, repository.upsertedBatches.size)
        val newPhotos = repository.upsertedBatches.single()
        assertEquals(listOf(3L), newPhotos.map { it.id })
        assertEquals("p3", newPhotos.single().name)
    }

    @Test
    fun fullSyncUpsertsChangedRowsOnly() = runBlocking<Unit> {
        registerProvider(listOf(row(1, "p1", dateModified = 999L), row(2, "p2")))
        val repository = FakePhotosRepository(context).apply { all = listOf(photo(1), photo(2)) }
        syncPhotos(context, repository, null, 0L)
        assertTrue(repository.deletedBatches.isEmpty())
        val changed = repository.upsertedBatches.single()
        // Only photo 1 changed (dateModified); photo 2 is identical and skipped.
        assertEquals(listOf(1L), changed.map { it.id })
        assertEquals(999L, changed.single().dateModified)
        // Prior scan state survives a metadata-only update.
        assertTrue(changed.single().exifSet)
    }

    @Test
    fun triggeredSyncOnlyDeletesTriggeredMissingIds() = runBlocking<Unit> {
        registerProvider(listOf(row(1, "p1")))
        val repository = FakePhotosRepository(context).apply {
            all = listOf(photo(1), photo(2), photo(3))
        }
        val triggered = listOf(
            Uri.parse("content://media/external/images/media/2"),
            Uri.parse("content://media/external/images/media/1"),
        )
        syncPhotos(context, repository, triggered, 0L)
        // Photo 3 is missing from MediaStore too, but was not triggered: kept.
        assertEquals(listOf(listOf(2L)), repository.deletedBatches)
    }

    @Test
    fun videoRowsSyncAsVideos() = runBlocking<Unit> {
        registerProvider(
            images = listOf(row(1, "p1")),
            videos = listOf(row(5, "v5", duration = 4200L)),
        )
        val repository = FakePhotosRepository(context).apply { all = listOf(photo(1)) }
        syncPhotos(context, repository, null, 0L)
        val video = repository.upsertedBatches.single().single()
        assertEquals(5L, video.id)
        assertEquals(4200L, video.videoData?.duration)
        assertTrue(video.uri.contains("/video/"))
    }

    @Test
    fun exifPassWritesNullResultsForUnreadableUris() = runBlocking<Unit> {
        val scanRepository = FakeScanRepository(context).apply {
            exifTargets = listOf(PhotoExifTarget(id = 7L, uri = "content://media/external/images/media/7"))
        }
        setExifData(scanRepository, context)
        val result = scanRepository.exifWrites.single().single()
        assertEquals(7L, result.id)
        assertEquals(null, result.lat)
        assertEquals(null, result.long)
    }

    @Test
    fun indexingLoopsAreInertWithoutModels() = runBlocking<Unit> {
        val scanRepository = FakeScanRepository(context).apply {
            ocrTargets = listOf(
                PhotoScanTarget(id = 1L, uri = "content://media/1", date = 0L, width = 4000, height = 3000),
            )
        }
        // OCR engine unavailable on this host: the loop returns without writes.
        runOCR(scanRepository, context)
        assertTrue(scanRepository.ocrWrites.isEmpty())

        runFaceIndexing(mock(FaceRepository::class.java), scanRepository, context)
        assertEquals(false, scanRepository.facesRead)

        runClipIndexing(scanRepository, context)
        assertEquals(false, scanRepository.clipRead)
    }

    @Test
    fun setExifDataWithNoTargetsWritesNothing() = runBlocking<Unit> {
        val scanRepository = FakeScanRepository(context)
        setExifData(scanRepository, context)
        assertTrue(scanRepository.exifWrites.isEmpty())
    }
}
