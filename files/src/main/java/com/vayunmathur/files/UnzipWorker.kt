package com.vayunmathur.files

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.source
import java.util.zip.ZipInputStream

class UnzipWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val zipPathString = inputData.getString("zip_path") ?: return Result.failure()
        val destPathString = inputData.getString("dest_path") ?: return Result.failure()

        val zipPath = zipPathString.toPath()
        val destPath = destPathString.toPath()

        try {
            val fileSystem = FileSystem.SYSTEM
            fileSystem.read(zipPath) {
                ZipInputStream(inputStream()).use { zipInputStream ->
                    var entry = zipInputStream.nextEntry
                    while (entry != null) {
                        val entryPath = destPath.resolve(entry.name)
                        if (entry.isDirectory) {
                            fileSystem.createDirectories(entryPath)
                        } else {
                            fileSystem.createDirectories(entryPath.parent!!)
                            fileSystem.write(entryPath) {
                                writeAll(zipInputStream.source())
                            }
                        }
                        zipInputStream.closeEntry()
                        entry = zipInputStream.nextEntry
                    }
                }
            }
            return Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            return Result.failure()
        }
    }
}
