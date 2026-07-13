package com.vayunmathur.camera.util

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Encodes a short list of bitmaps into an H.264 MP4 (used as the trailing clip of a Motion Photo).
 *
 * Uses MediaCodec in ByteBuffer/Image mode with COLOR_FormatYUV420Flexible: each ARGB bitmap is
 * converted to I420 into the codec's input [android.media.Image], respecting its plane strides, so
 * the path stays portable across encoders without needing EGL. Runs synchronously on the caller's
 * (background) thread. Rotation is carried by the muxer orientation hint rather than rotating pixels.
 */
object MotionPhotoEncoder {

    private const val TAG = "MotionPhotoEncoder"
    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val TIMEOUT_US = 10_000L

    /**
     * Encodes [frames] to [outputFile] at [fps]. All frames must share dimensions (the first
     * frame's are used). Returns true on success. The caller owns recycling the bitmaps.
     */
    fun encode(frames: List<Bitmap>, outputFile: File, rotationDegrees: Int, fps: Int = 15): Boolean {
        if (frames.isEmpty()) return false
        // Encoders require even dimensions.
        val width = frames[0].width and 1.inv()
        val height = frames[0].height and 1.inv()
        if (width < 2 || height < 2) return false

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var trackIndex = -1
        try {
            val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )
                setInteger(MediaFormat.KEY_BIT_RATE, width * height * 4)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            codec = MediaCodec.createEncoderByType(MIME)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(outputFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(rotationDegrees)

            val bufferInfo = MediaCodec.BufferInfo()
            var frameIndex = 0
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        if (frameIndex < frames.size) {
                            val ptsUs = frameIndex * 1_000_000L / fps
                            val image = codec.getInputImage(inIndex)
                            if (image != null) {
                                fillI420(image, frames[frameIndex], width, height)
                            }
                            val size = width * height * 3 / 2
                            codec.queueInputBuffer(inIndex, 0, size, ptsUs, 0)
                            frameIndex++
                        } else {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, frameIndex * 1_000_000L / fps,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIndex >= 0 -> {
                        val encoded: ByteBuffer? = codec.getOutputBuffer(outIndex)
                        if (encoded != null && muxerStarted &&
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                            bufferInfo.size > 0
                        ) {
                            encoded.position(bufferInfo.offset)
                            encoded.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, encoded, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Motion Photo MP4 encode failed", e)
            return false
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    /** Converts an ARGB_8888 [bitmap] into the YUV [image]'s I420 planes (BT.601). */
    private fun fillI420(image: android.media.Image, bitmap: Bitmap, width: Int, height: Int) {
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixStride = uPlane.pixelStride
        val vPixStride = vPlane.pixelStride

        for (y in 0 until height) {
            for (x in 0 until width) {
                val c = argb[y * width + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val yy = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yBuf.put(y * yRowStride + x, yy.coerceIn(0, 255).toByte())
                if (y and 1 == 0 && x and 1 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val cx = x / 2
                    val cy = y / 2
                    uBuf.put(cy * uRowStride + cx * uPixStride, u.coerceIn(0, 255).toByte())
                    vBuf.put(cy * vRowStride + cx * vPixStride, v.coerceIn(0, 255).toByte())
                }
            }
        }
    }
}
