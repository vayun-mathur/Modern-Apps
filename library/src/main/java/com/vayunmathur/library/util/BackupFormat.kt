package com.vayunmathur.library.util

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream

interface BackupFormat {
    val mimeType: String
    val defaultFileName: String
    val needsPassword: Boolean
    suspend fun export(context: Context, password: String?, outputStream: OutputStream)
    suspend fun import(context: Context, password: String?, inputStream: InputStream)
}

class ZipBackupFormat(
    private val dbConfigs: List<Pair<String, String>> = emptyList(),
    private val datastoreNames: List<String> = emptyList(),
    private val prefNames: List<String> = emptyList(),
    private val extraFiles: List<File> = emptyList(),
    private val extraFilesMapping: Map<String, File> = extraFiles.associateBy { it.name },
    private val dbCodec: DbBackupCodec? = null,
) : BackupFormat {
    override val mimeType = "application/zip"
    override val defaultFileName = "backup.zip"
    override val needsPassword = false

    override suspend fun export(context: Context, password: String?, outputStream: OutputStream) {
        BackupHelper.performFullBackup(context, dbConfigs, datastoreNames, prefNames, extraFiles, outputStream, dbCodec)
    }

    override suspend fun import(context: Context, password: String?, inputStream: InputStream) {
        BackupHelper.performFullRestore(context, dbConfigs, datastoreNames, prefNames, extraFilesMapping, inputStream, dbCodec)
    }
}
