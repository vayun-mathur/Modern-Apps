package com.vayunmathur.library.room

import android.content.Context
import android.util.Log
import com.vayunmathur.library.util.DbBackupCodec
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * SQLCipher-backed [DbBackupCodec]. Exports an encrypted Room database to a
 * plaintext SQLite file for inclusion in a backup zip, and re-imports it into a
 * freshly encrypted database on restore. Lives in `:library:room` so the backup
 * plumbing in `:library`/`:library:ui` stays free of any SQLCipher dependency.
 */
object SqlCipherDbCodec : DbBackupCodec {
    private const val TAG = "SqlCipherDbCodec"

    override fun exportDatabase(context: Context, dbName: String, password: String, outputFile: File) {
        loadSqlCipher()
        val dbFile = context.getDatabasePath(dbName)
        if (!dbFile.exists()) {
            Log.w(TAG, "exportDatabase: Database file does not exist!")
            return
        }

        // Ensure parent directory exists and use canonical path to avoid symlink issues
        val parent = outputFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        if (outputFile.exists()) {
            outputFile.delete()
        }
        outputFile.createNewFile()

        val outputPath = outputFile.canonicalPath
        val sourcePath = dbFile.canonicalPath

        // Use SQLCipher to export to an unencrypted database
        val db = SQLiteDatabase.openDatabase(
            sourcePath,
            password,
            null,
            SQLiteDatabase.OPEN_READWRITE,
            null
        )
        db.rawExecSQL("PRAGMA cipher_compatibility = 4")
        db.rawExecSQL("ATTACH DATABASE '$outputPath' AS plaintext KEY ''")
        db.rawExecSQL("SELECT sqlcipher_export('plaintext')")
        db.rawExecSQL("DETACH DATABASE plaintext")
        db.close()
    }

    override fun importDatabase(context: Context, dbName: String, password: String, inputFile: File) {
        loadSqlCipher()
        val dbFile = context.getDatabasePath(dbName)

        val inputPath = inputFile.canonicalPath
        val outputPath = dbFile.canonicalPath

        // Delete existing database files
        dbFile.delete()
        File("$outputPath-wal").delete()
        File("$outputPath-shm").delete()
        File("$outputPath-journal").delete()

        // Create a new encrypted database from the plaintext input
        val db = SQLiteDatabase.openDatabase(
            outputPath,
            password,
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
            null
        )
        db.rawExecSQL("PRAGMA cipher_compatibility = 4")
        db.rawExecSQL("ATTACH DATABASE '$inputPath' AS plaintext KEY ''")
        db.rawExecSQL("SELECT sqlcipher_export('main', 'plaintext')")
        db.rawExecSQL("DETACH DATABASE plaintext")
        db.close()
    }
}
