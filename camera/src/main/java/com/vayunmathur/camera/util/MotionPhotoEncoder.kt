package com.vayunmathur.camera.util

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.vayunmathur.camera.domain.ArgbToI420Converter
import com.vayunmathur.camera.domain.YuvPlane
import com.vayunmathur.library.log.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Encodes a short list of bitmaps into an H.264 MP4 (used as the trailing clip of a Motion Photo).
 *
 * Uses MediaCodec in ByteBuffer/Image mode with COLOR_FormatYUV420Flexible: each ARGB bitmap is
 * read with one bulk getPixels() and converted by [ArgbToI420Converter] into the codec's input
 * [android.media.Image] row by row with bulk buffer writes, respecting its plane strides (planar
 * or semi-planar), so the path stays portable across encoders without needing EGL. Runs synchronously on the caller's
 * (background) thread. Rotation is carried by the muxer orientation hint rather than rotating pixels.
 */
object MotionPhotoEncoder {

    private const val TAG = "MotionPhotoEncoder"
    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val TIMEOUT_US = 10_000L
    /** Micros per second; YUV420 bytes per pixel (3/2). */
    private const val MICROS_PER_SECOND = 1_000_000L
    private const val YUV420_NUMERATOR = 3
    private const val YUV420_DENOMINATOR = 2

    /**
     * Encodes [frames] to [outputFile] at [fps]. All frames must share dimensions (the first
     * frame's are used). Returns true on success. The caller owns recycling the bitmaps.
     */
    fun encode(frames: List<Bitmap>, outputFile: File, rotationDegrees: Int, fps: Int = DEFAULT_FPS): Boolean {
        if (frames.isEmpty()) return false
        // Encoders require even dimensions.
        val width = frames[0].width and 1.inv()
        val height = frames[0].height and 1.inv()
        if (width < MIN_ENCODE_SIDE || height < MIN_ENCODE_SIDE) return false

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            codec = startEncoder(width, height, fps)
            muxer = MediaMuxer(outputFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(rotationDegrees)
            val pump = FramePump(codec, muxer, frames, width, height, fps)
            pump.run()
            muxerStarted = pump.muxerStarted
            return true
        } catch (e: java.io.IOException) {
            Log.error(TAG, "Motion Photo MP4 encode failed", e)
            return false
        } catch (e: IllegalStateException) {
            Log.error(TAG, "Motion Photo MP4 encode failed", e)
            return false
        } catch (e: IllegalArgumentException) {
            Log.error(TAG, "Motion Photo MP4 encode failed", e)
            return false
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    /** Minimum (even) encode dimension; encoder timeout; default fps. */
    private const val MIN_ENCODE_SIDE = 2
    private const val DEFAULT_FPS = 15

    /** Creates, configures and starts the AVC encoder for [width]x[height]@[fps]. */
    private fun startEncoder(width: Int, height: Int, fps: Int): MediaCodec {
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            setInteger(MediaFormat.KEY_BIT_RATE, width * height * 4)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        return MediaCodec.createEncoderByType(MIME).also {
            it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            it.start()
        }
    }

    /** Pumps frames into the encoder and muxes output until end-of-stream. */
    private class FramePump(
        private val codec: MediaCodec,
        private val muxer: MediaMuxer,
        private val frames: List<Bitmap>,
        private val width: Int,
        private val height: Int,
        private val fps: Int
    ) {
        var muxerStarted = false
            private set
        private var trackIndex = -1
        private val bufferInfo = MediaCodec.BufferInfo()
        // Reused across frames: one pixel buffer and one converter (scratch rows) per encode.
        private val argb = IntArray(width * height)
        private val converter = ArgbToI420Converter(width, height)

        fun run() {
            var frameIndex = 0
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        if (frameIndex < frames.size) {
                            queueFrame(inIndex, frameIndex)
                            frameIndex++
                        } else {
                            queueEndOfStream(inIndex, frameIndex)
                            inputDone = true
                        }
                    }
                }
                outputDone = drainOutput()
            }
        }

        private fun queueFrame(inIndex: Int, frameIndex: Int) {
            val ptsUs = frameIndex * MICROS_PER_SECOND / fps
            val image = codec.getInputImage(inIndex)
            if (image != null) {
                frames[frameIndex].getPixels(argb, 0, width, 0, 0, width, height)
                val planes = image.planes
                converter.convert(
                    argb,
                    YuvPlane(planes[0].buffer, planes[0].rowStride, planes[0].pixelStride),
                    YuvPlane(planes[1].buffer, planes[1].rowStride, planes[1].pixelStride),
                    YuvPlane(planes[2].buffer, planes[2].rowStride, planes[2].pixelStride)
                )
            }
            val size = width * height * YUV420_NUMERATOR / YUV420_DENOMINATOR
            codec.queueInputBuffer(inIndex, 0, size, ptsUs, 0)
        }

        private fun queueEndOfStream(inIndex: Int, frameIndex: Int) {
            codec.queueInputBuffer(
                inIndex, 0, 0, frameIndex * MICROS_PER_SECOND / fps,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )
        }

        /** Drains one output buffer; returns true when end-of-stream is reached. */
        private fun drainOutput(): Boolean {
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outIndex >= 0 -> {
                    writeSample(outIndex)
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        return true
                    }
                }
            }
            return false
        }

        private fun writeSample(outIndex: Int) {
            val encoded: ByteBuffer? = codec.getOutputBuffer(outIndex)
            if (isConfigFreeSample(encoded)) {
                encoded!!.position(bufferInfo.offset)
                encoded.limit(bufferInfo.offset + bufferInfo.size)
                muxer.writeSampleData(trackIndex, encoded, bufferInfo)
            }
        }

        /** True when the output buffer holds a non-empty, non-codec-config sample. */
        private fun isConfigFreeSample(encoded: ByteBuffer?): Boolean {
            return encoded != null && muxerStarted &&
                (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                bufferInfo.size > 0
        }
    }
}
