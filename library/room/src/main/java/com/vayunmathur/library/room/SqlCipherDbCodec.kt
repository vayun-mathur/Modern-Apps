package com.vayunmathur.library.room

import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.util.DbBackupCodec
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.io.IOException
import java.util.UUID

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
            Log.status(TAG, "exportDatabase: Database file does not exist!")
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

    /**
     * Imports [inputFile] without ever putting the live database at risk
     * until the replacement is proven good:
     *
     * 1. Build the encrypted replacement in a sibling temp file.
     * 2. Verify it: reopen with [password] and require
     *    `PRAGMA integrity_check` to report `ok`.
     * 3. Swap: rename live files to `*.bak-*`, rename the temp in, delete
     *    the backup. On swap failure the `.bak-*` files are renamed back.
     *
     * Any failure before the swap (missing/corrupt input, wrong password,
     * failed verify) deletes only the temp file — the live DB and its
     * `-wal`/`-shm`/`-journal` sidecars stay byte-for-byte untouched.
     *
     * Callers must close the live database first (see
     * [DbBackupCodec.importDatabase]): this codec does not own Room's
     * cached instances, and an open handle would keep reading the
     * pre-swap file after the rename.
     */
    override fun importDatabase(context: Context, dbName: String, password: String, inputFile: File) {
        loadSqlCipher()
        val dbFile = context.getDatabasePath(dbName)
        if (!inputFile.exists() || inputFile.length() == 0L) {
            throw IOException("importDatabase: input file missing or empty: ${inputFile.path}")
        }
        val parent = dbFile.parentFile
            ?: throw IOException("importDatabase: database has no parent directory: ${dbFile.path}")
        parent.mkdirs()

        val tempFile = File(parent, "${dbFile.name}.import-${UUID.randomUUID()}.tmp")
        try {
            buildEncryptedCopy(inputFile, tempFile, password)
            verifyEncryptedCopy(tempFile, password)
            swapIntoPlace(tempFile, dbFile)
        } finally {
            // No-op once the swap renamed the temp into place.
            deleteWithSidecars(tempFile)
        }
    }

    /** Creates a fresh encrypted DB at [tempFile] holding [inputFile]'s contents. */
    private fun buildEncryptedCopy(inputFile: File, tempFile: File, password: String) {
        if (tempFile.exists()) tempFile.delete()
        tempFile.createNewFile()
        val db = SQLiteDatabase.openDatabase(
            tempFile.canonicalPath,
            password,
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
            null
        )
        try {
            db.rawExecSQL("PRAGMA cipher_compatibility = 4")
            db.rawExecSQL("ATTACH DATABASE '${inputFile.canonicalPath}' AS plaintext KEY ''")
            try {
                db.rawExecSQL("SELECT sqlcipher_export('main', 'plaintext')")
            } finally {
                db.rawExecSQL("DETACH DATABASE plaintext")
            }
        } finally {
            db.close()
        }
    }

    /**
     * Reopens [file] with [password] and requires SQLite's integrity check
     * to pass. A corrupt plaintext input produces a temp DB that fails
     * here (or fails to open at all), so the live DB is never swapped for
     * garbage. Migrations are deliberately not run here: the codec does
     * not know the app's Room schema; Room runs them on first open, the
     * same as after every restore before this change.
     */
    private fun verifyEncryptedCopy(file: File, password: String) {
        if (!file.exists() || file.length() == 0L) {
            throw IOException("importDatabase: imported database is missing or empty")
        }
        val db = SQLiteDatabase.openDatabase(
            file.canonicalPath,
            password,
            null,
            SQLiteDatabase.OPEN_READONLY,
            null
        )
        try {
            val result = db.rawQuery("PRAGMA integrity_check", null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            if (!result.equals("ok", ignoreCase = true)) {
                throw IOException("importDatabase: integrity_check failed: $result")
            }
        } finally {
            db.close()
        }
    }

    /**
     * Renames [tempFile] over [dbFile], keeping the old files as `.bak-*`
     * siblings until the new file is in place. Renames within one directory
     * are atomic on the same filesystem, so a kill mid-swap leaves either
     * the old DB or the new DB under [dbFile]'s name.
     */
    private fun swapIntoPlace(tempFile: File, dbFile: File) {
        val suffix = ".bak-${UUID.randomUUID()}"
        val liveFiles = listOf(dbFile, sidecar(dbFile, "-wal"), sidecar(dbFile, "-shm"), sidecar(dbFile, "-journal"))
        val backedUp = mutableListOf<Pair<File, File>>()
        try {
            liveFiles.forEach { live ->
                if (!live.exists()) return@forEach
                val bak = File("${live.path}$suffix")
                if (!live.renameTo(bak)) {
                    throw IOException("importDatabase: failed to move ${live.path} aside")
                }
                backedUp += live to bak
            }
            if (!tempFile.renameTo(dbFile)) {
                throw IOException("importDatabase: failed to move imported database into place")
            }
            // Carry any temp sidecars (e.g. a WAL not yet checkpointed at
            // close) in under the live name so they stay paired with it.
            listOf("-wal", "-shm", "-journal").forEach { ext ->
                val tmpSide = File("${tempFile.path}$ext")
                if (tmpSide.exists()) tmpSide.renameTo(sidecar(dbFile, ext))
            }
        } catch (e: Exception) {
            // Roll back: remove any partially swapped new file, restore old.
            if (dbFile.exists() && backedUp.any { it.first == dbFile }) dbFile.delete()
            backedUp.asReversed().forEach { (live, bak) ->
                if (bak.exists()) bak.renameTo(live)
            }
            throw e
        }
        backedUp.forEach { (_, bak) -> bak.delete() }
    }

    private fun sidecar(dbFile: File, ext: String): File = File("${dbFile.path}$ext")

    private fun deleteWithSidecars(file: File, includeSelf: Boolean = true) {
        if (includeSelf) file.delete()
        File("${file.path}-wal").delete()
        File("${file.path}-shm").delete()
        File("${file.path}-journal").delete()
    }
}
