package com.vayunmathur.health.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs every Room migration against a real SQLite file.
 *
 * In-memory databases are created at the latest version, so without this
 * the migration chain - the code that protects every existing install's
 * data on upgrade - never executes in tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationsTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    /** Minimal [SQLiteConnection] over a framework database. */
    private class FrameworkConnection(private val db: SQLiteDatabase) : SQLiteConnection {
        override fun prepare(sql: String): SQLiteStatement = FrameworkStatement(db, sql)
        override fun inTransaction(): Boolean = db.inTransaction()
        override fun close() = db.close()
    }

    private class FrameworkStatement(private val db: SQLiteDatabase, private val sql: String) : SQLiteStatement {
        override fun step(): Boolean {
            db.execSQL(sql)
            return false
        }
        override fun bindBlob(index: Int, value: ByteArray) = Unit
        override fun bindDouble(index: Int, value: Double) = Unit
        override fun bindLong(index: Int, value: Long) = Unit
        override fun bindText(index: Int, value: String) = Unit
        override fun bindNull(index: Int) = Unit
        override fun getBlob(index: Int): ByteArray = throw UnsupportedOperationException()
        override fun getDouble(index: Int): Double = throw UnsupportedOperationException()
        override fun getLong(index: Int): Long = throw UnsupportedOperationException()
        override fun getText(index: Int): String = throw UnsupportedOperationException()
        override fun isNull(index: Int): Boolean = throw UnsupportedOperationException()
        override fun getColumnCount(): Int = 0
        override fun getColumnName(index: Int): String = throw UnsupportedOperationException()
        override fun getColumnType(index: Int): Int = throw UnsupportedOperationException()
        override fun reset() = Unit
        override fun clearBindings() = Unit
        override fun close() = Unit
    }

    @Test
    fun `all migrations apply cleanly in order`() {
        val file = File(context.cacheDir, "migration-probe.db")
        file.delete()
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        // Minimal v4-era Record table: migration 4->5 only adds columns to
        // it, later migrations create their own tables.
        raw.execSQL(
            "CREATE TABLE Record (id TEXT NOT NULL, `index` INTEGER NOT NULL, type TEXT NOT NULL, " +
                "startTime INTEGER NOT NULL, endTime INTEGER NOT NULL, value REAL NOT NULL, " +
                "secondaryValue REAL NOT NULL, metadata TEXT, primaryKey TEXT NOT NULL PRIMARY KEY)"
        )
        val connection = FrameworkConnection(raw)
        runBlocking {
            HealthDatabase.migrations.forEach { migration -> migration.migrate(connection) }
        }

        // Tables the chain must have created by v12.
        val tables = mutableSetOf<String>()
        raw.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor ->
            while (cursor.moveToNext()) tables.add(cursor.getString(0))
        }
        assertTrue("VaccinationEntry" in tables, "vaccination table created: $tables")
        assertTrue("MedicationEntry" in tables, "medication table created")
        assertTrue("MedicationSchedule" in tables, "schedule table created")
        // 4->5 added the exercise columns to Record.
        val columns = mutableSetOf<String>()
        raw.rawQuery("PRAGMA table_info(Record)", null).use { cursor ->
            while (cursor.moveToNext()) columns.add(cursor.getString(1))
        }
        assertTrue("exercise_title" in columns, "exercise columns added: $columns")
        assertTrue("exercise_hasRoute" in columns)
        raw.close()
        file.delete()
    }

    @Test
    fun `migration chain spans version 4 to 12 without gaps`() {
        val migrations = HealthDatabase.migrations
        val sorted = migrations.sortedBy { it.startVersion }
        assertTrue(sorted.first().startVersion == 4)
        assertTrue(sorted.last().endVersion == 12)
        for (i in 0 until sorted.size - 1) {
            assertTrue(sorted[i].endVersion == sorted[i + 1].startVersion, "gap after version ${sorted[i].endVersion}")
        }
    }
}
