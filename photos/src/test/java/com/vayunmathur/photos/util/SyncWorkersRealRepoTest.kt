package com.vayunmathur.photos.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.TestPhotosRepository
import com.vayunmathur.photos.data.VideoData
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Worker entry points and indexing passes over the real repositories.
 *
 * With no MediaStore provider and no ML model assets on the test host, the
 * genuine worker code must still run its diffing/bookkeeping: a library
 * sync deletes rows MediaStore no longer reports, EXIF backfill marks its
 * targets scanned, and the OCR/CLIP/face passes leave unscanned rows
 * untouched for a device that has the models.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncWorkersRealRepoTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var installed: TestPhotosRepository.Installed

    /** Minimal media provider: answers generation calls and empty queries. */
    private class MediaProvider : android.content.ContentProvider() {
        override fun onCreate() = true
        override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle =
            android.os.Bundle().apply { putLong("generation", 7L); putLong("android.provider.extra.GENERATION", 7L); putLong("extra_generation", 7L) }
        override fun query(
            uri: android.net.Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): android.database.Cursor {
            val columns = projection ?: arrayOf("_id")
            val cursor = android.database.MatrixCursor(columns)
            val isVideo = uri.toString().contains("video")
            when {
                columns.none { it.contains("display_name") } -> addIdRows(cursor, isVideo)
                isVideo -> addVideoRows(cursor, columns)
                else -> addImageRows(cursor, columns)
            }
            return cursor
        }

        private fun addIdRows(cursor: android.database.MatrixCursor, isVideo: Boolean) {
            for (id in if (isVideo) listOf(601L) else listOf(501L, 502L)) {
                cursor.addRow(arrayOf<Any>(id))
            }
        }

        private fun baseValue(col: String): Any = when (col) {
            "datetaken" -> 1_700_000_000_000L
            "date_added" -> 1_700_000_000L
            "date_modified" -> 1_700_000_000L
            "bucket_display_name" -> "Camera"
            else -> 0
        }

        private fun addVideoRows(cursor: android.database.MatrixCursor, columns: Array<out String>) {
            val row = Array<Any?>(columns.size) { baseValue(columns[it]) }
            columns.forEachIndexed { i, col ->
                if (col == "_id") row[i] = 601L
                if (col.contains("display_name")) row[i] = "clip.mp4"
                if (col == "mime_type") row[i] = "video/mp4"
            }
            cursor.addRow(row)
        }

        private fun addImageRows(cursor: android.database.MatrixCursor, columns: Array<out String>) {
            for ((id, name, fav) in listOf(Triple(501L, "beach.jpg", 1), Triple(502L, "mountain.jpg", 0))) {
                val row = Array<Any?>(columns.size) { baseValue(columns[it]) }
                columns.forEachIndexed { i, col ->
                    if (col == "_id") row[i] = id
                    if (col == "_display_name" || col == "display_name") row[i] = name
                    if (col == "mime_type") row[i] = "image/jpeg"
                    if (col == "is_favorite") row[i] = fav
                }
                cursor.addRow(row)
            }
        }

        override fun getType(uri: android.net.Uri): String? = null
        override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = null
        override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(
            uri: android.net.Uri, values: android.content.ContentValues?, selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0
    }

    @BeforeTest fun setup() {
        runCatching {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        }
        val provider = MediaProvider()
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply {
            authority = "media"
            packageName = context.packageName
        })
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media", provider)
        installed = TestPhotosRepository.install(context)
    }

    private fun photo(name: String, date: Long = 1_000L, exifSet: Boolean = false) = Photo(
        name = name, uri = "content://media/$name", date = date, width = 100, height = 100,
        dateModified = date, exifSet = exifSet, lat = null, long = null,
        videoData = null, panoData = null,
    )

    @Test fun syncPhotosDeletesRowsMediaStoreNoLongerReports() = runBlocking {
        installed.photos.upsertAll(listOf(photo("a"), photo("b", date = 2_000L)))
        assertEquals(2, installed.photos.getAll().size)
        // A full sync deletes rows the provider no longer reports and
        // imports the rows it does (501/502/601 from the stub provider).
        syncPhotos(context, installed.photos, null, 0L)
        assertEquals(setOf(501L, 502L, 601L), installed.photos.getAll().map { it.id }.toSet())
        Unit
    }

    @Test fun syncPhotosWithTriggeredUrisKeepsUntriggeredRows() = runBlocking {
        installed.photos.upsertAll(listOf(photo("a"), photo("b", date = 2_000L)))
        val a = installed.photos.getAll().first { it.name == "a" }
        val triggered = listOf(android.content.ContentUris.withAppendedId(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, a.id))
        syncPhotos(context, installed.photos, triggered, 0L)
        // Only the triggered id is deletion-eligible; the other seeded row
        // remains, joined by whatever the provider reports.
        assertTrue(installed.photos.getAll().any { it.name == "b" })
        assertTrue(installed.photos.getAll().none { it.name == "a" })
        Unit
    }

    private class FakeFlag(var set: Boolean = false) : OneTimeFlag {
        override suspend fun isSet(): Boolean = set
        override suspend fun set() { set = true }
    }

    @Test fun syncLibraryFullAndIncrementalScansRun() = runBlocking {
        installed.photos.upsertAll(listOf(photo("a")))
        val flag = FakeFlag(set = true)
        syncLibrary(context, installed.photos, lastGeneration = 0L, needsMimeBackfill = false, favoritesRescan = flag)
        // The seeded row is gone; the provider's rows took its place.
        assertTrue(installed.photos.getAll().none { it.name == "a" })
        installed.photos.upsertAll(listOf(photo("b")))
        val firstRun = FakeFlag()
        syncLibrary(context, installed.photos, lastGeneration = 42L, needsMimeBackfill = true, favoritesRescan = firstRun)
        assertTrue(firstRun.set, "one-time flag set after the full scan")
        Unit
    }

    @Test fun exifBackfillMarksTargetsScannedWithoutClobberingRows() = runBlocking {
        installed.photos.upsertAll(listOf(photo("a", exifSet = false), photo("done", date = 2L, exifSet = true)))
        setExifData(installed.scan, context)
        // Unreadable URIs still count as processed (no retry loop), and the
        // row's other columns survive the column-targeted write.
        val after = installed.photos.getAll().first { it.name == "a" }
        assertTrue(after.exifSet)
        assertEquals("a", after.name)
        assertTrue(installed.scan.getUnscannedForExif().isEmpty())
        Unit
    }

    @Test fun indexingPassesLeaveRowsUnscannedWhenModelsMissing() = runBlocking {
        installed.photos.upsertAll(listOf(photo("a")))
        runOCR(installed.scan, context)
        runClipIndexing(installed.scan, context)
        runFaceIndexing(installed.faces, installed.scan, context)
        // No models on this host: nothing is marked scanned, so a real
        // device run would pick the photo up again.
        assertEquals(1, installed.scan.getUnscannedForOCR().size)
        assertEquals(1, installed.scan.getUnscannedForClip().size)
        assertEquals(1, installed.scan.getUnscannedForFaces().size)
        assertTrue(installed.faces.getPersons().isEmpty())
        Unit
    }

    @Test fun foregroundInfoCreatesChannelAndNotification() {
        val info = context.syncForegroundInfo(
            notificationId = 7, channelId = "test-channel", channelName = "Test",
            title = "Working", text = "Indexing",
        )
        assertEquals(7, info.notificationId)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        assertTrue(manager.notificationChannels.any { it.id == "test-channel" })
    }

    @Test fun workersSucceedWithEmptyLibrary() = runBlocking {
        // SyncWorker.doWork itself reads MediaStore generations, which
        // Robolectric does not shadow; its sync functions are covered directly above.
        val ocr = TestListenableWorkerBuilder<OCRWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), ocr.doWork())
        val clip = TestListenableWorkerBuilder<ClipWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), clip.doWork())
        val face = TestListenableWorkerBuilder<FaceWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), face.doWork())
        Unit
    }

    @Test fun syncWorkerDoWorkRunsFullPassWithMediaProvider() = runBlocking {
        installed.photos.upsertAll(listOf(photo("stale")))
        val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        // The seeded row is synced away, the provider's rows are imported,
        // and the generation watermark was written for the next pass.
        assertTrue(installed.photos.getAll().none { it.name == "stale" })
        assertTrue(com.vayunmathur.library.util.DataStoreUtils.getInstance(context).getLongAwait("last_photos_generation") != null)
        Unit
    }

    @Test fun syncLibraryImportsRowsFromMediaProvider() = runBlocking {
        val flag = FakeFlag()
        syncLibrary(context, installed.photos, 0L, false, flag)
        val all = installed.photos.getAll()
        assertEquals(setOf("beach.jpg", "mountain.jpg", "clip.mp4"), all.map { it.name }.toSet())
        val beach = all.first { it.name == "beach.jpg" }
        assertEquals("Camera", beach.album)
        assertTrue(beach.isFavorite)
        assertEquals(501L, beach.id)
        val clip = all.first { it.name == "clip.mp4" }
        assertTrue(clip.videoData != null)
        assertTrue(flag.isSet())
        // A second pass over the same provider rows changes nothing.
        syncLibrary(context, installed.photos, 0L, false, flag)
        assertEquals(3, installed.photos.getAll().size)
        Unit
    }

    @Test fun coolDownBetweenBatchesOnlyPausesOnBatchBoundaries() = runBlocking {
        val started = System.currentTimeMillis()
        coolDownBetweenBatches(1, "test")
        coolDownBetweenBatches(19, "test")
        // No boundary crossed (every 20): both calls return immediately.
        assertTrue(System.currentTimeMillis() - started < 4_000L)
        Unit
    }

    @Test fun videoRowsAreExcludedFromIndexingQueues() = runBlocking {
        installed.photos.upsertAll(listOf(
            photo("movie").copy(videoData = VideoData(9_000L)),
        ))
        assertTrue(installed.scan.getUnscannedForOCR().isEmpty())
        assertTrue(installed.scan.getUnscannedForFaces().isEmpty())
        assertTrue(installed.scan.getUnscannedForClip().isEmpty())
        Unit
    }
}
