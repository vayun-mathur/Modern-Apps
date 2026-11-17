package com.vayunmathur.youpipe.network.sabr

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.vayunmathur.library.log.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Muxes the separately-downloaded SABR audio and video temporary files into a single mp4 using
 * Android's native [MediaMuxer] + [MediaExtractor] (stream copy, no re-encode).
 *
 * Previously this used ffmpeg-kit (ffmpeg-kit.aar), a prebuilt non-free binary that blocks
 * F-Droid publication. This implementation uses only AOSP framework APIs and is fully
 * FOSS-compliant.
 *
 * Input files are fMP4 containers (init + media segments) produced by [SabrSegmentWriter].
 * Each file contains a single track (video-only or audio-only).
 */
internal object SabrFfmpegMuxer {
    private const val TAG = "SabrFfmpegMuxer"
    private const val INITIAL_BUFFER_SIZE = 2 * 1024 * 1024 // 2 MB

    // MediaExtractor.SAMPLE_FLAG_* and MediaCodec.BUFFER_FLAG_* are distinct constant spaces.
    private fun bufferFlagsFor(sampleFlags: Int): Int =
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0

    @Throws(IOException::class)
    fun mux(videoInput: File, audioInput: File, output: File) {
        Log.dev(TAG, "remux video=${videoInput.length()} audio=${audioInput.length()} -> ${output.name}")

        validateMuxInputs(videoInput, audioInput, output)

        var muxer: MediaMuxer? = null
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null

        try {
            videoExtractor = MediaExtractor().apply { setDataSource(videoInput.absolutePath) }
            audioExtractor = MediaExtractor().apply { setDataSource(audioInput.absolutePath) }
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Use non-null locals after creation to avoid !!.
            val vExtractor = videoExtractor
            val aExtractor = audioExtractor
            val mMuxer = muxer

            val tracks = selectMuxTracks(vExtractor, aExtractor, mMuxer)
            val videoTrack = tracks.videoTrack
            val audioTrack = tracks.audioTrack
            val videoMuxerTrackIndex = tracks.videoMuxerIndex
            val audioMuxerTrackIndex = tracks.audioMuxerIndex

            mMuxer.start()

            videoTrack?.let { vExtractor.selectTrack(it.extractorIndex) }
            audioTrack?.let { aExtractor.selectTrack(it.extractorIndex) }
            pumpSamples(
                vExtractor, aExtractor, mMuxer, videoTrack, audioTrack,
                videoMuxerTrackIndex, audioMuxerTrackIndex,
            )

            Log.dev(TAG, "mux successful -> ${output.absolutePath} size=${output.length()}")
        } catch (e: java.io.IOException) {
            runCatching { if (output.exists()) output.delete() }
            throw remuxFailure(e)
        } catch (e: IllegalStateException) {
            runCatching { if (output.exists()) output.delete() }
            throw remuxFailure(e)
        } finally {
            runCatching { videoExtractor?.release() }
            runCatching { audioExtractor?.release() }
            muxer?.let { m ->
                runCatching { m.stop() }
                runCatching { m.release() }
            }
        }
    }
    @Throws(SabrDownloadException::class)
    private fun validateMuxInputs(videoInput: File, audioInput: File, output: File) {
        requireTempFile(videoInput, "video")
        requireTempFile(audioInput, "audio")
        output.parentFile?.mkdirs()
        if (output.exists() && !output.delete()) {
            throw SabrDownloadException(
                SabrDownloadException.Reason.STORAGE,
                "SABR download failed: could not delete existing output",
            )
        }
    }

    @Throws(SabrDownloadException::class)
    private fun requireTempFile(input: File, kind: String) {
        if (!input.exists() || input.length() == 0L) {
            throw SabrDownloadException(
                SabrDownloadException.Reason.MUXING,
                "SABR download failed: $kind temp file missing or empty",
            )
        }
    }

    private fun remuxFailure(cause: Throwable): IOException =
        (cause as? SabrDownloadException) ?: remuxFailedException(cause)

    private fun remuxFailedException(cause: Throwable) = SabrDownloadException(
        SabrDownloadException.Reason.MUXING,
        "SABR download failed: MediaMuxer remux failed: ${cause.message ?: cause.javaClass.simpleName}",
        cause,
    )

    private data class SelectedTrack(val extractorIndex: Int, val format: MediaFormat)

    private data class MuxTracks(
        val videoTrack: SelectedTrack?,
        val audioTrack: SelectedTrack?,
        val videoMuxerIndex: Int,
        val audioMuxerIndex: Int,
    )

    @Throws(SabrDownloadException::class)
    private fun selectMuxTracks(
        vExtractor: MediaExtractor,
        aExtractor: MediaExtractor,
        mMuxer: MediaMuxer,
    ): MuxTracks {
        val videoTrack = selectTrack(vExtractor, "video/")
        val audioTrack = selectTrack(aExtractor, "audio/")
        if (videoTrack == null && audioTrack == null) {
            throw SabrDownloadException(
                SabrDownloadException.Reason.MUXING,
                "SABR download failed: no video/audio tracks found in temp files",
            )
        }
        return MuxTracks(
            videoTrack,
            audioTrack,
            videoTrack?.let { mMuxer.addTrack(it.format) } ?: -1,
            audioTrack?.let { mMuxer.addTrack(it.format) } ?: -1,
        )
    }

    private fun selectTrack(extractor: MediaExtractor, mimePrefix: String): SelectedTrack? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) {
                return SelectedTrack(i, format)
            }
        }
        return null
    }

    private fun pumpSamples(
        vExtractor: MediaExtractor,
        aExtractor: MediaExtractor,
        mMuxer: MediaMuxer,
        videoTrack: SelectedTrack?,
        audioTrack: SelectedTrack?,
        videoMuxerTrackIndex: Int,
        audioMuxerTrackIndex: Int,
    ) {
        val videoPump = TrackPump(vExtractor, mMuxer, videoMuxerTrackIndex, videoTrack != null)
        val audioPump = TrackPump(aExtractor, mMuxer, audioMuxerTrackIndex, audioTrack != null)
        while (!videoPump.done || !audioPump.done) {
            videoPump.refreshSampleTime()
            audioPump.refreshSampleTime()
            if (videoPump.done && audioPump.done) break
            if (videoPump.goesNext(audioPump)) {
                videoPump.pumpNext()
            } else {
                audioPump.pumpNext()
            }
        }
    }

    /** Pumps one track's samples into the muxer, tracking completion. */
    private class TrackPump(
        private val extractor: MediaExtractor,
        private val muxer: MediaMuxer,
        private val muxerTrackIndex: Int,
        hasTrack: Boolean,
    ) {
        var done: Boolean = !hasTrack
            private set
        private var sampleTime: Long = Long.MAX_VALUE
        private var buffer: ByteBuffer = ByteBuffer.allocate(INITIAL_BUFFER_SIZE)

        fun refreshSampleTime() {
            if (done) {
                sampleTime = Long.MAX_VALUE
                return
            }
            sampleTime = sampleTimeOrDone(extractor, done = false)
            if (sampleTime == Long.MAX_VALUE) {
                done = true
            }
        }

        fun goesNext(other: TrackPump): Boolean {
            if (done) return false
            if (other.done) return true
            return sampleTime <= other.sampleTime
        }

        fun pumpNext() {
            val pumped = pumpOneSample(extractor, muxer, muxerTrackIndex, buffer)
            buffer = pumped.buffer
            if (pumped.exhausted) done = true
        }
    }

    private fun sampleTimeOrDone(extractor: MediaExtractor, done: Boolean): Long {
        if (done) return Long.MAX_VALUE
        val t = extractor.sampleTime
        return if (t == -1L) Long.MAX_VALUE else t
    }

    private data class PumpResult(val buffer: ByteBuffer, val exhausted: Boolean)

    private fun pumpOneSample(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        muxerTrackIndex: Int,
        buffer: ByteBuffer,
    ): PumpResult {
        var active = buffer
        active.clear()
        var sampleSize = extractor.readSampleData(active, 0)
        if (sampleSize < 0) return PumpResult(active, exhausted = true)
        if (sampleSize > active.capacity()) {
            active = ByteBuffer.allocate(sampleSize)
            active.clear()
            sampleSize = extractor.readSampleData(active, 0)
            if (sampleSize < 0) return PumpResult(active, exhausted = true)
        }
        val bufferInfo = MediaCodec.BufferInfo().apply {
            set(0, sampleSize, extractor.sampleTime, bufferFlagsFor(extractor.sampleFlags))
        }
        muxer.writeSampleData(muxerTrackIndex, active, bufferInfo)
        extractor.advance()
        return PumpResult(active, exhausted = false)
    }
}
