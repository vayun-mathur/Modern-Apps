package com.vayunmathur.openassistant.data

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AppDatabaseMigrationTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun migratesV1ToV4PreservingRows() = runTest {
        val name = "migration-test.db"
        ctx.deleteDatabase(name)
        val path = ctx.getDatabasePath(name)
        path.parentFile?.mkdirs()
        // Create a v1 database with the pre-migration schema.
        val raw = SQLiteDatabase.openOrCreateDatabase(path, null)
        raw.execSQL("CREATE TABLE IF NOT EXISTS `Conversation` (`title` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
        raw.execSQL("CREATE TABLE IF NOT EXISTS `Message` (`conversationId` INTEGER NOT NULL, `text` TEXT NOT NULL, `role` TEXT NOT NULL, `imagePaths` TEXT NOT NULL, `hasAudio` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
        raw.execSQL("INSERT INTO Conversation (title, timestamp) VALUES ('Old chat', 42)")
        raw.execSQL("INSERT INTO Message (conversationId, text, role, imagePaths, hasAudio, timestamp) VALUES (1, 'old msg', 'user', '[]', 0, 43)")
        raw.version = 1
        raw.close()

        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.migrations.toTypedArray())
            .allowMainThreadQueries()
            .build()
        try {
            val conv = db.conversationDao().getById(1L)
            assertNotNull(conv); assertEquals("Old chat", conv.title)
            val msg = db.messageDao().getByConversation(1L).single()
            assertEquals("old msg", msg.text); assertNull(msg.missingAppPackage); assertNull(msg.a2ui)
            // v2-v4 additions are writable after migration.
            db.messageDao().upsert(msg.copy(a2ui = "{}", missingAppPackage = "com.example"))
            assertEquals("{}", db.messageDao().getById(msg.id)?.a2ui)
            val memId = db.memoryDao().upsert(Memory("remembered"))
            assertTrue(memId > 0)
            assertEquals(listOf("remembered"), db.memoryDao().getAllFlow().first().map { it.content })
        } finally {
            db.close()
            val check = SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY)
            assertEquals(4, check.version)
            check.close()
            ctx.deleteDatabase(name)
            File(path.path + "-wal").delete(); File(path.path + "-shm").delete()
        }
    }

    private fun assertNull(value: Any?) = kotlin.test.assertNull(value)
}
