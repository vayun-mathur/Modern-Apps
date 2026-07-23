package com.vayunmathur.library.room

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import com.vayunmathur.library.util.DatabaseHelper
import com.vayunmathur.library.util.DatabaseMigrations
import com.vayunmathur.library.util.databases
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets

private var sqlCipherLoaded = false
fun loadSqlCipher() {
    if (sqlCipherLoaded) return
    try {
        System.loadLibrary("sqlcipher")
        sqlCipherLoaded = true
    } catch (e: UnsatisfiedLinkError) {
        e.printStackTrace()
    }
}

inline fun <reified T : RoomDatabase> Context.buildDatabase(
    migrations: List<Migration>? = null,
    encryptionPassword: String? = null,
    dbName: String = "passwords-db",
    useDeviceProtectedStorage: Boolean = false
): T {
    loadSqlCipher()

    // Resolve migrations: explicit arg wins; otherwise read from the
    // database's companion object if it implements [DatabaseMigrations].
    val resolvedMigrations: List<Migration> = migrations ?: run {
        val companionField = try {
            T::class.java.getDeclaredField("Companion").apply { isAccessible = true }
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

    synchronized(databases) {
        if (databases[T::class] != null) return databases[T::class]!! as T

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
            T::class.java,
            dbName
        ).addMigrations(*resolvedMigrations.toTypedArray())
            .fallbackToDestructiveMigration()

        builder.openHelperFactory(SupportOpenHelperFactory(password.toByteArray(StandardCharsets.UTF_8)))

        val db = builder.build()
        databases[T::class] = db
        return db as T
    }
}

fun encryptExistingDatabase(context: Context, dbName: String, password: String) {
    loadSqlCipher()
    val dbFile = context.getDatabasePath(dbName)
    if (!dbFile.exists() || dbFile.length() < 16) return

    val isEncrypted = try {
        FileInputStream(dbFile).use { fis ->
            val header = ByteArray(16)
            if (fis.read(header) != 16) {
                true
            } else {
                !header.contentEquals("SQLite format 3\u0000".toByteArray(StandardCharsets.UTF_8))
            }
        }
    } catch (e: Exception) {
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
    } catch (e: net.zetetic.database.sqlcipher.SQLiteNotADatabaseException) {
        tempFile.delete()
    }
}
