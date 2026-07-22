package com.vayunmathur.youpipe.util.sabr

import android.content.Context
import android.util.Log
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrProtocolException
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrRecoverableException
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSegmentRequest
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Orchestrates a full SABR download for a single video: it drives a [YoutubeSabrSession] to fetch
 * the selected audio and video formats into two temporary files, then muxes them into a single mp4.
 *
 * This is an adaptation of PipePipe's `us.shandian.giga.get.SabrDownloader` (which is tied to the
 * giga `DownloadMission` framework). The session-driving loop — cold-start retries, transient
 * retries, the per-round segment request/drain cycle, and the cold-start detection when media
 * arrives before initialization — is preserved. Everything specific to `DownloadMission`
 * (checkpoints/resume, SAF storage streams, progress bookkeeping) has been removed; progress is
 * reported through a simple [onProgress] callback and the muxed result is written to [outputFile].
 *
 * Unlike the reference, a download always needs both an audio and a video format (the
 * [YoutubeSabrSession] constructor requires one of each), so this only implements the two-target
 * `VIDEO_AND_AUDIO` request mode; the reference's single-target companion-warmup / audio-only /
 * video-only special cases are not applicable here.
 */
internal object SabrDownloadHelper {
    private const val TAG = "SabrDownloadHelper"
    private const val IDLE_POLL_MS = 250L
    private const val MAX_EMPTY_RESPONSES = 60
    private const val MAX_COLD_START_RETRIES = 3
    private const val MAX_TRANSIENT_RETRIES = 5
    private const val MAX_TRANSIENT_RETRY_DELAY_MS = 5_000L
    private const val MAX_SESSION_CACHE_BYTES = 48L * 1024L * 1024L

    private const val AUDIO_INDEX = 0
    private const val VIDEO_INDEX = 1

    /**
     * Downloads the SABR [audioItag] + [videoItag] formats of [videoId] described by [info] and
     * muxes them into [outputFile] (mp4). Temporary media is written under [workDir], which is
     * created if needed and cleaned up on return. [onProgress] receives a fraction in `[0, 1]`.
     *
     * @throws SabrDownloadException on any unrecoverable failure.
     */
    @Throws(SabrDownloadException::class)
    fun download(
        context: Context,
        videoId: String,
        videoItag: Int,
        audioItag: Int,
        info: YoutubeSabrInfo,
        workDir: File,
        outputFile: File,
        onProgress: (Double) -> Unit,
    ) {
        val audioFormat = SabrDownloadFormatResolver.resolveAudioFormat(info, audioItag)
        val videoFormat = SabrDownloadFormatResolver.resolveVideoFormat(info, videoItag)
        val expectedLength = expectedLength(audioFormat, videoFormat)

        if (!workDir.exists() && !workDir.mkdirs()) {
            throw storageException("could not create temporary directory", null)
        }
        val audioFile = File(workDir, "sabr-audio-$videoId-${audioFormat.itag}.media")
        val videoFile = File(workDir, "sabr-video-$videoId-${videoFormat.itag}.media")

        try {
            var coldStartAttempts = 0
            var transientAttempts = 0
            while (true) {
                try {
                    runSessionAttempt(
                        context,
                        info,
                        audioFormat,
                        videoFormat,
                        audioFile,
                        videoFile,
                        outputFile,
                        expectedLength,
                        onProgress,
                    )
                    break
                } catch (error: RetryColdStartException) {
                    coldStartAttempts++
                    if (coldStartAttempts > MAX_COLD_START_RETRIES) {
                        throw SabrDownloadException(
                            SabrDownloadException.Reason.INITIALIZATION,
                            "SABR download failed: cold start did not provide initialization",
                            error,
                        )
                    }
                    Log.d(TAG, "retry cold start attempt=$coldStartAttempts")
                } catch (error: SabrProtocolException) {
                    throw classifyProtocolException(error)
                } catch (error: Exception) {
                    if (!isRetryableAttemptFailure(error)) {
                        throw error
                    }
                    if (transientAttempts >= MAX_TRANSIENT_RETRIES) {
                        throw SabrDownloadException(
                            SabrDownloadException.Reason.NETWORK,
                            "SABR download failed: network error after retries",
                            error,
                        )
                    }
                    transientAttempts++
                    Log.d(TAG, "retry transient attempt=$transientAttempts error=${error.javaClass.simpleName}")
                    Thread.sleep(transientRetryDelayMs(transientAttempts))
                }
            }
            onProgress(1.0)
        } catch (error: SabrDownloadException) {
            throw error
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SabrDownloadException(
                SabrDownloadException.Reason.NETWORK,
                "SABR download interrupted",
                error,
            )
        } catch (error: Exception) {
            throw SabrDownloadException(
                SabrDownloadException.Reason.PROTOCOL,
                "SABR download failed: ${error.message ?: error.javaClass.simpleName}",
                error,
            )
        } finally {
            deleteQuietly(audioFile)
            deleteQuietly(videoFile)
        }
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun runSessionAttempt(
        context: Context,
        info: YoutubeSabrInfo,
        audioFormat: YoutubeSabrFormat,
        videoFormat: YoutubeSabrFormat,
        audioFile: File,
        videoFile: File,
        outputFile: File,
        expectedLength: Long,
        onProgress: (Double) -> Unit,
    ) {
        // Each attempt starts from a clean slate (no checkpoint/resume in this adaptation).
        deleteQuietly(audioFile)
        deleteQuietly(videoFile)

        val session = YoutubeSabrSession(
            info,
            audioFormat,
            videoFormat,
            LocalDomPoTokenProvider.shared(context),
            null,
            SabrPolicyRuntime.createSessionHost(),
        )
        val targets = listOf(
            SabrDownloadTarget(AUDIO_INDEX, audioFormat, audioFile),
            SabrDownloadTarget(VIDEO_INDEX, videoFormat, videoFile),
        )
        // Both tracks are requested together for the whole download.
        session.streamState.setVideoAndAudioRequestMode()

        var writtenBytes = 0L
        val onBytesWritten: (SabrDownloadTarget, Long) -> Unit = { _, delta ->
            if (delta > 0) {
                writtenBytes += delta
                if (expectedLength > 0) {
                    onProgress((writtenBytes.toDouble() / expectedLength).coerceIn(0.0, 1.0))
                }
            }
        }

        val outputs = mutableMapOf<Int, FileOutputStream>()
        try {
            targets.forEach { target ->
                outputs[target.resourceIndex] = FileOutputStream(target.file, true)
            }
        } catch (error: IOException) {
            outputs.values.forEach { runCatching { it.close() } }
            throw storageException("could not open temporary media", error)
        }

        try {
            downloadSegments(
                session,
                targets,
                SabrSegmentWriter(session, targets, outputs, onBytesWritten),
            )
        } finally {
            outputs.values.forEach { runCatching { it.close() } }
            session.clearCache()
        }

        SabrFfmpegMuxer.mux(videoFile, audioFile, outputFile)
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun downloadSegments(
        session: YoutubeSabrSession,
        targets: List<SabrDownloadTarget>,
        writer: SabrSegmentWriter,
    ) {
        val localization = Localization("en", "US")
        writer.observeWrittenInitializations()

        var emptyResponses = 0
        while (true) {
            writer.observeWrittenInitializations()
            var wroteSegment = writer.drainCachedInitializations()
            wroteSegment = writer.drainCachedSegments() || wroteSegment

            if (isDownloadComplete(session, targets)) {
                break
            }

            session.streamState.setPlayerTimeMs(session.streamState.minBufferedEndMs)
            val segmentCount = session.pumpOnceStreaming(localization)
            writer.observeWrittenInitializations()
            wroteSegment = writer.drainCachedInitializations() || wroteSegment
            wroteSegment = writer.drainCachedSegments() || wroteSegment
            enforceSessionCacheLimit(session, writer)

            if (hasMediaWaitingForInitialization(targets)) {
                fetchMissingInitializationsOrRetry(writer, localization)
                writer.observeWrittenInitializations()
                wroteSegment = writer.drainCachedInitializations() || wroteSegment
                wroteSegment = writer.drainCachedSegments() || wroteSegment
                if (hasMediaWaitingForInitialization(targets)) {
                    throw RetryColdStartException()
                }
            }

            if (isDownloadComplete(session, targets)) {
                break
            }
            if (wroteSegment || segmentCount > 0) {
                emptyResponses = 0
            } else {
                emptyResponses++
                if (emptyResponses > MAX_EMPTY_RESPONSES) {
                    throw SabrDownloadException(
                        SabrDownloadException.Reason.STALLED,
                        "SABR download stalled: no media received after $MAX_EMPTY_RESPONSES rounds",
                    )
                }
                Thread.sleep(IDLE_POLL_MS)
            }
        }
    }

    @Throws(IOException::class)
    private fun fetchMissingInitializationsOrRetry(
        writer: SabrSegmentWriter,
        localization: Localization,
    ) {
        try {
            writer.fetchMissingInitializations(localization)
        } catch (error: SabrProtocolException) {
            if (isRetryableInitializationProtocolError(error)) {
                throw RetryColdStartException(error)
            }
            throw error
        }
    }

    @Throws(IOException::class)
    private fun enforceSessionCacheLimit(
        session: YoutubeSabrSession,
        writer: SabrSegmentWriter,
    ) {
        if (session.cachedBytes <= MAX_SESSION_CACHE_BYTES) {
            return
        }
        writer.drainCachedSegments()
        if (session.cachedBytes <= MAX_SESSION_CACHE_BYTES) {
            return
        }
        throw SabrDownloadException(
            SabrDownloadException.Reason.STALLED,
            "SABR download stalled: cached media grew to ${session.cachedBytes} bytes",
        )
    }

    private fun hasMediaWaitingForInitialization(targets: List<SabrDownloadTarget>): Boolean {
        return targets.any { target -> !target.initializationWritten && target.pending.isNotEmpty() }
    }

    private fun isDownloadComplete(
        session: YoutubeSabrSession,
        targets: List<SabrDownloadTarget>,
    ): Boolean {
        return targets.all { target ->
            target.pending.isEmpty() &&
                (session.streamState.isComplete(target.format) ||
                    session.isBeyondEnd(SabrSegmentRequest.media(target.format, target.nextWriteSequence)))
        }
    }

    private fun expectedLength(
        audioFormat: YoutubeSabrFormat,
        videoFormat: YoutubeSabrFormat,
    ): Long {
        val audio = audioFormat.contentLength
        val video = videoFormat.contentLength
        return if (audio > 0 && video > 0) audio + video else 0L
    }

    private fun storageException(message: String, cause: IOException?): SabrDownloadException {
        return SabrDownloadException(
            SabrDownloadException.Reason.STORAGE,
            "SABR download failed: $message",
            cause,
        )
    }

    private fun isRetryableAttemptFailure(error: Exception): Boolean {
        if (error is RetryColdStartException || error is SabrDownloadException) {
            return false
        }
        if (error is SabrRecoverableException) {
            return true
        }
        if (error is SabrProtocolException) {
            return false
        }
        return error is SocketTimeoutException ||
            error is ConnectException ||
            error is UnknownHostException ||
            error is IOException
    }

    private fun transientRetryDelayMs(attempt: Int): Long {
        return (500L shl (attempt - 1)).coerceAtMost(MAX_TRANSIENT_RETRY_DELAY_MS)
    }

    private fun classifyProtocolException(error: SabrProtocolException): SabrDownloadException {
        val message = error.message.orEmpty()
        val reason = when {
            message.contains("protected", ignoreCase = true) ||
                message.contains("PO token", ignoreCase = true) -> {
                SabrDownloadException.Reason.PROTECTED
            }
            message.contains("policy-only", ignoreCase = true) ||
                message.contains("not returned", ignoreCase = true) ||
                message.contains("integrity", ignoreCase = true) -> {
                SabrDownloadException.Reason.STALLED
            }
            else -> SabrDownloadException.Reason.PROTOCOL
        }
        return SabrDownloadException(
            reason,
            "SABR download failed: ${message.ifBlank { "protocol error" }}",
            error,
        )
    }

    private fun isRetryableInitializationProtocolError(error: SabrProtocolException): Boolean {
        val message = error.message.orEmpty()
        if (!message.contains(":init")) {
            return false
        }
        return message.contains("policy-only", ignoreCase = true) ||
            message.contains("not returned", ignoreCase = true)
    }

    private fun deleteQuietly(file: File) {
        runCatching { if (file.exists()) file.delete() }
    }

    private class RetryColdStartException(cause: Throwable? = null) : IOException(cause)
}
