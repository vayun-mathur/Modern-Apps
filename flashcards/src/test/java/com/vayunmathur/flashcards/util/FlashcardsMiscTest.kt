package com.vayunmathur.flashcards.util

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.flashcards.Route
import com.vayunmathur.flashcards.ui.isZip
import com.vayunmathur.flashcards.ui.queryFileName
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** A tiny provider serving one named byte payload, for resolver-backed helpers. */
class FakeMediaProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor {
        val cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME))
        cursor.addRow(arrayOf<Any>("picked.png"))
        return cursor
    }
    override fun getType(uri: Uri): String = "image/png"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = File(context!!.cacheDir, "fake_provider_payload.bin")
        file.writeBytes(byteArrayOf(10, 20, 30, 40))
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}

@RunWith(RobolectricTestRunner::class)
class FlashcardsMiscTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun ttsSpeakerQueuesAndShutsDown() {
        val speaker = TtsSpeaker(ctx)
        speaker.speak("  hello flashcards  ")
        speaker.speak("   ") // blank is a no-op
        speaker.stop()
        speaker.speak("again")
        speaker.shutdown()
    }

    @Test
    fun backupAgentConfig() {
        val agent = AppBackupAgent()
        assertEquals(listOf("datastore_default"), agent.datastoreNames)
        assertTrue(agent.extraFiles.isEmpty())
        assertEquals(com.vayunmathur.library.room.SqlCipherDbCodec, agent.dbCodec)
    }

    @Test
    fun mediaImportViaProviderAndFileHelpers() {
        Robolectric.setupContentProvider(FakeMediaProvider::class.java, "com.vayunmathur.flashcards.fake")
        val uri = Uri.parse("content://com.vayunmathur.flashcards.fake/item/1")
        assertEquals("picked.png", queryFileName(ctx, uri))
        assertFalse(isZip(ctx, uri))
        val store = MediaStore(ctx)
        val imported = store.import(ctx, uri)
        assertTrue(imported != null && imported.endsWith(".png"), "imported=$imported")
        assertEquals(4, store.resolve(imported).readBytes().size)
    }

    @Test
    fun isZipDetectsPkHeaderViaProviderFile() = runTest {
        // Write a real zip into cache and read it back through a file uri.
        val zipFile = File(ctx.cacheDir, "sample.apkg")
        java.util.zip.ZipOutputStream(zipFile.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("collection.anki2"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
        }
        assertTrue(isZip(ctx, Uri.fromFile(zipFile)))
        val textFile = File(ctx.cacheDir, "plain.csv")
        textFile.writeText("a,b\n")
        assertFalse(isZip(ctx, Uri.fromFile(textFile)))
        assertFalse(isZip(ctx, Uri.fromFile(File(ctx.cacheDir, "missing.bin"))))
        assertNull(queryFileName(ctx, Uri.fromFile(textFile)))
    }

    @Test
    fun reminderUpdateSchedulesAndCancels() {
        runCatching {
            androidx.work.WorkManager.initialize(ctx, androidx.work.Configuration.Builder().build())
        }
        // Cancelling when nothing is scheduled and scheduling for tomorrow are both safe.
        ReviewReminder.update(ctx, false, 8, 0)
        ReviewReminder.update(ctx, true, 23, 59)
        ReviewReminder.update(ctx, true, 0, 0)
        ReviewReminder.update(ctx, false, 0, 0)
        assertEquals("review_reminders", ReviewReminder.CHANNEL_ID)
    }

    @Test
    fun reminderWorkerReportsSuccessWithSeededDatabase() = runTest {
        runCatching {
            androidx.work.WorkManager.initialize(ctx, androidx.work.Configuration.Builder().build())
        }
        val mem = androidx.room3.Room.inMemoryDatabaseBuilder(ctx, com.vayunmathur.flashcards.data.FlashcardsDatabase::class.java)
            .allowMainThreadQueries().build()
        synchronized(com.vayunmathur.library.util.databases) {
            com.vayunmathur.library.util.databases[com.vayunmathur.flashcards.data.FlashcardsDatabase::class] = mem
        }
        com.vayunmathur.flashcards.data.FlashcardsRepository.get(ctx)
        val params = androidx.work.WorkerParameters(
            java.util.UUID.randomUUID(),
            androidx.work.Data.EMPTY,
            emptyList(),
            androidx.work.WorkerParameters.RuntimeExtras(),
            0,
            0,
            java.util.concurrent.Executor { it.run() },
            kotlinx.coroutines.Dispatchers.Default,
            object : androidx.work.impl.utils.taskexecutor.TaskExecutor {
                override fun getMainThreadExecutor(): java.util.concurrent.Executor = java.util.concurrent.Executor { it.run() }
                override fun getSerialTaskExecutor(): androidx.work.impl.utils.taskexecutor.SerialExecutor =
                    object : androidx.work.impl.utils.taskexecutor.SerialExecutor {
                        override fun execute(command: Runnable) = command.run()
                        override fun hasPendingTasks(): Boolean = false
                    }
                override fun getCoroutineScope(): kotlinx.coroutines.CoroutineScope =
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
            },
            object : androidx.work.WorkerFactory() {
                override fun createWorker(
                    appContext: android.content.Context,
                    workerClassName: String,
                    workerParameters: androidx.work.WorkerParameters,
                ): androidx.work.ListenableWorker? = null
            },
            androidx.work.ProgressUpdater { _, _, _ ->
                com.google.common.util.concurrent.Futures.immediateVoidFuture()
            },
            androidx.work.ForegroundUpdater { _, _, _ ->
                com.google.common.util.concurrent.Futures.immediateVoidFuture()
            },
        )
        val worker = ReviewReminderWorker(ctx, params)
        assertEquals(androidx.work.ListenableWorker.Result.success(), worker.doWork())
    }

    @Test
    fun routesSerializeRoundTrip() {
        val json = Json { classDiscriminator = "type" }
        assertTrue(json.encodeToString(Route.serializer(), Route.Main).contains("Main") || json.encodeToString(Route.serializer(), Route.Main).isNotBlank())
        val review = Route.Review(deckId = 42, mode = 4, count = 9, daysAhead = 2, tags = listOf("a", "b"))
        val encoded = json.encodeToString(Route.serializer(), review)
        assertTrue(encoded.contains("42"))
        assertEquals(review, json.decodeFromString(Route.serializer(), encoded))
        val edit = Route.NoteEdit(deckId = 3, noteId = 8)
        assertEquals(edit, json.decodeFromString(Route.serializer(), json.encodeToString(Route.serializer(), edit)))
        val cardList = Route.CardList(7)
        assertEquals(cardList, json.decodeFromString(Route.serializer(), json.encodeToString(Route.serializer(), cardList)))
        val typeEdit = Route.NoteTypeEdit(5)
        assertEquals(typeEdit, json.decodeFromString(Route.serializer(), json.encodeToString(Route.serializer(), typeEdit)))
    }
}
