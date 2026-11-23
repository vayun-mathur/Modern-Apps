package com.vayunmathur.camera.platform

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import com.vayunmathur.camera.domain.ArgbToI420Converter
import com.vayunmathur.camera.domain.YuvPlane
import com.vayunmathur.library.log.Log
import java.io.ByteArrayOutputStream

/**
 * Still-AVIF encoding through a platform `image/avif` MediaCodec encoder,
 * when the device actually ships one (see [AvifCodecProbe]).
 *
 * The frame is fed as YUV 4:2:0 through the codec's input [android.media.Image]
 * (the same bulk conversion the Motion Photo encoder uses), the encoded
 * AVIF file is collected from the output buffers, and the EXIF TIFF is
 * spliced into it natively (`StitchNative.muxAvifExif`) because image
 * encoders have no EXIF input. Every failure — no encoder, size outside
 * its caps, configure/feed/drain errors, non-AVIF output, mux refusal —
 * returns null so [StillEncoder] falls back to the software ravif path;
 * a shot is never lost and never lands EXIF-less. Blocking: call off the
 * main thread.
 */
internal object AvifHardwareEncoder {

    private const val TIMEOUT_US = 10_000L
    private const val DEADLINE_MS = 20_000L
    private const val YUV420_NUMERATOR = 3
    private const val YUV420_DENOMINATOR = 2

    /** True when a platform still-AVIF encoder exists (cached probe). */
    fun isAvailable(): Boolean = AvifCodecProbe.stillEncoder() != null

    /**
     * Encodes [bitmap] to AVIF-with-EXIF through the hardware encoder,
     * or null when unavailable/unsuccessful. [exifTiff] is required: a
     * hardware file without the muxed Exif item is treated as failure.
     */
    fun encode(bitmap: Bitmap, exifTiff: ByteArray, quality: Float): ByteArray? {
        val entry = AvifCodecProbe.stillEncoder() ?: return null
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) {
            Log.debug("StillSave", "avifHw skipped ${width}x$height (odd/empty); software fallback")
            return null
        }
        if (!AvifCodecRouting.fitsSize(entry, width, height)) {
            Log.debug(
                "StillSave",
                "avifHw skipped ${width}x$height exceeds ${entry.name} cap " +
                    "${entry.maxWidth}x${entry.maxHeight}; software fallback",
            )
            return null
        }
        val started = SystemClock.elapsedRealtime()
        val raw = try {
            encodeViaCodec(entry.name, bitmap, width, height, quality)
        } catch (e: Exception) {
            Log.status("StillEncoder", "Hardware AVIF encode failed; software fallback", e)
            null
        } ?: return null
        if (raw.size < 12 || String(raw, 4, 8, Charsets.US_ASCII) != "ftypavif") {
            Log.status("StillEncoder", "Hardware AVIF output is not an AVIF file; software fallback")
            return null
        }
        if (!StitchNative.isAvailable) return null
        val muxed = try {
            StitchNative.muxAvifExif(raw, exifTiff)
        } catch (e: UnsatisfiedLinkError) {
            Log.status("StillEncoder", "AVIF EXIF mux unavailable; software fallback", e)
            null
        } ?: run {
            Log.status("StillEncoder", "Hardware AVIF EXIF mux failed; software fallback")
            return null
        }
        Log.debug(
            "StillSave",
            "avifHwEncodeMs=${SystemClock.elapsedRealtime() - started} " +
                "codec=${entry.name} hw=${entry.hardwareAccelerated} ${width}x$height bytes=${muxed.size}",
        )
        return muxed
    }

    /** One-frame MediaCodec image encode: feed YUV420, EOS, drain to EOS. */
    private fun encodeViaCodec(
        codecName: String, bitmap: Bitmap, width: Int, height: Int, quality: Float,
    ): ByteArray? {
        var codec: MediaCodec? = null
        try {
            codec = MediaCodec.createByCodecName(codecName)
            val format = MediaFormat.createImageFormat(AVIF_IMAGE_MIME, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                )
                setInteger(MediaFormat.KEY_QUALITY, quality.toInt().coerceIn(1, 100))
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            feed(codec, bitmap, width, height)
            return drain(codec)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
        }
    }

    private fun feed(codec: MediaCodec, bitmap: Bitmap, width: Int, height: Int) {
        val deadline = SystemClock.elapsedRealtime() + DEADLINE_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inIndex < 0) continue
            val image = codec.getInputImage(inIndex)
            if (image != null) {
                val argb = IntArray(width * height)
                bitmap.getPixels(argb, 0, width, 0, 0, width, height)
                val planes = image.planes
                ArgbToI420Converter(width, height).convert(
                    argb,
                    YuvPlane(planes[0].buffer, planes[0].rowStride, planes[0].pixelStride),
                    YuvPlane(planes[1].buffer, planes[1].rowStride, planes[1].pixelStride),
                    YuvPlane(planes[2].buffer, planes[2].rowStride, planes[2].pixelStride),
                )
            }
            val size = width * height * YUV420_NUMERATOR / YUV420_DENOMINATOR
            codec.queueInputBuffer(inIndex, 0, size, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return
        }
        error("Timed out waiting for an AVIF encoder input buffer")
    }

    private fun drain(codec: MediaCodec): ByteArray? {
        val out = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        val deadline = SystemClock.elapsedRealtime() + DEADLINE_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (outIndex >= 0) {
                val buffer = codec.getOutputBuffer(outIndex)
                if (buffer != null && info.size > 0 &&
                    (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                ) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val chunk = ByteArray(info.size)
                    buffer.get(chunk)
                    out.write(chunk)
                }
                codec.releaseOutputBuffer(outIndex, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    return out.toByteArray().takeIf { it.isNotEmpty() }
                }
            }
        }
        return null
    }
}
