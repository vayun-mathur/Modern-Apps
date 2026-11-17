package com.vayunmathur.youpipe.network.sabr

import android.content.Context
import com.vayunmathur.library.log.Log
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrFormatTimeline
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrInfo
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Session-based ([SabrNgSession]) SABR downloader: drives `requestOnce` to fetch the selected
 * audio+video formats to two temporary files (init segment followed by all media segments) and
 * muxes them into a single mp4 via [SabrFfmpegMuxer]. Replaces the old `SabrDownloadHelper`, which
 * drove the legacy SABR session API.
 */
internal object SabrNgDownloadHelper {
    private const val TAG = "SabrNgDownloadHelper"
    private const val SEGMENT_TIMEOUT_MS = 30_000L
    private val DOWNLOAD_LOCALIZATION = Localization("en", "US")

    @Throws(IOException::class)
    fun download(
        context: Context,
        videoId: String,
        videoItag: Int,
        audioItag: Int,
        audioTrackId: String?,
        info: YoutubeSabrInfo,
        workDir: File,
        outputFile: File,
        onProgress: (Double) -> Unit,
    ) {
        ensureWorkDir(workDir)
        val provider = LocalDomPoTokenProvider.shared(context.applicationContext)
        val tokenMinter: (Boolean) -> ByteArray? = { force ->
            provider.getPoTokenBytes(videoId, force)
        }
        val poToken = tokenMinter(false)
        val spec = SabrNgSessionStore.createSourceSpec(
            videoId, videoItag, audioItag, audioTrackId, info, poToken, DOWNLOAD_LOCALIZATION,
            tokenMinter
        )
        val audioFile = File(workDir, "sabr-audio-$videoId-${spec.audioFormat.getItag()}.media")
        val videoFile = File(workDir, "sabr-video-$videoId-${spec.videoFormat.getItag()}.media")
        val spoolDir = File(workDir, "spool").apply { mkdirs() }
        val session = SabrNgSession(spec, spoolDir, tokenMinter)

        val totalSegments =
            (spec.audioTimeline.getEndSequence() + spec.videoTimeline.getEndSequence())
                .coerceAtLeast(1)
        var written = 0
        val progress = { onProgress((written.toDouble() / totalSegments).coerceIn(0.0, 1.0)) }
        try {
            session.start()
            writeTrack(session, spec.audioFormat.getItag(), spec.audioTimeline, audioFile) {
                written++
                progress()
            }
            writeTrack(session, spec.videoFormat.getItag(), spec.videoTimeline, videoFile) {
                written++
                progress()
            }
            SabrFfmpegMuxer.mux(videoFile, audioFile, outputFile)
            onProgress(1.0)
        } catch (e: IOException) {
            throw cleanupAfterFailure(outputFile, videoId, e)
        } catch (e: org.schabi.newpipe.extractor.exceptions.ExtractionException) {
            throw cleanupAfterFailure(outputFile, videoId, e)
        } finally {
            session.stop()
            audioFile.delete()
            videoFile.delete()
            spoolDir.deleteRecursively()
        }
    }

    @Throws(IOException::class)
    private fun ensureWorkDir(dir: File) {
        if (!dir.exists()) dir.mkdirs()
    }

    @Throws(IOException::class)
    private fun cleanupAfterFailure(outputFile: File, videoId: String, cause: Throwable): IOException {
        Log.error(TAG, "SABR download failed for $videoId", cause)
        if (outputFile.exists()) {
            outputFile.delete()
        }
        return downloadIOException(videoId, cause)
    }

    @Throws(IOException::class)
    private fun downloadIOException(videoId: String, cause: Throwable): IOException =
        IOException("SABR download failed for $videoId: ${cause.message ?: cause.javaClass.simpleName}", cause)

    @Throws(IOException::class)
    private fun writeTrack(
        session: SabrNgSession,
        itag: Int,
        timeline: YoutubeSabrFormatTimeline,
        file: File,
        onSegment: () -> Unit,
    ) {
        FileOutputStream(file).use { out ->
            session.getInitialization(itag)?.let { out.write(it) }
            for (sequence in 1..timeline.getEndSequence()) {
                val segment = session.getMediaSegment(itag, sequence, SEGMENT_TIMEOUT_MS)
                    ?: throw IOException("Missing SABR segment itag=$itag seq=$sequence")
                segment.openStream().use { it.copyTo(out) }
                onSegment()
            }
        }
    }
}
