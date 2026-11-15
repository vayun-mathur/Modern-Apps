package com.vayunmathur.passwords.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the real v1 -> v5 upgrade path against an in-memory SQLite connection
 * and checks the data survives: a legacy `userId` becomes the `username`, and
 * the passkey table gains its sync + link columns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationsExtraTest {

    @Test fun fullUpgradePathPreservesRows() = runBlocking {
        val connection = AndroidSQLiteDriver().open(":memory:")
        try {
            // v1 schema for Password (pre username/email split, pre sync columns).
            connection.execSQL(
                "CREATE TABLE `Password` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, `userId` TEXT NOT NULL, `password` TEXT NOT NULL, " +
                    "`totpSecret` TEXT, `websites` TEXT NOT NULL)",
            )
            connection.execSQL(
                "INSERT INTO `Password` (`id`, `name`, `userId`, `password`, `totpSecret`, `websites`) " +
                    "VALUES (1, 'GitHub', 'octocat', 'pw', NULL, 'github.com')",
            )

            val migrations = PasswordDatabase.migrations.sortedBy { it.startVersion }
            assertEquals(listOf(1, 2, 3, 4), migrations.map { it.startVersion })
            for (migration in migrations) {
                migration.migrate(connection)
            }

            // The legacy login survived the table-recreate migration as the username.
            val passwordColumns = columnNames(connection, "Password")
            assertTrue("username" in passwordColumns)
            assertTrue("syncId" in passwordColumns)
            connection.prepare("SELECT `username`, `email`, `password` FROM `Password` WHERE `id` = 1").use { stmt ->
                assertTrue(stmt.step())
                assertEquals("octocat", stmt.getText(0))
                assertEquals("", stmt.getText(1))
                assertEquals("pw", stmt.getText(2))
            }
            // Passkey was created by 1->2 and gained sync + link columns by 2->3 and 4->5.
            val passkeyColumns = columnNames(connection, "Passkey")
            assertTrue("syncId" in passkeyColumns)
            assertTrue("linkedPasswordSyncId" in passkeyColumns)
            // The snapshot baseline table exists after the upgrade.
            assertTrue("SyncSnapshot" in tableNames(connection))
        } finally {
            connection.close()
        }
    }

    private fun tableNames(connection: SQLiteConnection): Set<String> {
        val names = mutableSetOf<String>()
        connection.prepare("SELECT name FROM sqlite_master WHERE type = 'table'").use { stmt ->
            while (stmt.step()) names += stmt.getText(0)
        }
        return names
    }

    private fun columnNames(connection: SQLiteConnection, table: String): Set<String> {
        val names = mutableSetOf<String>()
        connection.prepare("PRAGMA table_info(`$table`)").use { stmt ->
            while (stmt.step()) names += stmt.getText(1)
        }
        return names
    }
}
