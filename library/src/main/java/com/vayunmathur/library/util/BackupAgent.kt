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
    
    private val BACKUP_KEY = "full_backup"

    override fun onBackup(
        oldState: ParcelFileDescriptor?,
        data: BackupDataOutput?,
        newState: ParcelFileDescriptor?
    ) {
        val backupFile = File(cacheDir, "backup.zip")
        FileOutputStream(backupFile).use { fos ->
            BackupHelper.performFullBackup(this, dbConfigs, datastoreNames, prefNames, extraFiles, fos, dbCodec)
        }

        val lastModified = backupFile.lastModified()
        
        // Simple state management: check if file changed
        val oldTimestamp = oldState?.let { readState(it) } ?: 0L
        if (lastModified > oldTimestamp) {
            val bytes = backupFile.readBytes()
            data?.writeEntityHeader(BACKUP_KEY, bytes.size)
            data?.writeEntityData(bytes, bytes.size)
            
            newState?.let { writeState(it, lastModified) }
        } else {
            oldState?.let { newState?.let { it1 -> copyState(it, it1) } }
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
                    BackupHelper.performFullRestore(this, dbConfigs, datastoreNames, prefNames, extraFilesMapping, fis, dbCodec)
                }
                
                restoreFile.delete()
                newState?.let { writeState(it, System.currentTimeMillis()) }
            } else {
                data.skipEntityData()
            }
        }
    }

    private fun readState(pfd: ParcelFileDescriptor): Long {
        FileInputStream(pfd.fileDescriptor).use { fis ->
            val bytes = fis.readBytes()
            if (bytes.size >= 8) {
                return java.nio.ByteBuffer.wrap(bytes).long
            }
        }
        return 0L
    }

    private fun writeState(pfd: ParcelFileDescriptor, timestamp: Long) {
        FileOutputStream(pfd.fileDescriptor).use { fos ->
            fos.write(java.nio.ByteBuffer.allocate(8).putLong(timestamp).array())
        }
    }
    
    private fun copyState(old: ParcelFileDescriptor, new: ParcelFileDescriptor) {
        FileInputStream(old.fileDescriptor).use { fis ->
            FileOutputStream(new.fileDescriptor).use { fos ->
                fis.copyTo(fos)
            }
        }
    }
}
