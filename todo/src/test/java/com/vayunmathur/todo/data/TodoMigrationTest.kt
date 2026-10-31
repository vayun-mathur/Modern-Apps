package com.vayunmathur.todo.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Runs the real v2→v3 migration against a v2 database and checks the result matches the
 * exported v3 schema Room validates on open.
 */
class TodoMigrationTest {
    private lateinit var conn: SQLiteConnection
    private val savedZone = TimeZone.getDefault()

    @BeforeTest
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        conn = BundledSQLiteDriver().open(":memory:")
        conn.execSQL(
            "CREATE TABLE `Todo` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, " +
                "`done` INTEGER NOT NULL, `position` REAL NOT NULL, `dueAt` INTEGER, `syncId` TEXT NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, `notes` TEXT NOT NULL DEFAULT '', " +
                "`createdAt` INTEGER NOT NULL DEFAULT 0)",
        )
        conn.execSQL(
            "CREATE TABLE `SyncSnapshot` (`syncId` TEXT NOT NULL, `kind` TEXT NOT NULL, `contentHash` TEXT NOT NULL, " +
                "`localUpdatedAt` INTEGER NOT NULL, `remoteModified` INTEGER NOT NULL, " +
                "`lastSyncedAt` INTEGER NOT NULL, PRIMARY KEY(`syncId`))",
        )
    }

    @AfterTest
    fun tearDown() {
        conn.close()
        TimeZone.setDefault(savedZone)
    }

    private fun migrate() = runBlocking { TodoMigrations.all.single { it.startVersion == 2 }.migrate(conn) }

    private fun columns(table: String): Set<String> {
        val out = mutableSetOf<String>()
        conn.prepare("PRAGMA table_info(`$table`)").use { while (it.step()) out += it.getText(1) }
        return out
    }

    private fun schemaColumns(table: String): Set<String> {
        val file = File("schemas/com.vayunmathur.todo.data.TodoDatabase/3.json")
        val entities = Json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
        val entity = entities.first { it.jsonObject["tableName"]!!.jsonPrimitive.content == table }.jsonObject
        return entity["fields"]!!.jsonArray.map { it.jsonObject["columnName"]!!.jsonPrimitive.content }.toSet()
    }

    @Test
    fun `migrated tables carry exactly the v3 columns`() {
        migrate()
        assertEquals(schemaColumns("Todo"), columns("Todo"))
        assertEquals(schemaColumns("TodoList"), columns("TodoList"))
    }

    @Test
    fun `existing rows get v3 defaults`() {
        conn.execSQL("INSERT INTO Todo (title, done, position, syncId, updatedAt, deleted) VALUES ('a', 0, 1.0, 's1', 5, 0)")
        migrate()
        conn.prepare("SELECT tags, priority, dueAllDay, blockedBy, listSyncId FROM Todo").use {
            it.step()
            assertEquals("[]", it.getText(0))
            assertEquals(0L, it.getLong(1))
            assertEquals(1L, it.getLong(2))
            assertEquals("[]", it.getText(3))
            assertEquals(true, it.isNull(4))
        }
    }

    @Test
    fun `legacy utc-midnight due dates become local midnight of the same date`() {
        // 2026-10-14T00:00Z, as the old date picker stored it.
        val utcMidnight = 1_791_936_000_000L
        conn.execSQL("INSERT INTO Todo (title, done, position, dueAt, syncId, updatedAt, deleted) VALUES ('a', 0, 1.0, $utcMidnight, 's1', 5, 0)")
        migrate()
        conn.prepare("SELECT dueAt FROM Todo").use {
            it.step()
            // 2026-10-14T00:00-07:00 (PDT).
            assertEquals(utcMidnight + 7 * 3_600_000L, it.getLong(0))
        }
    }

    @Test
    fun `non-midnight due values are left alone`() {
        val odd = 1_791_936_000_123L
        conn.execSQL("INSERT INTO Todo (title, done, position, dueAt, syncId, updatedAt, deleted) VALUES ('a', 0, 1.0, $odd, 's1', 5, 0)")
        migrate()
        conn.prepare("SELECT dueAt FROM Todo").use {
            it.step()
            assertEquals(odd, it.getLong(0))
        }
    }
}
