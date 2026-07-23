package com.vayunmathur.youpipe.util.sabr

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
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

    @Throws(IOException::class)
    fun mux(videoInput: File, audioInput: File, output: File) {
        Log.d(TAG, "remux video=${videoInput.length()} audio=${audioInput.length()} -> ${output.name}")

        if (!videoInput.exists() || videoInput.length() == 0L) {
            throw SabrDownloadException(
                SabrDownloadException.Reason.MUXING,
                "SABR download failed: video temp file missing or empty",
            )
        }
        if (!audioInput.exists() || audioInput.length() == 0L) {
            throw SabrDownloadException(
                SabrDownloadException.Reason.MUXING,
                "SABR download failed: audio temp file missing or empty",
            )
        }

        output.parentFile?.mkdirs()
        if (output.exists() && !output.delete()) {
            throw SabrDownloadException(
                SabrDownloadException.Reason.STORAGE,
                "SABR download failed: could not delete existing output",
            )
        }

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

            var videoExtractorTrackIndex = -1
            var videoMuxerTrackIndex = -1
            for (i in 0 until vExtractor.trackCount) {
                val format = vExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    videoExtractorTrackIndex = i
                    videoMuxerTrackIndex = mMuxer.addTrack(format)
                    break
                }
            }

            var audioExtractorTrackIndex = -1
            var audioMuxerTrackIndex = -1
            for (i in 0 until aExtractor.trackCount) {
                val format = aExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioExtractorTrackIndex = i
                    audioMuxerTrackIndex = mMuxer.addTrack(format)
                    break
                }
            }

            if (videoMuxerTrackIndex == -1 && audioMuxerTrackIndex == -1) {
                throw SabrDownloadException(
                    SabrDownloadException.Reason.MUXING,
                    "SABR download failed: no video/audio tracks found in temp files",
                )
            }

            mMuxer.start()

            if (videoExtractorTrackIndex != -1) {
                vExtractor.selectTrack(videoExtractorTrackIndex)
            }
            if (audioExtractorTrackIndex != -1) {
                aExtractor.selectTrack(audioExtractorTrackIndex)
            }

            var videoBuffer = ByteBuffer.allocate(INITIAL_BUFFER_SIZE)
            var audioBuffer = ByteBuffer.allocate(INITIAL_BUFFER_SIZE)

            var videoDone = videoExtractorTrackIndex == -1
            var audioDone = audioExtractorTrackIndex == -1

            while (!videoDone || !audioDone) {
                val videoSampleTime = if (videoDone) {
                    Long.MAX_VALUE
                } else {
                    val t = vExtractor.sampleTime
                    if (t == -1L) {
                        videoDone = true
                        Long.MAX_VALUE
                    } else t
                }
                val audioSampleTime = if (audioDone) {
                    Long.MAX_VALUE
                } else {
                    val t = aExtractor.sampleTime
                    if (t == -1L) {
                        audioDone = true
                        Long.MAX_VALUE
                    } else t
                }

                if (videoDone && audioDone) break

                val useVideo = when {
                    videoDone -> false
                    audioDone -> true
                    else -> videoSampleTime <= audioSampleTime
                }

                if (useVideo) {
                    videoBuffer.clear()
                    var sampleSize = vExtractor.readSampleData(videoBuffer, 0)
                    if (sampleSize < 0) {
                        videoDone = true
                        continue
                    }
                    if (sampleSize > videoBuffer.capacity()) {
                        videoBuffer = ByteBuffer.allocate(sampleSize)
                        videoBuffer.clear()
                        sampleSize = vExtractor.readSampleData(videoBuffer, 0)
                        if (sampleSize < 0) {
                            videoDone = true
                            continue
                        }
                    }
                    val bufferInfo = MediaCodec.BufferInfo().apply {
                        set(0, sampleSize, vExtractor.sampleTime, vExtractor.sampleFlags)
                    }
                    mMuxer.writeSampleData(videoMuxerTrackIndex, videoBuffer, bufferInfo)
                    vExtractor.advance()
                } else {
                    audioBuffer.clear()
                    var sampleSize = aExtractor.readSampleData(audioBuffer, 0)
                    if (sampleSize < 0) {
                        audioDone = true
                        continue
                    }
                    if (sampleSize > audioBuffer.capacity()) {
                        audioBuffer = ByteBuffer.allocate(sampleSize)
                        audioBuffer.clear()
                        sampleSize = aExtractor.readSampleData(audioBuffer, 0)
                        if (sampleSize < 0) {
                            audioDone = true
                            continue
                        }
                    }
                    val bufferInfo = MediaCodec.BufferInfo().apply {
                        set(0, sampleSize, aExtractor.sampleTime, aExtractor.sampleFlags)
                    }
                    mMuxer.writeSampleData(audioMuxerTrackIndex, audioBuffer, bufferInfo)
                    aExtractor.advance()
                }
            }

            Log.d(TAG, "mux successful -> ${output.absolutePath} size=${output.length()}")
        } catch (e: SabrDownloadException) {
            runCatching { if (output.exists()) output.delete() }
            throw e
        } catch (e: Exception) {
            runCatching { if (output.exists()) output.delete() }
            throw SabrDownloadException(
                SabrDownloadException.Reason.MUXING,
                "SABR download failed: MediaMuxer remux failed: ${e.message ?: e.javaClass.simpleName}",
                e,
            )
        } finally {
            runCatching { videoExtractor?.release() }
            runCatching { audioExtractor?.release() }
            muxer?.let { m ->
                runCatching { m.stop() }
                runCatching { m.release() }
            }
        }
    }
}
