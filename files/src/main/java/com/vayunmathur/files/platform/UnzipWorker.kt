package com.vayunmathur.files.platform

import android.content.Context
import com.vayunmathur.library.log.Log
import androidx.work.WorkerParameters
import com.vayunmathur.files.R
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream

private const val TAG_UNZIP = "UnzipWorker"

class UnzipWorker(context: Context, params: WorkerParameters) : ProgressNotificationWorker(
    context,
    params,
    channelId = "unzip_progress_channel",
    notificationId = 2,
    channelNameRes = R.string.unzip_channel_name,
    contentTitleRes = R.string.unzipping,
) {

    override suspend fun doWork(): Result {
        val zipPathString = inputData.getString("zip_path") ?: return Result.failure()
        val destPathString = inputData.getString("dest_path") ?: return Result.failure()
        // The caller checks the archive's declared size against this too, but the declaration comes
        // from the archive and can lie, so the real ceiling is enforced here as bytes are written.
        val budget = inputData.getLong("size_budget", Long.MAX_VALUE)

        createNotificationChannel()
        setForeground(createForegroundInfo(0))

        // Everything this run created, so a rejected archive does not leave its partial output behind.
        val extracted = mutableListOf<File>()

        return try {
            extractArchive(File(zipPathString), File(destPathString), budget, extracted)
            Result.success()
        } catch (e: ArchiveTooLargeException) {
            Log.status(TAG_UNZIP, "archive exceeds its extraction budget", e)
            cleanUpPartial(extracted)
            Result.failure()
        } catch (e: IOException) {
            Log.status(TAG_UNZIP, "failed to extract archive", e)
            cleanUpPartial(extracted)
            Result.failure()
        } finally {
            cancelNotification()
        }
    }

    private fun extractArchive(
        zipFile: File,
        destDir: File,
        budget: Long,
        extracted: MutableList<File>
    ) {
        val destDirCanonical = destDir.canonicalFile
        val zipFileSize = zipFile.length()
        val progress = ProgressTracker(zipFileSize)
        FileInputStream(zipFile).use { fis ->
            ZipInputStream(ProgressInputStream(fis, progress)).use { zipInputStream ->
                var totalWritten = 0L
                var entryCount = 0
                var entry = zipInputStream.nextEntry
                while (entry != null) {
                    if (++entryCount > ArchiveLimits.MAX_ENTRIES) {
                        throw ArchiveTooLargeException()
                    }
                    totalWritten = extractOneEntry(
                        zipInputStream,
                        destDirCanonical,
                        entry.name,
                        entry.isDirectory,
                        extracted,
                        budget,
                        totalWritten
                    )
                    zipInputStream.closeEntry()
                    entry = zipInputStream.nextEntry
                }
            }
        }
    }

    private fun extractOneEntry(
        zipInputStream: ZipInputStream,
        destDirCanonical: File,
        entryName: String,
        isDirectory: Boolean,
        extracted: MutableList<File>,
        budget: Long,
        totalWritten: Long
    ): Long {
        val entryFile = ArchiveLimits.resolveEntry(destDirCanonical, entryName) ?: return totalWritten
        if (isDirectory) {
            if (entryFile.isFile) throw IOException("Archive entry is an existing file: $entryName")
            entryFile.mkdirs()
            return totalWritten
        }
        // Never overwrite what was already in the destination. Truncating an
        // existing file here would destroy it even when a later entry then
        // fails (budget, corrupt data), because cleanup can only delete the
        // partial output, not restore the original contents. Refusing keeps
        // extraction all-or-nothing: this run's new files are cleaned up and
        // every pre-existing file stays exactly as it was. This also covers
        // an archive listing the same name twice.
        if (entryFile.exists()) throw IOException("File already exists: $entryName")
        entryFile.parentFile?.mkdirs()
        extracted.add(entryFile)
        FileOutputStream(entryFile).use { out ->
            return totalWritten + copyBounded(zipInputStream, out, budget - totalWritten)
        }
    }

    private fun cleanUpPartial(extracted: List<File>) {
        extracted.forEach { runCatching { it.delete() } }
    }

    /**
     * Copies one entry, refusing to write more than [remaining] bytes.
     *
     * A plain copy would follow the entry to its end, which is exactly what a zip bomb relies on: a
     * few kilobytes of input can ask for gigabytes of output.
     */
    private fun copyBounded(input: InputStream, out: OutputStream, remaining: Long): Long {
        if (remaining <= 0) throw ArchiveTooLargeException()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var written = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            if (written + read > remaining) throw ArchiveTooLargeException()
            out.write(buffer, 0, read)
            written += read
        }
        return written
    }

    /** Counts compressed bytes read so the notification shows real progress. */
    private inner class ProgressTracker(private val total: Long) {
        private var bytesRead = 0L

        fun addBytes(count: Long) {
            bytesRead += count
            updateProgress(bytesRead, total)
        }
    }

    /** Forwards reads while reporting compressed progress to [tracker]. */
    private inner class ProgressInputStream(
        input: FileInputStream,
        private val tracker: ProgressTracker
    ) : FilterInputStream(input) {
        override fun read(): Int {
            val byte = super.read()
            if (byte != -1) tracker.addBytes(1)
            return byte
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            val count = super.read(buffer, off, len)
            if (count != -1) tracker.addBytes(count.toLong())
            return count
        }
    }
}
