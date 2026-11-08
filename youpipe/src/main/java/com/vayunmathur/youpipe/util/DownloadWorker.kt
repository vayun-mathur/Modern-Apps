package com.vayunmathur.youpipe.util

import android.content.Context
import com.vayunmathur.library.log.Log
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vayunmathur.youpipe.data.DownloadedVideo
import com.vayunmathur.youpipe.data.SubscriptionRepository
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.util.sabr.SabrNgDownloadHelper
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.NetworkDataStream
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrInfo
import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.Channels
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
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
        val input = readDownloadInput() ?: return@coroutineScope Result.failure()
        val dir = File(applicationContext.getExternalFilesDir(null), "downloads")
        if (!dir.exists()) dir.mkdirs()

        // SABR streams are encoded by the UI as "sabr://<videoId>?v=<itag>" (video) and
        // "sabr://<videoId>?a=<itag>" (audio). They cannot be fetched over plain HTTP; drive a
        // SABR session instead, muxing audio+video into a single mp4.
        if (input.videoUrl.toUri().scheme == "sabr") {
            return@coroutineScope runSabrDownload(input, dir)
        }
        runProgressiveDownload(input, dir)
    }

    private data class DownloadInput(
        val videoID: Long,
        val videoUrl: String,
        val audioUrl: String?,
        val videoInfo: VideoInfo,
        val repository: SubscriptionRepository,
    )

    private suspend fun readDownloadInput(): DownloadInput? {
        val videoID = inputData.getLong("videoID", -1L)
        val videoUrl = inputData.getString("videoUrl") ?: return null
        val audioUrl = inputData.getString("audioUrl")
        val videoInfo = VideoInfo(
            name = inputData.getString("name") ?: "",
            videoID = videoID,
            duration = inputData.getLong("duration", 0L),
            views = inputData.getLong("views", 0L),
            uploadDate = Instant.fromEpochSeconds(inputData.getLong("uploadDate", 0L)),
            thumbnailURL = inputData.getString("thumbnailURL") ?: "",
            author = inputData.getString("author") ?: ""
        )
        return DownloadInput(
            videoID, videoUrl, audioUrl, videoInfo,
            SubscriptionRepository.get(applicationContext),
        )
    }

    private suspend fun runProgressiveDownload(input: DownloadInput, dir: File): Result =
        coroutineScope {
            val videoFile = File(dir, "${input.videoID}.mp4")
            val audioFile = if (input.audioUrl != null) File(dir, "${input.videoID}.m4a") else null

            val createdFiles = mutableListOf<File>()
            createdFiles.add(videoFile)
            audioFile?.let { createdFiles.add(it) }

            try {
                downloadProgressiveTracks(input, videoFile, audioFile)
                // A stop that landed mid-transfer leaves a partial file behind: the
                // finally below deletes it, so don't record a DB row for it.
                if (isStopped) return@coroutineScope Result.failure()
                val download = DownloadedVideo(
                    id = input.videoID,
                    videoItem = input.videoInfo,
                    filePath = videoFile.absolutePath,
                    audioPath = audioFile?.absolutePath,
                    timestamp = Clock.System.now()
                )
                input.repository.upsertDownloadedVideo(download)
                Result.success()
            } catch (e: java.io.IOException) {
                // Cleanup on error or cancellation
                Log.error("DownloadWorker", "Progressive download failed for ${input.videoID}", e)
                createdFiles.forEach { if (it.exists()) it.delete() }
                Result.failure()
            } finally {
                if (isStopped) {
                    createdFiles.forEach { if (it.exists()) it.delete() }
                }
                DownloadManager.finishDownload(input.videoID)
            }
        }

    private suspend fun downloadProgressiveTracks(
        input: DownloadInput,
        videoFile: File,
        audioFile: File?,
    ) = coroutineScope {
        val videoWeight = if (input.audioUrl != null) PROGRESS_VIDEO_WEIGHT else 1.0

        // Simplified progress reporting for parallel:
        var videoP = 0.0
        var audioP = 0.0

        val vJob = async(Dispatchers.IO) {
            downloadFileChunked(input.videoUrl, videoFile) { p ->
                videoP = p
                DownloadManager.updateProgress(
                    input.videoID,
                    (videoP * videoWeight) + (audioP * (1.0 - videoWeight))
                )
            }
        }

        val aJob = input.audioUrl?.let { url ->
            async(Dispatchers.IO) {
                downloadFileChunked(url, audioFile!!) { p ->
                    audioP = p
                    DownloadManager.updateProgress(
                        input.videoID,
                        (videoP * videoWeight) + (audioP * (1.0 - videoWeight))
                    )
                }
            }
        }

        vJob.await()
        aJob?.await()
    }

    /**
     * Fetches a SABR audio+video pair through a [SabrDownloadHelper] session and stores the muxed
     * result as a single mp4. The [YoutubeSabrInfo] is not carried in WorkManager input data, so it
     * is re-obtained by re-extracting the stream page (mirroring [YouPipeViewModel] loadVideo).
     */
    private suspend fun runSabrDownload(input: DownloadInput, dir: File): Result {
        val videoUri = input.videoUrl.toUri()
        val youtubeId = videoUri.host
        val videoItag = videoUri.getQueryParameter("v")?.toIntOrNull()
        val audioItag = input.audioUrl?.let { it.toUri().getQueryParameter("a")?.toIntOrNull() }
        if (youtubeId.isNullOrEmpty() || videoItag == null || audioItag == null) {
            Log.error("DownloadWorker", "Malformed SABR request video=${input.videoUrl} audio=${input.audioUrl}")
            DownloadManager.finishDownload(input.videoID)
            return Result.failure()
        }

        val outputFile = File(dir, "${input.videoID}.mp4")
        val workDir = File(dir, "sabr-tmp-${input.videoID}")

        return try {
            withContext(Dispatchers.IO) {
                // Re-extract the stream page to recover the SABR metadata for this video.
                val ex = ServiceList.YouTube.getStreamExtractor(
                    "https://www.youtube.com/watch?v=$youtubeId"
                )
                ex.fetchPage()
                val sabrStreams: List<Stream> = ex.getVideoOnlyStreams() + ex.getAudioStreams()
                val info = sabrStreams.firstOrNull {
                    it.getDeliveryMethod() == DeliveryMethod.SABR &&
                        it.getDeliveryMethodInfo() is YoutubeSabrInfo
                }?.getDeliveryMethodInfo() as? YoutubeSabrInfo
                    ?: throw IllegalStateException("No SABR info available for $youtubeId")

                SabrNgDownloadHelper.download(
                    context = applicationContext,
                    videoId = youtubeId,
                    videoItag = videoItag,
                    audioItag = audioItag,
                    audioTrackId = null,
                    info = info,
                    workDir = workDir,
                    outputFile = outputFile,
                ) { p -> DownloadManager.updateProgress(input.videoID, p) }
            }

            val download =
                DownloadedVideo(
                    id = input.videoID,
                    videoItem = input.videoInfo,
                    filePath = outputFile.absolutePath,
                    audioPath = null,
                    timestamp = Clock.System.now()
                )
            input.repository.upsertDownloadedVideo(download)
            Result.success()
        } catch (e: java.io.IOException) {
            Log.error("DownloadWorker", "SABR download failed for ${input.videoID}", e)
            if (outputFile.exists()) outputFile.delete()
            Result.failure()
        } catch (e: org.schabi.newpipe.extractor.exceptions.ExtractionException) {
            Log.error("DownloadWorker", "SABR download failed for ${input.videoID}", e)
            if (outputFile.exists()) outputFile.delete()
            Result.failure()
        } finally {
            workDir.deleteRecursively()
            if (isStopped && outputFile.exists()) outputFile.delete()
            DownloadManager.finishDownload(input.videoID)
        }
    }

    private suspend fun downloadFileChunked(url: String, file: File, onProgress: (Double) -> Unit) =
        coroutineScope {
            val totalBytes = resolveContentLength(url)
            if (totalBytes <= 0) {
                // Fallback to simple download if size unknown
                downloadFileSimple(url, file, onProgress)
                return@coroutineScope
            }
            preallocateFile(file, totalBytes)
            val downloadedBytes = AtomicLong(0)
            val progressJob = launchProgressReporter(downloadedBytes, totalBytes, onProgress)
            try {
                fetchChunksParallel(url, file, totalBytes, downloadedBytes)
            } finally {
                progressJob.cancel()
                // Final flush of progress so the UI doesn't appear stuck at
                // <100% if the polling loop was mid-sleep when the last
                // chunk finished.
                onProgress(downloadedBytes.get().toDouble() / totalBytes)
            }
        }

    private suspend fun resolveContentLength(url: String): Long {
        var totalBytes = client.getContentLength(url) ?: 0L
        if (totalBytes <= 0) {
            totalBytes =
                client.getContentLength(url, headers = mapOf("Range" to "bytes=0-0")) ?: 0L
        }
        return totalBytes
    }

    private suspend fun preallocateFile(file: File, totalBytes: Long) {
        withContext(Dispatchers.IO) {
            RandomAccessFile(file, "rw").use { raf -> raf.setLength(totalBytes) }
        }
    }

    private fun CoroutineScope.launchProgressReporter(
        downloadedBytes: AtomicLong,
        totalBytes: Long,
        onProgress: (Double) -> Unit,
    ) = launch(Dispatchers.Default) {
        // Throttled progress reporter: ONE coroutine polls the shared
        // counter at PROGRESS_INTERVAL_MS instead of every download
        // thread emitting on every 64KB write. Without this, a fast
        // connection produces thousands of StateFlow emissions per
        // second (each does an immutable Map copy of activeDownloads),
        // pinning the dispatcher and starving the actual network reads.
        while (isActive) {
            val done = downloadedBytes.get()
            onProgress(done.toDouble() / totalBytes)
            if (done >= totalBytes) break
            delay(PROGRESS_INTERVAL_MS)
        }
    }

    private suspend fun fetchChunksParallel(
        url: String,
        file: File,
        totalBytes: Long,
        downloadedBytes: AtomicLong,
    ) = coroutineScope {
        val chunkSize = totalBytes / CHUNK_COUNT
        val jobs = (0 until CHUNK_COUNT).map { i ->
            val start = i * chunkSize
            val end = if (i == CHUNK_COUNT - 1) totalBytes - 1 else (i + 1) * chunkSize - 1
            // No catch here: a failed chunk must fail the download (the caller's
            // catch deletes the partial file). Swallowing it used to leave a
            // zero-filled hole in the preallocated file marked as success.
            async(Dispatchers.IO) {
                fetchChunk(url, file, start, end, downloadedBytes)
            }
        }
        jobs.forEach { it.await() }
        // NetworkClient.stream reports a mid-transfer drop as a clean -1 EOF, so a
        // short chunk also completes "successfully" — reject short files here.
        val done = downloadedBytes.get()
        if (done != totalBytes) {
            throw java.io.IOException("Incomplete download: $done of $totalBytes bytes")
        }
    }

    private suspend fun fetchChunk(
        url: String,
        file: File,
        start: Long,
        end: Long,
        downloadedBytes: AtomicLong,
    ) {
        client.stream(
            url,
            headers = mapOf("Range" to "bytes=$start-$end")
        ) { stream, _ ->
            // Null stream = non-streamable status (error body, 416 on the range,
            // ...). Returning silently here used to count the chunk as done.
            if (stream == null) throw java.io.IOException("Range request failed for bytes=$start-$end")
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
                    copyChunkStream(stream, out, downloadedBytes)
                }
            }
        }
    }

    private suspend fun copyChunkStream(
        stream: NetworkDataStream,
        out: BufferedOutputStream,
        downloadedBytes: AtomicLong,
    ) {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        while (!stream.isClosedForRead) {
            if (isStopped) return
            val bytesRead = stream.read(buffer)
            if (bytesRead == -1) break
            out.write(buffer, 0, bytesRead)
            downloadedBytes.addAndGet(bytesRead.toLong())
        }
        out.flush()
    }

    private suspend fun downloadFileSimple(url: String, file: File, onProgress: (Double) -> Unit) {
        // No catch here: like the chunked path, a transfer error must reach the
        // caller's catch (partial-file cleanup + failure), not a success row.
        client.stream(url) { stream, response ->
            if (stream == null) throw java.io.IOException("Simple download request failed for $url")
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
                    // Same -1-on-drop truncation as the chunked path: a short read
                    // breaks the loop cleanly, so verify the length when known.
                    if (downloadedBytes != totalBytes) {
                        throw java.io.IOException(
                            "Incomplete download: $downloadedBytes of $totalBytes bytes"
                        )
                    }
                }
            }
        }
    }

    companion object {
        private const val CHUNK_COUNT = 4
        private const val PROGRESS_VIDEO_WEIGHT = 0.5
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
