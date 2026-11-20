package com.vayunmathur.library.util

import android.content.Context
import com.vayunmathur.library.log.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
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

    /**
     * Imports [inputFile] as the live database [dbName].
     *
     * Contract: implementations must build and verify the replacement away
     * from the live files first, and only swap it in once it is known good —
     * a corrupt or unreadable [inputFile] must leave the live database
     * untouched. Callers must close any open handle to the live database
     * before calling this: the codec does not own Room's cached instances,
     * and an open handle keeps the pre-swap file alive on Linux.
     */
    fun importDatabase(context: Context, dbName: String, password: String, inputFile: File)
}

/** ZIP packing, restore and database file handling shared by the backup formats. */
object BackupHelper {
    private const val TAG = "BackupHelper"
    private const val DATASTORE_DEFAULT_NAME = "datastore_default"

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
        val tempDir = createTimestampedTempDir(context)
        try {
            val filesToZip = mutableListOf<File>()

            warnOnMissingCodec(dbConfigs, dbCodec, ::warnBackupSkipped)
            exportDatabases(context, dbConfigs, dbCodec, tempDir, filesToZip)
            stageDatastoreFiles(context, datastoreNames, tempDir, filesToZip)
            stagePrefFiles(context, prefNames, tempDir, filesToZip)
            stageExtraFiles(extraFiles, tempDir, filesToZip)

            if (filesToZip.isEmpty()) {
                Log.error(TAG, "performFullBackup: NO FILES TO BACKUP!")
            }

            zipFiles(filesToZip, tempDir, outputStream)
        } finally {
            tempDir.deleteRecursively()
        }
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
        // Unique per restore: a fixed dir would collide between concurrent
        // restores and could be wiped from under an in-flight one.
        val tempDir = createUniqueTempDir(context, "restore_temp")
        try {
            unzipFiles(inputStream, tempDir)

            warnOnMissingCodec(dbConfigs, dbCodec, ::warnRestoreSkipped)
            importDatabases(context, dbConfigs, dbCodec, tempDir)
            restoreDatastoreFiles(context, datastoreNames, tempDir)
            restorePrefFiles(context, prefNames, tempDir)
            restoreExtraFiles(extraFilesMapping, tempDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun createTimestampedTempDir(context: Context): File =
        createUniqueTempDir(context, "backup_temp")

    private fun createUniqueTempDir(context: Context, prefix: String): File {
        val tempDir = File(
            context.cacheDir,
            "${prefix}_${System.currentTimeMillis()}_${UUID.randomUUID()}",
        )
        tempDir.mkdirs()
        return tempDir
    }

    /**
     * Replaces [target] with [source]'s bytes atomically: copy to a sibling
     * temp file on the same filesystem, then rename over the target. A kill
     * mid-copy tears only the temp file; the live file is either fully old
     * or fully new, never partial.
     */
    private fun atomicReplaceFile(source: File, target: File) {
        val parent = target.parentFile
            ?: throw IOException("Target has no parent directory: ${target.path}")
        parent.mkdirs()
        val tmp = File(parent, "${target.name}.tmp-${UUID.randomUUID()}")
        try {
            source.copyTo(tmp, overwrite = true)
            if (!tmp.renameTo(target)) {
                throw IOException("Failed to rename ${tmp.path} over ${target.path}")
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * Swaps [target] (file or directory) for [source]: stage [source] as a
     * sibling of [target], rename the old target aside, rename the staged
     * copy in, then delete the old one. A kill mid-swap leaves either the
     * old tree or the new tree in place (plus at most a `.restore-bak-*`
     * sibling the next restore's aside-rename does not collide with,
     * because the suffix is unique per call).
     */
    private fun swapIntoPlace(source: File, target: File) {
        // Used for both files and directories: a plain rename-over cannot
        // replace a directory with a file (or vice versa), so extras always
        // go through the aside-swap, unlike datastore/prefs (always files).
        val parent = target.parentFile
            ?: throw IOException("Target has no parent directory: ${target.path}")
        parent.mkdirs()
        val staging = File(parent, "${target.name}.restore-tmp-${UUID.randomUUID()}")
        val aside = File(parent, "${target.name}.restore-bak-${UUID.randomUUID()}")
        try {
            if (source.isDirectory) {
                source.copyRecursively(staging, overwrite = false)
            } else {
                source.copyTo(staging, overwrite = false)
            }
            val movedAside = target.exists() && target.renameTo(aside)
            if (target.exists() && !movedAside) {
                throw IOException("Failed to move ${target.path} aside for restore")
            }
            if (!staging.renameTo(target)) {
                // Put the old tree back before giving up.
                if (movedAside) aside.renameTo(target)
                throw IOException("Failed to rename ${staging.path} over ${target.path}")
            }
            if (movedAside) aside.deleteRecursively()
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun warnOnMissingCodec(
        dbConfigs: List<Pair<String, String>>,
        dbCodec: DbBackupCodec?,
        warn: () -> Unit
    ) {
        if (dbConfigs.isNotEmpty() && dbCodec == null) warn()
    }

    private fun warnBackupSkipped() {
        Log.status(
            TAG,
            "performFullBackup: dbConfigs provided but no DbBackupCodec; skipping database export"
        )
    }

    private fun warnRestoreSkipped() {
        Log.status(
            TAG,
            "performFullRestore: dbConfigs provided but no DbBackupCodec; skipping database import"
        )
    }

    private fun exportDatabases(
        context: Context,
        dbConfigs: List<Pair<String, String>>,
        dbCodec: DbBackupCodec?,
        tempDir: File,
        filesToZip: MutableList<File>
    ) {
        if (dbCodec == null) return
        dbConfigs.forEach { (dbName, password) ->
            val plainDbFile = File(tempDir, "$dbName.db")
            dbCodec.exportDatabase(context, dbName, password, plainDbFile)
            if (plainDbFile.exists() && plainDbFile.length() > 0) {
                filesToZip.add(plainDbFile)
            } else {
                Log.status(TAG, "performFullBackup: Database export failed or file is empty: $dbName")
            }
        }
    }

    private fun stageDatastoreFiles(
        context: Context,
        datastoreNames: List<String>,
        tempDir: File,
        filesToZip: MutableList<File>
    ) {
        datastoreNames.forEach { dsName ->
            findDatastoreFile(context, dsName)?.let { actualFile ->
                stageFile(actualFile, tempDir, filesToZip)
            }
        }
    }

    private fun findDatastoreFile(context: Context, dsName: String): File? {
        val primary = File(context.filesDir, "datastore/$dsName.preferences_pb")
        if (primary.exists()) return primary
        val legacy = File(context.filesDir, "$dsName.preferences_pb")
        return legacy.takeIf { it.exists() }
    }

    private fun stageFile(source: File, tempDir: File, filesToZip: MutableList<File>) {
        val targetFile = File(tempDir, source.name)
        source.copyTo(targetFile, true)
        filesToZip.add(targetFile)
    }

    private fun stagePrefFiles(
        context: Context,
        prefNames: List<String>,
        tempDir: File,
        filesToZip: MutableList<File>
    ) {
        prefNames.map { File(context.dataDir, "shared_prefs/$it.xml") }
            .filter { it.exists() }
            .forEach { stageFile(it, tempDir, filesToZip) }
    }

    private fun stageExtraFiles(
        extraFiles: List<File>,
        tempDir: File,
        filesToZip: MutableList<File>
    ) {
        extraFiles.forEach { file ->
            if (!file.exists()) {
                Log.status(TAG, "performFullBackup: Extra file does not exist: ${file.absolutePath}")
                return@forEach
            }
            val targetFile = File(tempDir, file.name)
            if (file.isDirectory) {
                file.copyRecursively(targetFile, true)
            } else {
                file.copyTo(targetFile, true)
            }
            filesToZip.add(targetFile)
        }
    }

    private fun importDatabases(
        context: Context,
        dbConfigs: List<Pair<String, String>>,
        dbCodec: DbBackupCodec?,
        tempDir: File
    ) {
        if (dbCodec == null) return
        dbConfigs.forEach { (dbName, password) ->
            val plainDbFile = File(tempDir, "$dbName.db")
            if (plainDbFile.exists()) {
                dbCodec.importDatabase(context, dbName, password, plainDbFile)
            }
        }
    }

    private fun restoreDatastoreFiles(
        context: Context,
        datastoreNames: List<String>,
        tempDir: File
    ) {
        datastoreNames.forEach { dsName ->
            val extracted = File(tempDir, "$dsName.preferences_pb")
            if (!extracted.exists()) return@forEach
            val targetFile = datastoreTarget(context, dsName)
            atomicReplaceFile(extracted, targetFile)
        }
    }

    private fun datastoreTarget(context: Context, dsName: String): File {
        // The default store writes to the filesDir root; named stores live under datastore/.
        if (dsName == DATASTORE_DEFAULT_NAME) {
            return File(context.filesDir, "$dsName.preferences_pb")
        }
        return File(context.filesDir, "datastore/$dsName.preferences_pb")
    }

    private fun restorePrefFiles(context: Context, prefNames: List<String>, tempDir: File) {
        prefNames.forEach { prefName ->
            val extracted = File(tempDir, "$prefName.xml")
            if (!extracted.exists()) return@forEach
            val targetFile = File(context.dataDir, "shared_prefs/$prefName.xml")
            atomicReplaceFile(extracted, targetFile)
        }
    }

    private fun restoreExtraFiles(extraFilesMapping: Map<String, File>, tempDir: File) {
        extraFilesMapping.forEach { (zipName, targetFile) ->
            val extractedFile = File(tempDir, zipName)
            if (!extractedFile.exists()) return@forEach
            swapIntoPlace(extractedFile, targetFile)
        }
    }
}
