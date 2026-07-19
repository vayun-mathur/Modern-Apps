package com.vayunmathur.youpipe.util

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vayunmathur.library.room.buildDatabase
import com.vayunmathur.youpipe.data.DownloadedVideo
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.NetworkDataStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.Channels
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DownloadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    private val client = NetworkClient

    override suspend fun doWork(): Result = coroutineScope {
        val videoID = inputData.getLong("videoID", -1L)
        val videoUrl = inputData.getString("videoUrl") ?: return@coroutineScope Result.failure()
        val audioUrl = inputData.getString("audioUrl")

        val videoInfo =
            VideoInfo(
                name = inputData.getString("name") ?: "",
                videoID = videoID,
                duration = inputData.getLong("duration", 0L),
                views = inputData.getLong("views", 0L),
                uploadDate = Instant.fromEpochSeconds(inputData.getLong("uploadDate", 0L)),
                thumbnailURL = inputData.getString("thumbnailURL") ?: "",
                author = inputData.getString("author") ?: ""
            )

        val db = applicationContext.buildDatabase<SubscriptionDatabase>()
        val dir = File(applicationContext.getExternalFilesDir(null), "downloads")
        if (!dir.exists()) dir.mkdirs()

        val videoFile = File(dir, "$videoID.mp4")
        val audioFile = if (audioUrl != null) File(dir, "$videoID.m4a") else null

        val createdFiles = mutableListOf<File>()
        createdFiles.add(videoFile)
        audioFile?.let { createdFiles.add(it) }

        try {
            val videoWeight = if (audioUrl != null) 0.5 else 1.0

            // Simplified progress reporting for parallel:
            var videoP = 0.0
            var audioP = 0.0

            val vJob =
                async(Dispatchers.IO) {
                    downloadFileChunked(videoUrl, videoFile) { p ->
                        videoP = p
                        DownloadManager.updateProgress(
                            videoID,
                            (videoP * videoWeight) + (audioP * (1.0 - videoWeight))
                        )
                    }
                }

            val aJob =
                audioUrl?.let { url ->
                    async(Dispatchers.IO) {
                        downloadFileChunked(url, audioFile!!) { p ->
                            audioP = p
                            DownloadManager.updateProgress(
                                videoID,
                                (videoP * videoWeight) + (audioP * (1.0 - videoWeight))
                            )
                        }
                    }
                }

            vJob.await()
            aJob?.await()

            val download =
                DownloadedVideo(
                    id = videoID,
                    videoItem = videoInfo,
                    filePath = videoFile.absolutePath,
                    audioPath = audioFile?.absolutePath,
                    timestamp = Clock.System.now()
                )

            db.downloadedVideoDao().upsert(download)
            Result.success()
        } catch (e: Exception) {
            // Cleanup on error or cancellation
            createdFiles.forEach { if (it.exists()) it.delete() }
            Result.failure()
        } finally {
            if (isStopped) {
                createdFiles.forEach { if (it.exists()) it.delete() }
            }
            DownloadManager.finishDownload(videoID)
        }
    }

    private suspend fun downloadFileChunked(url: String, file: File, onProgress: (Double) -> Unit) =
        coroutineScope {
            var totalBytes = client.getContentLength(url) ?: 0L

            if (totalBytes <= 0) {
                totalBytes =
                    client.getContentLength(url, headers = mapOf("Range" to "bytes=0-0")) ?: 0L
            }

            if (totalBytes <= 0) {
                // Fallback to simple download if size unknown
                downloadFileSimple(url, file, onProgress)
                return@coroutineScope
            }

            val numChunks = 4
            val chunkSize = totalBytes / numChunks
            val downloadedBytes = AtomicLong(0)

            // Pre-allocate file
            withContext(Dispatchers.IO) {
                RandomAccessFile(file, "rw").use { raf -> raf.setLength(totalBytes) }
            }

            // Throttled progress reporter: ONE coroutine polls the shared
            // counter at PROGRESS_INTERVAL_MS instead of every download
            // thread emitting on every 64KB write. Without this, a fast
            // connection produces thousands of StateFlow emissions per
            // second (each does an immutable Map copy of activeDownloads),
            // pinning the dispatcher and starving the actual network reads.
            val totalForReporter = totalBytes
            val progressJob = launch(Dispatchers.Default) {
                while (isActive) {
                    val done = downloadedBytes.get()
                    onProgress(done.toDouble() / totalForReporter)
                    if (done >= totalForReporter) break
                    delay(PROGRESS_INTERVAL_MS)
                }
            }

            try {
                val jobs =
                    (0 until numChunks).map { i ->
                        val start = i * chunkSize
                        val end =
                            if (i == numChunks - 1) totalBytes - 1
                            else (i + 1) * chunkSize - 1

                        async(Dispatchers.IO) {
                            try {
                                client.stream(
                                    url,
                                    headers = mapOf("Range" to "bytes=$start-$end")
                                ) { stream, _ ->
                                    if (stream == null) return@stream
                                    // RandomAccessFile.write is unbuffered —
                                    // wrap it via FileChannel so the 64KB
                                    // socket reads are coalesced into 256KB
                                    // disk syscalls. ~4× fewer write
                                    // syscalls per chunk at high bandwidth.
                                    RandomAccessFile(file, "rw").use { raf ->
                                        raf.seek(start)
                                        BufferedOutputStream(
                                            Channels.newOutputStream(raf.channel),
                                            WRITE_BUFFER_SIZE,
                                        ).use { out ->
                                            val buffer = ByteArray(READ_BUFFER_SIZE)
                                            while (!stream.isClosedForRead) {
                                                if (isStopped) return@stream
                                                val bytesRead = stream.read(buffer)
                                                if (bytesRead == -1) break
                                                out.write(buffer, 0, bytesRead)
                                                downloadedBytes.addAndGet(bytesRead.toLong())
                                            }
                                            out.flush()
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("DownloadWorker", "Error downloading chunk $i", e)
                            }
                        }
                    }

                jobs.forEach { it.await() }
            } finally {
                progressJob.cancel()
                // Final flush of progress so the UI doesn't appear stuck at
                // <100% if the polling loop was mid-sleep when the last
                // chunk finished.
                onProgress(downloadedBytes.get().toDouble() / totalBytes)
            }
        }

    private suspend fun downloadFileSimple(url: String, file: File, onProgress: (Double) -> Unit) {
        try {
            client.stream(url) { stream, response ->
                if (stream == null) return@stream
                val totalBytes = response.contentLength
                var downloadedBytes = 0L
                var lastReportedMs = 0L
                BufferedOutputStream(file.outputStream(), WRITE_BUFFER_SIZE).use { output ->
                    val buffer = ByteArray(READ_BUFFER_SIZE)
                    while (!stream.isClosedForRead) {
                        if (isStopped) {
                            file.delete()
                            return@stream
                        }
                        val read = stream.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes != null && totalBytes > 0) {
                            val now = System.currentTimeMillis()
                            if (now - lastReportedMs >= PROGRESS_INTERVAL_MS) {
                                onProgress(downloadedBytes.toDouble() / totalBytes.toDouble())
                                lastReportedMs = now
                            }
                        }
                    }
                    output.flush()
                    if (totalBytes != null && totalBytes > 0) {
                        onProgress(downloadedBytes.toDouble() / totalBytes.toDouble())
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DownloadWorker", "Error in simple download", e)
        }
    }

    companion object {
        /**
         * Socket-read buffer. 64KB matches NewPipe's downloader and is large
         * enough that read/write syscall overhead is negligible compared to
         * network throughput; 8KB (the previous value) pegged the dispatcher
         * on syscalls long before the network was saturated.
         */
        private const val READ_BUFFER_SIZE = 64 * 1024

        /**
         * File-write buffer wrapped around [RandomAccessFile.channel]. Larger
         * than [READ_BUFFER_SIZE] so multiple socket reads collapse into one
         * disk write.
         */
        private const val WRITE_BUFFER_SIZE = 256 * 1024

        /** Maximum UI update rate for download progress, in milliseconds. */
        private const val PROGRESS_INTERVAL_MS = 250L

        fun enqueue(context: Context, videoInfo: VideoInfo, videoUrl: String, audioUrl: String?) {
            val data =
                Data.Builder()
                    .putLong("videoID", videoInfo.videoID)
                    .putString("name", videoInfo.name)
                    .putLong("duration", videoInfo.duration)
                    .putLong("views", videoInfo.views)
                    .putLong("uploadDate", videoInfo.uploadDate.epochSeconds)
                    .putString("thumbnailURL", videoInfo.thumbnailURL)
                    .putString("author", videoInfo.author)
                    .putString("videoUrl", videoUrl)
                    .putString("audioUrl", audioUrl)
                    .build()

            val request = OneTimeWorkRequestBuilder<DownloadWorker>().setInputData(data).build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "download_${videoInfo.videoID}",
                    ExistingWorkPolicy.KEEP,
                    request
                )
        }
    }
}
