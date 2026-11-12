package com.vayunmathur.notes.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The exact v3 `Note` DDL Room generated before tags existed (columns in entity order). */
private const val V3_NOTE_DDL =
    "CREATE TABLE IF NOT EXISTS `Note` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`title` TEXT NOT NULL, `content` TEXT NOT NULL, `position` REAL NOT NULL, `blocks` TEXT, " +
        "`syncId` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL)"

private const val V3_SNAPSHOT_DDL =
    "CREATE TABLE IF NOT EXISTS `SyncSnapshot` (`syncId` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
        "`contentHash` TEXT NOT NULL, `localUpdatedAt` INTEGER NOT NULL, `remoteModified` INTEGER NOT NULL, " +
        "`lastSyncedAt` INTEGER NOT NULL, PRIMARY KEY(`syncId`))"

/**
 * v3 -> v4 (#913) on raw SQLite: [NoteDatabase.MIGRATION_3_4] adds `tags`
 * NOT NULL DEFAULT '[]', and rows that existed before read back as `[]`.
 */
class NoteTagsMigrationTest {
    private val conn: SQLiteConnection = BundledSQLiteDriver().open(":memory:").apply {
        execSQL(V3_NOTE_DDL)
        execSQL(V3_SNAPSHOT_DDL)
        execSQL(
            "INSERT INTO Note (title, content, position, blocks, syncId, updatedAt, deleted) VALUES " +
                "('kept', 'body', 1.0, NULL, 'aaaa', 100, 0), ('gone', '', 2.0, '{}', 'bbbb', 200, 1)",
        )
    }

    @AfterTest fun close() = conn.close()

    @Test fun addsNotNullTagsColumnDefaultingToEmptyArray() {
        runBlocking { NoteDatabase.MIGRATION_3_4.migrate(conn) }
        conn.prepare("PRAGMA table_info(`Note`)").use {
            var found = false
            while (it.step()) {
                if (it.getText(1) == "tags") {
                    found = true
                    assertEquals("TEXT", it.getText(2))
                    assertEquals(1L, it.getLong(3), "tags must be NOT NULL")
                    assertEquals("'[]'", it.getText(4))
                }
            }
            assertTrue(found, "tags column missing")
        }
        conn.prepare("SELECT title, tags, syncId, updatedAt, deleted FROM Note ORDER BY id").use {
            assertTrue(it.step())
            assertEquals("kept", it.getText(0)); assertEquals("[]", it.getText(1))
            assertEquals("aaaa", it.getText(2)); assertEquals(100L, it.getLong(3)); assertEquals(0L, it.getLong(4))
            assertTrue(it.step())
            assertEquals("gone", it.getText(0)); assertEquals("[]", it.getText(1)); assertEquals(1L, it.getLong(4))
        }
    }

    @Test fun isRegisteredAsTheLastMigrationAndBumpsToFour() {
        val last = NoteDatabase.migrations.last()
        assertEquals(3 to 4, last.startVersion to last.endVersion)
        assertTrue(last === NoteDatabase.MIGRATION_3_4)
    }
}

/**
 * The same upgrade through Room itself on a real v3 database file, so Room's
 * own schema validation runs against the migrated table (a mismatch between
 * the hand-written DDL and the entity would throw on open), and the old rows
 * come back through the DAO as untagged notes that can then be tagged.
 */
@RunWith(RobolectricTestRunner::class)
class NoteTagsRoomMigrationTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val name = "notes-tags-migration-test.db"

    @AfterTest fun cleanup() {
        ctx.deleteDatabase(name)
    }

    @Test fun roomOpensV3FileMigratesAndValidates() = runTest {
        ctx.deleteDatabase(name)
        val path = ctx.getDatabasePath(name)
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).apply {
            execSQL(V3_NOTE_DDL)
            execSQL(V3_SNAPSHOT_DDL)
            execSQL(
                "INSERT INTO Note (title, content, position, blocks, syncId, updatedAt, deleted) " +
                    "VALUES ('old note', 'old body', 3.0, NULL, 'sync-old', 1234, 0)",
            )
            version = 3
            close()
        }

        val db = Room.databaseBuilder(ctx, NoteDatabase::class.java, name)
            .addMigrations(*NoteDatabase.migrations.toTypedArray())
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.noteDao()
            val old = dao.getBySyncId("sync-old")!!
            assertEquals("old note", old.title)
            assertEquals(emptyList(), old.tags)
            // Migration is not an edit: the sync clock is untouched.
            assertEquals(1234L, old.updatedAt)
            dao.upsertRaw(old.copy(tags = listOf("work", "home office")))
            assertEquals(listOf("work", "home office"), dao.getBySyncId("sync-old")!!.tags)
        } finally {
            db.close()
            SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use {
                assertEquals(4, it.version)
            }
            File(path.path + "-wal").delete(); File(path.path + "-shm").delete()
        }
    }
}
