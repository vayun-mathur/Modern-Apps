package com.vayunmathur.camera.util

import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log

/**
 * Reports whether the device can encode with modern video codecs. AV1 is only offered when a
 * true hardware encoder is present, because software AV1 encoding is too slow for realtime capture.
 */
object CodecSupport {

    /** A hardware-accelerated AV1 (`video/av01`) encoder exists on this device. */
    val isHardwareAv1EncoderAvailable: Boolean by lazy {
        hasEncoder(MediaFormat.MIMETYPE_VIDEO_AV1, requireHardware = true)
    }

    /** An HEVC/H.265 (`video/hevc`) encoder exists on this device. */
    val isHevcEncoderAvailable: Boolean by lazy {
        hasEncoder(MediaFormat.MIMETYPE_VIDEO_HEVC, requireHardware = false)
    }

    private fun hasEncoder(mimeType: String, requireHardware: Boolean): Boolean {
        return try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder &&
                    (!requireHardware || info.isHardwareAccelerated) &&
                    info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
            }
        } catch (e: Exception) {
            Log.w("CodecSupport", "Failed to query encoders for $mimeType", e)
            false
        }
    }
}
