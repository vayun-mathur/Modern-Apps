package com.vayunmathur.library.room

import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.log.NativeLoader
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import com.vayunmathur.library.util.DatabaseHelper
import com.vayunmathur.library.util.DatabaseMigrations
import com.vayunmathur.library.util.databases
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver
import java.io.File
import java.io.FileInputStream

private var sqlCipherLoaded = false
fun loadSqlCipher() {
    if (sqlCipherLoaded) return
    // Delegates to the shared guard (catches every Throwable, keeps its own
    // logging host-JVM-safe, treats an already-loaded report as success).
    // Depending on :library:log cannot cycle: that module has no dependencies
    // at all, and common-conventions-library already puts it on this module's
    // classpath, so no build-file change is needed. On failure the flag stays
    // false, so the next call retries — same contract as the hand-rolled try.
    if (NativeLoader.load("sqlcipher", TAG)) sqlCipherLoaded = true
}

private const val TAG = "SqlCipher"

/**
 * Reified convenience wrapper. Prefer obtaining databases through a
 * [RoomRepository] subclass rather than calling this directly — a follow-up
 * change restricts direct construction so every database has a single owner.
 */
inline fun <reified T : RoomDatabase> Context.buildDatabase(
    migrations: List<Migration>? = null,
    encryptionPassword: String? = null,
    dbName: String = "passwords-db",
    useDeviceProtectedStorage: Boolean = false
): T = buildDatabase(
    T::class.java,
    migrations,
    encryptionPassword,
    dbName,
    useDeviceProtectedStorage,
)

/**
 * Non-reified core builder (cached by database class). Takes the [dbClass]
 * explicitly so it can be called from generic code (e.g. [RoomRepository]) that
 * only has a `Class`/`KClass`. The reified overload above delegates here.
 */
fun <T : RoomDatabase> Context.buildDatabase(
    dbClass: Class<T>,
    migrations: List<Migration>? = null,
    encryptionPassword: String? = null,
    dbName: String = "passwords-db",
    useDeviceProtectedStorage: Boolean = false
): T {
    synchronized(databases) {
        @Suppress("UNCHECKED_CAST")
        databases[dbClass.kotlin]?.let { return it as T }
        val db = openRoomDatabase(dbClass, migrations, encryptionPassword, dbName, useDeviceProtectedStorage)
        databases[dbClass.kotlin] = db
        return db
    }
}

/**
 * Builds a database instance **without** touching the process-wide cache. Intended
 * for one-shot/auxiliary opens where the cached instance can't be reused — e.g. a
 * one-time legacy-migration read of an old, differently-named file of the same
 * class. Callers own the returned instance and must `close()` it. Kept in the
 * library so app modules never call the Room builder directly.
 */
fun <T : RoomDatabase> Context.buildDatabaseUncached(
    dbClass: Class<T>,
    migrations: List<Migration>? = null,
    encryptionPassword: String? = null,
    dbName: String = "passwords-db",
    useDeviceProtectedStorage: Boolean = false
): T = openRoomDatabase(dbClass, migrations, encryptionPassword, dbName, useDeviceProtectedStorage)

/** Shared build logic for [buildDatabase] and [buildDatabaseUncached] (no caching). */
private fun <T : RoomDatabase> Context.openRoomDatabase(
    dbClass: Class<T>,
    migrations: List<Migration>?,
    encryptionPassword: String?,
    dbName: String,
    useDeviceProtectedStorage: Boolean,
): T {
    loadSqlCipher()

    // Resolve migrations: explicit arg wins; otherwise read from the
    // database's companion object if it implements [DatabaseMigrations].
    val resolvedMigrations: List<Migration> = migrations ?: run {
        val companionField = try {
            dbClass.getDeclaredField("Companion").apply { isAccessible = true }
        } catch (_: NoSuchFieldException) {
            null
        }
        val companionInstance = companionField?.get(null)
        (companionInstance as? DatabaseMigrations)?.migrations ?: emptyList()
    }

    val targetContext = if (useDeviceProtectedStorage) {
        val deviceContext = this.createDeviceProtectedStorageContext()
        val sharedPrefsName = "secure_prefs" // Matches DatabaseHelper.sharedPrefsName

        if (!deviceContext.getDatabasePath(dbName).exists() && this.getDatabasePath(dbName).exists()) {
            deviceContext.moveDatabaseFrom(this, dbName)
        }
        if (deviceContext.getSharedPreferences(sharedPrefsName, Context.MODE_PRIVATE).all.isEmpty() &&
            this.getSharedPreferences(sharedPrefsName, Context.MODE_PRIVATE).all.isNotEmpty()) {
            deviceContext.moveSharedPreferencesFrom(this, sharedPrefsName)
        }
        deviceContext
    } else {
        this
    }

    var password = encryptionPassword
    if (password == null) {
        val helper = DatabaseHelper(targetContext)
        if (!helper.isKeyGenerated()) {
            helper.generateKey()
            val cipher = helper.getCipherForEncryption()
            password = helper.createAndStorePassphrase(cipher)
        } else {
            val cipher = helper.getCipherForDecryption()
            password = helper.decryptPassphrase(cipher)
        }
    }

    encryptExistingDatabase(targetContext, dbName, password)

    val builder = Room.databaseBuilder(
        targetContext,
        dbClass,
        dbName
    ).addMigrations(*resolvedMigrations.toTypedArray())

    builder.setDriver(SQLCipherDriver(password.toByteArray(Charsets.UTF_8), null, null))

    // One connection, not Room's default pool of five.
    //
    // Room picks the pool from the journal mode in `RoomDatabase.Builder.build()`: TRUNCATE gets
    // `SingleConnection`, anything else - including the AUTOMATIC default, which resolves to WAL -
    // gets `MultipleConnection(4, 1)`. That is fine for plain SQLite, where opening a connection is
    // cheap, but SQLCipher derives the page key on every open, and at the default 256,000 PBKDF2
    // iterations that is on the order of a second each. Five of them is the several-second blank
    // screen on a cold start.
    //
    // This is a restoration rather than a new tuning decision: before the Room 3 migration the
    // builder set `setJournalMode(TRUNCATE)`, which under Room 3 means exactly this pool. The
    // migration swapped the open-helper for the driver and dropped that line, silently turning one
    // key derivation into five. Keeping WAL and asking for a single connection explicitly is
    // strictly better than going back to TRUNCATE, which would also slow every write down.
    //
    // The cost is that reads no longer run concurrently with a write. These are single-user apps
    // with small databases, and it is what they did for their whole history before the migration.
    builder.setSingleConnectionPool()

    return builder.build()
}

fun encryptExistingDatabase(context: Context, dbName: String, password: String) {
    loadSqlCipher()
    val dbFile = context.getDatabasePath(dbName)
    if (!dbFile.exists() || dbFile.length() < SQLITE_HEADER_BYTES) return

    val isEncrypted = try {
        FileInputStream(dbFile).use { fis ->
            val header = ByteArray(SQLITE_HEADER_BYTES)
            if (fis.read(header) != SQLITE_HEADER_BYTES) {
                true
            } else {
                !header.contentEquals(SQLITE_MAGIC)
            }
        }
    } catch (expected: java.io.IOException) {
        Log.status(TAG, "Failed to read database header; assuming encrypted", expected)
        true
    }

    if (isEncrypted) return

    // It's not encrypted. Let's encrypt it.
    val tempFile = context.getDatabasePath("${dbName}_temp")
    if (tempFile.exists()) tempFile.delete()
    tempFile.parentFile?.mkdirs()
    tempFile.createNewFile()

    try {
        val db = net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            "",
            null,
            net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE,
            null
        )
        db.rawExecSQL("PRAGMA cipher_compatibility = 4")
        db.rawExecSQL("ATTACH DATABASE '${tempFile.absolutePath}' AS encrypted KEY '${password}'")
        db.rawExecSQL("SELECT sqlcipher_export('encrypted')")
        db.rawExecSQL("DETACH DATABASE encrypted")
        db.close()

        // Delete the original plain database and its journal/WAL files
        dbFile.delete()
        File("${dbFile.path}-wal").delete()
        File("${dbFile.path}-shm").delete()
        File("${dbFile.path}-journal").delete()

        tempFile.renameTo(dbFile)
    } catch (expected: net.zetetic.database.sqlcipher.SQLiteNotADatabaseException) {
        Log.status(TAG, "Database is not a plain SQLite file; skipping encryption", expected)
        tempFile.delete()
    }
}

private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.UTF_8)
private const val SQLITE_HEADER_BYTES = 16
