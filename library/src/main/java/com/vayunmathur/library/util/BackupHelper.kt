package com.vayunmathur.library.util

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Encodes/decodes an app database during backup. The SQLCipher-backed
 * implementation lives in `:library:room` ([com.vayunmathur.library.room.SqlCipherDbCodec]);
 * this interface keeps [BackupHelper] (and therefore `:library`/`:library:ui`)
 * free of any SQLCipher dependency. Apps that back up an encrypted DB inject
 * the codec; apps that only back up datastore/prefs/files leave it null.
 */
interface DbBackupCodec {
    fun exportDatabase(context: Context, dbName: String, password: String, outputFile: File)
    fun importDatabase(context: Context, dbName: String, password: String, inputFile: File)
}

object BackupHelper {
    private const val TAG = "BackupHelper"

    fun zipFiles(files: List<File>, baseDir: File, outputStream: OutputStream) {
        ZipOutputStream(BufferedOutputStream(outputStream)).use { zos ->
            files.forEach { root ->
                root.walkTopDown().filter { it.isFile }.forEach { file ->
                    val entryName = file.relativeTo(baseDir).path
                    zos.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
    }

    fun unzipFiles(inputStream: InputStream, targetDir: File) {
        ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val file = File(targetDir, entry.name)
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    file.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    fun performFullBackup(
        context: Context,
        dbConfigs: List<Pair<String, String>>, // dbName to password
        datastoreNames: List<String> = emptyList(),
        prefNames: List<String> = emptyList(),
        extraFiles: List<File>,
        outputStream: OutputStream,
        dbCodec: DbBackupCodec? = null,
    ) {
        val tempDir = File(context.cacheDir, "backup_temp_${System.currentTimeMillis()}")
        if (tempDir.exists()) tempDir.deleteRecursively()
        tempDir.mkdirs()

        val filesToZip = mutableListOf<File>()

        if (dbConfigs.isNotEmpty() && dbCodec == null) {
            Log.w(TAG, "performFullBackup: dbConfigs provided but no DbBackupCodec; skipping database export")
        }
        if (dbCodec != null) {
            dbConfigs.forEach { (dbName, password) ->
                val plainDbFile = File(tempDir, "$dbName.db")
                dbCodec.exportDatabase(context, dbName, password, plainDbFile)
                if (plainDbFile.exists() && plainDbFile.length() > 0) {
                    filesToZip.add(plainDbFile)
                } else {
                    Log.w(TAG, "performFullBackup: Database export failed or file is empty: $dbName")
                }
            }
        }

        datastoreNames.forEach { dsName ->
            val dsFile = File(context.filesDir, "datastore/$dsName.preferences_pb")
            val dsFileAlt = File(context.filesDir, "$dsName.preferences_pb")
            val actualFile = if (dsFile.exists()) dsFile else if (dsFileAlt.exists()) dsFileAlt else null
            if (actualFile != null) {
                val targetFile = File(tempDir, actualFile.name)
                actualFile.copyTo(targetFile, true)
                filesToZip.add(targetFile)
            }
        }

        prefNames.forEach { prefName ->
            val prefFile = File(context.dataDir, "shared_prefs/$prefName.xml")
            if (prefFile.exists()) {
                val targetFile = File(tempDir, prefFile.name)
                prefFile.copyTo(targetFile, true)
                filesToZip.add(targetFile)
            }
        }

        extraFiles.forEach { file ->
            if (file.exists()) {
                val targetFile = File(tempDir, file.name)
                if (file.isDirectory) {
                    file.copyRecursively(targetFile, true)
                } else {
                    file.copyTo(targetFile, true)
                }
                filesToZip.add(targetFile)
            } else {
                Log.w(TAG, "performFullBackup: Extra file does not exist: ${file.absolutePath}")
            }
        }

        if (filesToZip.isEmpty()) {
            Log.e(TAG, "performFullBackup: NO FILES TO BACKUP!")
        }

        zipFiles(filesToZip, tempDir, outputStream)
        tempDir.deleteRecursively()
    }

    fun performFullRestore(
        context: Context,
        dbConfigs: List<Pair<String, String>>,
        datastoreNames: List<String> = emptyList(),
        prefNames: List<String> = emptyList(),
        extraFilesMapping: Map<String, File>, // filename in zip to target File
        inputStream: InputStream,
        dbCodec: DbBackupCodec? = null,
    ) {
        val tempDir = File(context.cacheDir, "restore_temp")
        if (tempDir.exists()) tempDir.deleteRecursively()
        tempDir.mkdirs()

        unzipFiles(inputStream, tempDir)

        if (dbConfigs.isNotEmpty() && dbCodec == null) {
            Log.w(TAG, "performFullRestore: dbConfigs provided but no DbBackupCodec; skipping database import")
        }
        if (dbCodec != null) {
            dbConfigs.forEach { (dbName, password) ->
                val plainDbFile = File(tempDir, "$dbName.db")
                if (plainDbFile.exists()) {
                    dbCodec.importDatabase(context, dbName, password, plainDbFile)
                }
            }
        }

        datastoreNames.forEach { dsName ->
            val dsFile = File(tempDir, "$dsName.preferences_pb")
            if (dsFile.exists()) {
                val targetFile1 = File(context.filesDir, "datastore/$dsName.preferences_pb")
                val targetFile2 = File(context.filesDir, "$dsName.preferences_pb")
                val targetFile = if (dsName == "datastore_default") targetFile2 else targetFile1

                targetFile.parentFile?.mkdirs()
                dsFile.copyTo(targetFile, true)
            }
        }

        prefNames.forEach { prefName ->
            val prefFile = File(tempDir, "$prefName.xml")
            if (prefFile.exists()) {
                val targetFile = File(context.dataDir, "shared_prefs/$prefName.xml")
                targetFile.parentFile?.mkdirs()
                prefFile.copyTo(targetFile, true)
            }
        }

        extraFilesMapping.forEach { (zipName, targetFile) ->
            val extractedFile = File(tempDir, zipName)
            if (extractedFile.exists()) {
                if (targetFile.exists()) targetFile.deleteRecursively()
                if (extractedFile.isDirectory) {
                    extractedFile.copyRecursively(targetFile, true)
                } else {
                    targetFile.parentFile?.mkdirs()
                    extractedFile.copyTo(targetFile, true)
                }
            }
        }

        tempDir.deleteRecursively()
    }
}
