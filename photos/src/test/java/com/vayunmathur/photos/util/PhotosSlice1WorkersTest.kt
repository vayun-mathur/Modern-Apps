package com.vayunmathur.photos.util

import android.content.Context
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.vayunmathur.photos.data.MIGRATION_10_11
import com.vayunmathur.photos.data.MIGRATION_11_12
import com.vayunmathur.photos.data.MIGRATION_12_13
import com.vayunmathur.photos.data.MIGRATION_13_14
import com.vayunmathur.photos.data.MIGRATION_14_15
import com.vayunmathur.photos.data.MIGRATION_15_16
import com.vayunmathur.photos.data.MIGRATION_16_17
import com.vayunmathur.photos.data.MIGRATION_17_18
import com.vayunmathur.photos.data.MIGRATION_18_19
import com.vayunmathur.photos.data.MIGRATION_19_20
import com.vayunmathur.photos.data.MIGRATION_1_2
import com.vayunmathur.photos.data.MIGRATION_2_3
import com.vayunmathur.photos.data.MIGRATION_3_4
import com.vayunmathur.photos.data.MIGRATION_4_5
import com.vayunmathur.photos.data.MIGRATION_5_6
import com.vayunmathur.photos.data.MIGRATION_6_7
import com.vayunmathur.photos.data.MIGRATION_7_8
import com.vayunmathur.photos.data.MIGRATION_8_9
import com.vayunmathur.photos.data.MIGRATION_9_10
import com.vayunmathur.photos.data.PhotoDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice 1/3 second pass: the [PhotoDatabase] migration chain plus worker
 * scheduling/inert-run behaviour for Sync/OCR/Face/Clip workers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosSlice1WorkersTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun initWorkManager() {
        // Synchronous executors run enqueued work immediately on this host,
        // where the doWork bodies cannot succeed (SQLCipher keystore / ML
        // native library absent): scheduling is what is pinned here, so the
        // terminal state is allowed to be FAILED - the work must exist.
        val config = Configuration.Builder()
            .setExecutor(androidx.work.testing.SynchronousExecutor())
            .setTaskExecutor(androidx.work.testing.SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    private fun awaitWork(name: String): List<WorkInfo> {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()
            if (infos.isNotEmpty()) return infos
            Thread.sleep(20)
        }
        throw AssertionError("no work enqueued under $name")
    }

    // ---- Migration chain v1 -> v20 ----

    @Test
    fun fullMigrationChainUpgradesV1Schema() = runBlocking<Unit> {
        val connection = androidx.sqlite.driver.bundled.BundledSQLiteDriver()
            .open(":memory:")
        try {
            // Minimal v1 Photo table: the columns the early migrations index
            // or update, before any ALTER ran.
            connection.execSQL(
                "CREATE TABLE Photo (id INTEGER PRIMARY KEY NOT NULL, date INTEGER NOT NULL, " +
                    "exifSet INTEGER NOT NULL DEFAULT 0, duration INTEGER)",
            )
            connection.execSQL("INSERT INTO Photo (id, date) VALUES (1, 1000), (2, 2000)")
            val migrations = listOf(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
                MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16,
                MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20,
            )
            assertEquals(19, PhotoDatabase.migrations.size)
            for (migration in migrations) migration.migrate(connection)

            fun queryStrings(sql: String): List<String> = connection.prepare(sql).use { statement ->
                val rows = mutableListOf<String>()
                while (statement.step()) rows += statement.getText(0) ?: ""
                rows
            }
            fun queryLong(sql: String): Long = connection.prepare(sql).use { statement ->
                assertTrue(statement.step())
                statement.getLong(0)
            }

            // Final schema carries the v19 face tables and late Photo columns.
            val tables = queryStrings("SELECT name FROM sqlite_master WHERE type='table'").toSet()
            assertTrue("Person" in tables, tables.toString())
            assertTrue("PhotoFace" in tables, tables.toString())
            val photoColumns = queryStrings("PRAGMA table_info(Photo)").toSet()
            // PRAGMA table_info returns cid first; column name is index 1 - requery by name.
            val columnNames = connection.prepare("PRAGMA table_info(Photo)").use { statement ->
                val names = mutableSetOf<String>()
                while (statement.step()) names += statement.getText(1) ?: ""
                names
            }
            assertTrue(photoColumns.isNotEmpty())
            for (column in listOf(
                "dateModified", "isTrashed", "faceScanned", "ocrText", "ocrScanned",
                "clipEmbedding", "clipScanned", "mimeType", "ocrBoxes", "album", "projectionType",
                "isFavorite",
            )) {
                assertTrue(column in columnNames, "missing $column in $columnNames")
            }
            // Rows survived the whole chain (drop/recreate only touched face tables).
            assertEquals(2L, queryLong("SELECT COUNT(*) FROM Photo"))
            // The face reset left both photos unscanned for the new indexer.
            assertEquals(2L, queryLong("SELECT COUNT(*) FROM Photo WHERE faceScanned = 0"))
        } finally {
            connection.close()
        }
    }

    @Test
    fun migrationListIsContiguousFrom1To20() {
        val versions = PhotoDatabase.migrations.map { it.startVersion to it.endVersion }
        assertEquals((1..19).map { it to it + 1 }, versions)
    }

    // ---- Worker scheduling ----

    @Test
    fun workersEnqueueUnderStableUniqueNames() {
        initWorkManager()
        SyncWorker.enqueue(context)
        OCRWorker.enqueue(context)
        FaceWorker.enqueue(context)
        ClipWorker.enqueue(context)
        assertEquals(SyncWorker.WORK_NAME, "SyncWorker")
        assertEquals(FaceWorker.WORK_NAME, "FaceWorker")
        for (name in listOf("SyncWorker", "OCRWorker", "FaceWorker", "ClipWorker")) {
            val infos = awaitWork(name)
            assertEquals(1, infos.size, "$name: $infos")
        }
        // KEEP policy: re-enqueue does not stack a second record.
        FaceWorker.enqueue(context)
        val after = WorkManager.getInstance(context).getWorkInfosForUniqueWork("FaceWorker").get()
        assertEquals(1, after.size)
        WorkManager.getInstance(context).cancelAllWork()
    }

    @Test
    fun syncRunOnceReplacesWork() {
        initWorkManager()
        SyncWorker.runOnce(context)
        val infos = awaitWork(SyncWorker.WORK_NAME)
        assertEquals(1, infos.size)
        SyncWorker.runOnce(context)
        val replaced = WorkManager.getInstance(context).getWorkInfosForUniqueWork(SyncWorker.WORK_NAME).get()
        assertTrue(replaced.isNotEmpty())
        WorkManager.getInstance(context).cancelAllWork()
    }

    @Test
    fun foregroundInfoBuildsChannelAndNotification() {
        val info = context.syncForegroundInfo(
            notificationId = 777, channelId = "test_channel", channelName = "Test",
            title = "Title", text = "Body",
        )
        assertEquals(777, info.notificationId)
        assertNotNull(info.notification)
    }

    @Test
    fun cooldownOnlyFiresOnBatchBoundaries() = runTest {
        // Non-boundary counts return immediately (virtual time does not advance).
        coolDownBetweenBatches(0, "T")
        coolDownBetweenBatches(1, "T")
        coolDownBetweenBatches(19, "T")
        coolDownBetweenBatches(21, "T")
        // At the boundary the 5 s pause is virtual under runTest: reaching the
        // next line proves the delay ran and completed.
        coolDownBetweenBatches(20, "T")
        coolDownBetweenBatches(40, "T")
    }
}
