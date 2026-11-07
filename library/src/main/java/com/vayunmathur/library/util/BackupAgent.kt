package com.vayunmathur.library.util

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

abstract class BaseBackupAgent : BackupAgent() {
    
    open val dbConfigs: List<Pair<String, String>> = emptyList()
    open val datastoreNames: List<String> = emptyList()
    open val prefNames: List<String> = emptyList()
    open val extraFiles: List<File> = emptyList()
    open val dbCodec: DbBackupCodec? = null
    
    companion object {
        private const val BACKUP_KEY = "full_backup"
        private const val STATE_TIMESTAMP_BYTES = 8
    }

    override fun onBackup(
        oldState: ParcelFileDescriptor?,
        data: BackupDataOutput?,
        newState: ParcelFileDescriptor?
    ) {
        val backupFile = File(cacheDir, "backup.zip")
        FileOutputStream(backupFile).use { fos ->
            BackupHelper.performFullBackup(
                this,
                dbConfigs,
                datastoreNames,
                prefNames,
                extraFiles,
                fos,
                dbCodec
            )
        }

        val lastModified = backupFile.lastModified()
        
        // Simple state management: check if file changed. The old state is
        // read once into memory: a stream over the descriptor closes it when
        // closed, so the unchanged path cannot re-read it for its copy.
        val oldStateBytes = oldState?.let { readStateBytes(it) } ?: ByteArray(0)
        val oldTimestamp = if (oldStateBytes.size >= STATE_TIMESTAMP_BYTES) {
            java.nio.ByteBuffer.wrap(oldStateBytes).long
        } else {
            0L
        }
        if (lastModified > oldTimestamp) {
            val bytes = backupFile.readBytes()
            data?.writeEntityHeader(BACKUP_KEY, bytes.size)
            data?.writeEntityData(bytes, bytes.size)

            newState?.let { writeState(it, lastModified) }
        } else {
            newState?.let { writeStateBytes(it, oldStateBytes) }
        }
        
        backupFile.delete()
    }

    override fun onRestore(
        data: BackupDataInput?,
        appVersionCode: Int,
        newState: ParcelFileDescriptor?
    ) {
        while (data?.readNextHeader() == true) {
            if (data.key == BACKUP_KEY) {
                val dataSize = data.dataSize
                val buffer = ByteArray(dataSize)
                data.readEntityData(buffer, 0, dataSize)
                
                val restoreFile = File(cacheDir, "restore.zip")
                restoreFile.writeBytes(buffer)
                
                val extraFilesMapping = extraFiles.associateBy { it.name }
                
                restoreFile.inputStream().use { fis ->
                    BackupHelper.performFullRestore(
                        this,
                        dbConfigs,
                        datastoreNames,
                        prefNames,
                        extraFilesMapping,
                        fis,
                        dbCodec
                    )
                }
                
                restoreFile.delete()
                newState?.let { writeState(it, System.currentTimeMillis()) }
            } else {
                data.skipEntityData()
            }
        }
    }

    private fun readStateBytes(pfd: ParcelFileDescriptor): ByteArray =
        FileInputStream(pfd.fileDescriptor).use { it.readBytes() }

    private fun writeState(pfd: ParcelFileDescriptor, timestamp: Long) {
        FileOutputStream(pfd.fileDescriptor).use { fos ->
            fos.write(
                java.nio.ByteBuffer.allocate(STATE_TIMESTAMP_BYTES).putLong(timestamp).array()
            )
        }
    }
    
    private fun writeStateBytes(pfd: ParcelFileDescriptor, bytes: ByteArray) {
        FileOutputStream(pfd.fileDescriptor).use { fos ->
            fos.write(bytes)
        }
    }
}
