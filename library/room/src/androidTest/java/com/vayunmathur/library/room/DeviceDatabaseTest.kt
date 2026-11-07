package com.vayunmathur.library.room

import android.content.Context
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vayunmathur.library.util.databases
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@Entity
data class DeviceItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)

@Dao
interface DeviceItemDao {
    @Upsert
    suspend fun upsert(item: DeviceItem): Long

    @Query("SELECT * FROM DeviceItem ORDER BY id")
    suspend fun all(): List<DeviceItem>
}

@Database(entities = [DeviceItem::class], version = 1, exportSchema = false)
abstract class DeviceDatabase : RoomDatabase() {
    abstract fun itemDao(): DeviceItemDao
}

@Database(entities = [DeviceItem::class], version = 1, exportSchema = false)
abstract class DevicePlainDatabase : RoomDatabase() {
    abstract fun itemDao(): DeviceItemDao
}

private class DeviceRepository(context: Context, name: String, password: String?) :
    RoomRepository<DeviceDatabase>(context, DeviceDatabase::class, name, password) {

    val database: DeviceDatabase get() = db
}

/**
 * The half of :library:room that needs the SQLCipher native library: opening
 * encrypted databases, exporting/importing them, and encrypting a plaintext
 * file in place. None of it exists on a host JVM.
 */
@RunWith(AndroidJUnit4::class)
class DeviceDatabaseTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun tearDown() {
        databases.values.forEach { runCatching { it.close() } }
        databases.clear()
    }

    private fun deleteDb(name: String) {
        val file = context.getDatabasePath(name)
        file.delete()
        File("${file.path}-wal").delete()
        File("${file.path}-shm").delete()
        File("${file.path}-journal").delete()
    }

    @Test
    fun rowsRoundTripThroughEncryptedDatabase() = runBlocking {
        deleteDb("device-round-trip")
        val repo = DeviceRepository(context, "device-round-trip", "pw")
        repo.database.itemDao().upsert(DeviceItem(name = "alpha"))
        repo.database.itemDao().upsert(DeviceItem(name = "beta"))
        assertEquals(listOf("alpha", "beta"), repo.database.itemDao().all().map { it.name })
        // The file on disk must not be a plaintext SQLite database.
        val header = context.getDatabasePath("device-round-trip").readBytes().copyOf(16)
        assertFalse(
            "the database file should be encrypted at rest",
            header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun defaultPassphraseComesFromTheKeystoreHelper() = runBlocking {
        deleteDb("device-helper-1")
        deleteDb("device-helper-2")
        // First build: no key yet, so one is generated and a passphrase stored.
        val first = context.buildDatabase(DeviceDatabase::class.java, null, null, "device-helper-1")
        first.itemDao().upsert(DeviceItem(name = "one"))
        // Second build: the stored key is read back and the passphrase decrypted.
        val second = context.buildDatabaseUncached(
            DevicePlainDatabase::class.java, null, null, "device-helper-2",
        )
        second.itemDao().upsert(DeviceItem(name = "two"))
        assertEquals(listOf("one"), first.itemDao().all().map { it.name })
        assertEquals(listOf("two"), second.itemDao().all().map { it.name })
        second.close()
    }

    @Test
    fun exportThenImportRestoresTheRows() = runBlocking {
        deleteDb("device-backup")
        val repo = DeviceRepository(context, "device-backup", "pw")
        repo.database.itemDao().upsert(DeviceItem(name = "kept"))
        // Close the Room instance before the codec opens the file directly.
        repo.database.close()
        databases.clear()

        val plaintext = File(context.cacheDir, "backup-plaintext.db")
        if (plaintext.exists()) plaintext.delete()
        SqlCipherDbCodec.exportDatabase(context, "device-backup", "pw", plaintext)
        assertTrue(plaintext.exists())
        val header = plaintext.readBytes().copyOf(16)
        assertTrue(
            "the export should be a plaintext SQLite file",
            header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.UTF_8)),
        )

        SqlCipherDbCodec.importDatabase(context, "device-backup", "pw", plaintext)
        val restored = DeviceRepository(context, "device-backup", "pw")
        assertEquals(listOf("kept"), restored.database.itemDao().all().map { it.name })
    }

    @Test
    fun reifiedDefaultsBuildThePasswordsDatabase() = runBlocking {
        deleteDb("passwords-db")
        val db = context.buildDatabase<DeviceDatabase>()
        db.itemDao().upsert(DeviceItem(name = "defaulted"))
        assertEquals(listOf("defaulted"), db.itemDao().all().map { it.name })
    }

    @Test
    fun aMagicHeaderedNonDatabaseIsSkippedNotDestroyed() {
        deleteDb("device-notadb")
        val file = context.getDatabasePath("device-notadb")
        file.parentFile?.mkdirs()
        // SQLite magic, then junk: SQLCipher's open refuses it as not a
        // database, which is a skip, and the original file stays put.
        file.writeBytes(
            "SQLite format 3\u0000".toByteArray(Charsets.UTF_8) +
                ByteArray(64) { (it * 13 + 3).toByte() },
        )
        encryptExistingDatabase(context, "device-notadb", "pw")
        assertTrue(file.exists())
        assertFalse("the temp copy is cleaned up on a skip", File("${file.path}_temp").exists())
    }

    @Test
    fun encryptExistingDatabaseConvertsAPlaintextFile() {
        deleteDb("device-plain")
        val file = context.getDatabasePath("device-plain")
        file.parentFile?.mkdirs()
        val plain = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        plain.execSQL("CREATE TABLE Note (body TEXT)")
        plain.execSQL("INSERT INTO Note VALUES ('hello')")
        plain.close()
        assertTrue(
            file.readBytes().copyOf(16)
                .contentEquals("SQLite format 3\u0000".toByteArray(Charsets.UTF_8)),
        )

        encryptExistingDatabase(context, "device-plain", "pw")

        val header = file.readBytes().copyOf(16)
        assertFalse(
            "after conversion the header must no longer be plaintext SQLite",
            header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.UTF_8)),
        )
        loadSqlCipher()
        val db = net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
            file.absolutePath, "pw", null,
            net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READONLY, null,
        )
        db.rawQuery("SELECT body FROM Note", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("hello", cursor.getString(0))
        }
        db.close()
    }
}
