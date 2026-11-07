package com.vayunmathur.files.platform

import android.content.Context
import com.vayunmathur.library.log.Log
import androidx.work.WorkerParameters
import com.vayunmathur.files.R
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val TAG_ZIP = "ZipWorker"

class ZipWorker(context: Context, params: WorkerParameters) : ProgressNotificationWorker(
    context,
    params,
    channelId = "zip_progress_channel",
    notificationId = 1,
    channelNameRes = R.string.zip_channel_name,
    contentTitleRes = R.string.archiving,
) {

    override suspend fun doWork(): Result {
        val sourcePaths = inputData.getStringArray("source_paths") ?: return Result.failure()
        val destPathString = inputData.getString("dest_path") ?: return Result.failure()
        val destFile = File(destPathString)

        createNotificationChannel()
        setForeground(createForegroundInfo(0))

        // Refusals happen before the try below on purpose: that block deletes
        // the destination on failure, which is only safe for a file this run
        // created - never for one it refused to touch.
        try {
            checkDestination(sourcePaths, destFile)
        } catch (e: IOException) {
            Log.status(TAG_ZIP, "refused archive destination", e)
            cancelNotification()
            return Result.failure()
        }

        return try {
            archiveSources(sourcePaths, destFile)
            Result.success()
        } catch (e: IOException) {
            Log.status(TAG_ZIP, "failed to create archive", e)
            // The destination was verified absent above, so anything at
            // destFile now is this run's partial output: remove it rather
            // than leaving a corrupt archive that looks like a finished one.
            runCatching { destFile.delete() }
            Result.failure()
        } finally {
            cancelNotification()
        }
    }

    /**
     * Opening the destination truncates it, so never start if it already
     * exists, is one of the sources, or sits inside a source directory
     * (it would be read back into its own archive as it grows).
     */
    private fun checkDestination(sourcePaths: Array<String>, destFile: File) {
        if (destFile.exists()) throw IOException("Archive already exists: ${destFile.path}")
        val destCanonical = destFile.canonicalFile
        for (sourcePath in sourcePaths) {
            val source = File(sourcePath).canonicalFile
            val insideSource = destCanonical == source ||
                (source.isDirectory &&
                    destCanonical.path.startsWith(source.path + File.separator))
            if (insideSource) throw IOException("Archive would contain itself: ${destFile.path}")
        }
    }

    private fun archiveSources(sourcePaths: Array<String>, destFile: File) {
        var totalSize = 0L
        sourcePaths.forEach { totalSize += calculateTotalSize(File(it)) }

        var bytesZipped = 0L
        FileOutputStream(destFile).use { fos ->
            ZipOutputStream(fos).use { zipOut ->
                sourcePaths.forEach { pathString ->
                    addToZip(File(pathString), "", zipOut) { bytes ->
                        bytesZipped += bytes
                        updateProgress(bytesZipped, totalSize)
                    }
                }
            }
        }
    }

    private fun calculateTotalSize(file: File): Long {
        if (!file.exists()) return 0L
        return if (file.isDirectory) {
            file.listFiles()?.sumOf { calculateTotalSize(it) } ?: 0L
        } else {
            file.length()
        }
    }

    private fun addToZip(
        file: File,
        base: String,
        zipOutputStream: ZipOutputStream,
        onProgress: (Long) -> Unit
    ) {
        if (!file.exists()) return
        val entryName = if (base.isEmpty()) file.name else "$base/${file.name}"

        if (file.isDirectory) {
            addDirectoryToZip(file, entryName, zipOutputStream, onProgress)
        } else {
            addFileToZip(file, entryName, zipOutputStream, onProgress)
        }
    }

    private fun addDirectoryToZip(
        dir: File,
        entryName: String,
        zipOutputStream: ZipOutputStream,
        onProgress: (Long) -> Unit
    ) {
        val children = dir.listFiles()
        if (children == null || children.isEmpty()) {
            zipOutputStream.putNextEntry(ZipEntry("$entryName/"))
            zipOutputStream.closeEntry()
        } else {
            children.forEach { child ->
                addToZip(child, entryName, zipOutputStream, onProgress)
            }
        }
    }

    private fun addFileToZip(
        file: File,
        entryName: String,
        zipOutputStream: ZipOutputStream,
        onProgress: (Long) -> Unit
    ) {
        zipOutputStream.putNextEntry(ZipEntry(entryName))
        val countingOut = CountingOutputStream(zipOutputStream, onProgress)
        FileInputStream(file).use { input ->
            input.copyTo(countingOut)
        }
        countingOut.flush()
        zipOutputStream.closeEntry()
    }

    private class CountingOutputStream(
        out: OutputStream,
        private val onProgress: (Long) -> Unit
    ) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) {
            super.write(b, off, len)
            onProgress(len.toLong())
        }

        override fun write(b: Int) {
            super.write(b)
            onProgress(1)
        }
    }
}
