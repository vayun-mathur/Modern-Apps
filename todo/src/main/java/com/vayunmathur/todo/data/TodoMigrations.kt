package com.vayunmathur.todo.data

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.vayunmathur.todo.domain.DueDates
import kotlinx.datetime.TimeZone

internal object TodoMigrations {
    // v2 adds the relay-carried `notes` + `createdAt` columns (drift fix): the relay
    // envelope always carried both, but the native model dropped them on persist.
    private val v1to2 = Migration(1, 2) {
        it.execSQL("ALTER TABLE `Todo` ADD COLUMN `notes` TEXT NOT NULL DEFAULT ''")
        it.execSQL("ALTER TABLE `Todo` ADD COLUMN `createdAt` INTEGER NOT NULL DEFAULT 0")
        it.execSQL("UPDATE `Todo` SET `createdAt` = `updatedAt` WHERE `createdAt` = 0")
    }

    // v3: lists, subtasks, tags, priority, timed due, start, recurrence, reminders.
    private val v2to3 = Migration(2, 3) { conn ->
        listOf(
            "`parentSyncId` TEXT",
            "`listSyncId` TEXT",
            "`section` TEXT",
            "`tags` TEXT NOT NULL DEFAULT '[]'",
            "`priority` INTEGER NOT NULL DEFAULT 0",
            "`dueAllDay` INTEGER NOT NULL DEFAULT 1",
            "`startAt` INTEGER",
            "`recurrence` TEXT",
            "`reminderAt` INTEGER",
            "`completedAt` INTEGER",
            "`estimateMinutes` INTEGER",
            "`blockedBy` TEXT NOT NULL DEFAULT '[]'",
        ).forEach { conn.execSQL("ALTER TABLE `Todo` ADD COLUMN $it") }
        conn.execSQL(
            "CREATE TABLE IF NOT EXISTS `TodoList` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`syncId` TEXT NOT NULL, `name` TEXT NOT NULL, `color` INTEGER, `icon` TEXT, " +
                "`position` REAL NOT NULL, `sections` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL)",
        )
        conn.execSQL("CREATE INDEX IF NOT EXISTS `index_Todo_syncId` ON `Todo` (`syncId`)")
        conn.execSQL("CREATE INDEX IF NOT EXISTS `index_Todo_listSyncId` ON `Todo` (`listSyncId`)")
        conn.execSQL("CREATE INDEX IF NOT EXISTS `index_Todo_parentSyncId` ON `Todo` (`parentSyncId`)")
        conn.execSQL("CREATE INDEX IF NOT EXISTS `index_TodoList_syncId` ON `TodoList` (`syncId`)")
        convertLegacyDueDates(conn, TimeZone.currentSystemDefault())
    }

    /** Pre-v3 all-day due values were the picker's UTC midnight; store local midnight instead. */
    private fun convertLegacyDueDates(conn: SQLiteConnection, zone: TimeZone) {
        val rows = mutableListOf<Pair<Long, Long>>()
        conn.prepare("SELECT `id`, `dueAt` FROM `Todo` WHERE `dueAt` IS NOT NULL").use { stmt ->
            while (stmt.step()) rows += stmt.getLong(0) to stmt.getLong(1)
        }
        conn.prepare("UPDATE `Todo` SET `dueAt` = ? WHERE `id` = ?").use { stmt ->
            for ((id, due) in rows) {
                val converted = DueDates.migrateLegacyAllDay(due, zone)
                if (converted == due) continue
                stmt.bindLong(1, converted)
                stmt.bindLong(2, id)
                stmt.step()
                stmt.reset()
            }
        }
    }

    val all: List<Migration> = listOf(v1to2, v2to3)
}
