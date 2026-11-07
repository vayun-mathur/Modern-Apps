package com.vayunmathur.camera.util

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

object VideoProcessor {
    /** Sample buffer size (2MB); no-video-track sentinel. */
    private const val SAMPLE_BUFFER_BYTES = 2 * 1024 * 1024
    private const val NO_TRACK = -1

    fun adjustSpeed(inputFile: File, outputFile: File, speedFactor: Float) {
        val rotation = readRotation(inputFile)

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(inputFile.path)

            muxer = MediaMuxer(outputFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(rotation)

            val videoTrackIndex = findVideoTrack(extractor, muxer)
            // No video track (audio-only input): clean up the half-created muxer and the empty
            // output, then fail so the caller falls back to the raw recording instead of
            // saving a 0-byte file.
            if (videoTrackIndex == NO_TRACK) {
                releaseQuietly(extractor, muxer, outputFile)
                throw java.io.IOException("Timelapse input has no video track")
            }

            extractor.selectTrack(videoTrackIndex)
            muxer.start()
            val trackFormat = extractor.getTrackFormat(videoTrackIndex)
            val maxInputSize = if (trackFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                trackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                0
            }
            remuxAtSpeed(extractor, muxer, speedFactor, sampleBufferSize(maxInputSize))

            // A failed stop() means the moov atom was never written and the
            // output is unplayable. Swallowing it (the old behaviour) saved
            // that corrupt file while the caller deleted the good raw
            // recording, so surface it as a failure and drop the output.
            try {
                muxer.stop()
            } catch (e: IllegalStateException) {
                try { outputFile.delete() } catch (_: Exception) {}
                throw java.io.IOException("Failed to finalize timelapse output", e)
            }
        } finally {
            if (muxer != null) {
                try { muxer.release() } catch (_: Exception) {}
            }
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    /** Reads the source rotation metadata; 0 when unavailable. */
    private fun readRotation(inputFile: File): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(inputFile.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
        } catch (_: Exception) {
            0
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /** Adds the first video track to the muxer; NO_TRACK when there is none. */
    private fun findVideoTrack(extractor: MediaExtractor, muxer: MediaMuxer): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("video/")) {
                muxer.addTrack(format)
                return i
            }
        }
        return NO_TRACK
    }

    /** Releases a half-created session and deletes the empty output. */
    private fun releaseQuietly(extractor: MediaExtractor, muxer: MediaMuxer?, outputFile: File) {
        try { extractor.release() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
        try { outputFile.delete() } catch (_: Exception) {}
    }

    /**
     * Copies video samples while remapping timestamps.
     *
     * newPts = (pts / totalDuration) * (totalDuration / speedFactor) == pts / speedFactor,
     * so timestamps can be remapped per-sample without buffering the whole stream.
     */
    private fun remuxAtSpeed(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        speedFactor: Float,
        bufferBytes: Int
    ) {
        val buffer = ByteBuffer.allocate(bufferBytes)
        val info = MediaCodec.BufferInfo()
        val muxerTrackIndex = 0
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            writeSample(extractor, muxer, muxerTrackIndex, buffer, info, size, speedFactor)
            extractor.advance()
        }
    }

    /** Presentation timestamp after the speed remap (pts / speedFactor). Pure for unit tests. */
    internal fun remappedPtsUs(sampleTimeUs: Long, speedFactor: Float): Long =
        (sampleTimeUs.toDouble() / speedFactor).toLong()

    /**
     * Sample buffer size: the fixed floor, grown to the encoder's declared
     * max input size when larger. A sample bigger than the buffer makes
     * readSampleData fail with an unchecked exception instead of returning
     * a truncated sample, which previously escaped the caller's
     * IOException/IllegalStateException fallback and lost the recording.
     */
    internal fun sampleBufferSize(maxInputSize: Int): Int =
        maxOf(SAMPLE_BUFFER_BYTES, maxInputSize)

    /** Writes one sample with its remapped presentation timestamp. */
    private fun writeSample(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        trackIndex: Int,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        size: Int,
        speedFactor: Float
    ) {
        info.offset = 0
        info.size = size
        info.flags = sampleFlags(extractor)
        info.presentationTimeUs = remappedPtsUs(extractor.sampleTime, speedFactor)
        muxer.writeSampleData(trackIndex, buffer, info)
    }

    /** Key-frame flag when the sample is a sync sample, else 0. */
    private fun sampleFlags(extractor: MediaExtractor): Int {
        return if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            MediaCodec.BUFFER_FLAG_KEY_FRAME
        } else {
            0
        }
    }
}
