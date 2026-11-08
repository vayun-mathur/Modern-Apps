package com.vayunmathur.appstore.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The full upgrade path from a v1 database: every migration runs in
 * order over a database shaped like the first release, the columns
 * and tables each version promised appear, and the rows the 3→4 and
 * 8→9 steps deliberately drop (unverifiable / split-less cache rows)
 * do not survive.
 */
class AppDatabaseMigrationTest {

    private fun openV1(): SQLiteConnection {
        val conn = BundledSQLiteDriver().open(":memory:")
        conn.execSQL(
            "CREATE TABLE CachedAppEntity (packageName TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL)",
        )
        conn.execSQL("CREATE TABLE RepoEntity (url TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL)")
        conn.execSQL("CREATE TABLE FavoriteEntity (id INTEGER NOT NULL PRIMARY KEY)")
        conn.execSQL(
            "INSERT INTO CachedAppEntity (packageName, name) VALUES ('com.example.old', 'Old')",
        )
        conn.execSQL("INSERT INTO RepoEntity (url, name) VALUES ('https://repo.example', 'Repo')")
        return conn
    }

    private fun columns(conn: SQLiteConnection, table: String): Set<String> {
        val out = mutableSetOf<String>()
        conn.prepare("PRAGMA table_info(`$table`)").use {
            while (it.step()) out += it.getText(1)
        }
        return out
    }

    private fun tableExists(conn: SQLiteConnection, table: String): Boolean =
        conn.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name=?").use {
            it.bindText(1, table)
            it.step()
        }

    private fun rowCount(conn: SQLiteConnection, table: String): Int =
        conn.prepare("SELECT COUNT(*) FROM `$table`").use {
            it.step()
            it.getInt(0)
        }

    @Test
    fun `migration chain is complete and ordered`() {
        assertEquals(
            listOf(1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 6, 6 to 7, 7 to 8, 8 to 9),
            AppDatabase.migrations.map { it.startVersion to it.endVersion },
        )
    }

    @Test
    fun `a v1 database upgrades to the v9 shape`() {
        val conn = openV1()
        runBlocking {
            for (migration in AppDatabase.migrations.sortedBy { it.startVersion }) {
                migration.migrate(conn)
            }
        }

        assertFalse(tableExists(conn, "FavoriteEntity"))
        assertTrue(tableExists(conn, "PinnedStampEntity"))
        assertTrue(tableExists(conn, "AccrescentTrustEntity"))

        val cached = columns(conn, "CachedAppEntity")
        assertTrue(
            cached.containsAll(
                setOf(
                    "targetSdk", "expectedSigners", "apkSha256", "license", "website",
                    "sourceCode", "screenshots", "featureGraphic", "rating", "ratingCount",
                    "installs", "updatedOn", "contentRating", "containsAds", "antiFeatures",
                    "whatsNew", "addedTimestamp", "reproducible", "tvScreenshots",
                    "requiredFeatures", "tvCompatible", "splitFiles",
                ),
            ),
            "columns were $cached",
        )
        // The pre-verification rows and repo rows are dropped, not carried over.
        assertEquals(0, rowCount(conn, "CachedAppEntity"))
        assertEquals(0, rowCount(conn, "RepoEntity"))
        conn.close()
    }

    @Test
    fun `rows cached after the trust upgrade survive to v9 except the split reset`() {
        val conn = openV1()
        runBlocking {
            AppDatabase.migrations.filter { it.startVersion < 4 }
                .sortedBy { it.startVersion }
                .forEach { it.migrate(conn) }
        }
        // A row cached under v4 rules (post trust-upgrade) survives the
        // column-only 4→5…7→8 steps, then the 8→9 split reset drops it.
        conn.execSQL(
            "INSERT INTO CachedAppEntity (packageName, name) VALUES ('com.example.new', 'New')",
        )
        runBlocking {
            AppDatabase.migrations.filter { it.startVersion in 4..7 }
                .sortedBy { it.startVersion }
                .forEach { it.migrate(conn) }
        }
        assertEquals(1, rowCount(conn, "CachedAppEntity"))

        runBlocking {
            AppDatabase.migrations.single { it.startVersion == 8 }.migrate(conn)
        }
        assertEquals(0, rowCount(conn, "CachedAppEntity"))
        conn.close()
    }
}
