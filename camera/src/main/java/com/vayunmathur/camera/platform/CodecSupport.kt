package com.vayunmathur.camera.platform

import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.camera.video.ExperimentalMimeTypeApi
import androidx.camera.video.Recorder

/**
 * Which modern video codecs this device can encode, sourced from CameraX's
 * [Recorder.getSupportedVideoMimeTypes] — the formats the Recorder itself reports it can encode on
 * this device — instead of scraping [android.media.MediaCodecList] directly. A mime appearing here
 * means it's safe to request via `Recorder.Builder.setVideoMimeType`.
 */
@OptIn(ExperimentalMimeTypeApi::class)
object CodecSupport {

    private val supportedVideoMimeTypes: List<String> by lazy {
        try {
            Recorder.getSupportedVideoMimeTypes()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private val supportedAudioMimeTypes: List<String> by lazy {
        try {
            Recorder.getSupportedAudioMimeTypes()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun supports(mimeType: String): Boolean =
        supportedVideoMimeTypes.any { it.equals(mimeType, ignoreCase = true) }

    private fun supportsAudio(mimeType: String): Boolean =
        supportedAudioMimeTypes.any { it.equals(mimeType, ignoreCase = true) }

    /** The Recorder can encode AV1 (`video/av01`) on this device. */
    val isHardwareAv1EncoderAvailable: Boolean by lazy { supports(MediaFormat.MIMETYPE_VIDEO_AV1) }

    /** The Recorder can encode HEVC/H.265 (`video/hevc`) on this device. */
    val isHevcEncoderAvailable: Boolean by lazy { supports(MediaFormat.MIMETYPE_VIDEO_HEVC) }

    /** The Recorder can encode Opus (`audio/opus`) on this device. */
    val isOpusAudioEncoderAvailable: Boolean by lazy {
        supportsAudio(MediaFormat.MIMETYPE_AUDIO_OPUS)
    }

    /**
     * True when some encoder for [mimeType] supports [bitrateBps] at
     * [width]x[height]@[fps]. Used to gate the adaptive high-motion boost: the
     * Recorder accepts any target bitrate, but requesting one outside the
     * encoder's range only buys macro-blocking (or a failed configure).
     */
    fun supportsVideoBitrate(
        mimeType: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrateBps: Int,
    ): Boolean = try {
        val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
        list.codecInfos.any { info ->
            if (!info.isEncoder) return@any false
            val caps = try {
                info.getCapabilitiesForType(mimeType)
            } catch (_: IllegalArgumentException) {
                return@any false
            }
            val video = caps.videoCapabilities ?: return@any false
            video.isSizeSupported(width, height) &&
                video.getSupportedFrameRatesFor(width, height).contains(fps.toDouble()) &&
                video.bitrateRange.contains(bitrateBps)
        }
    } catch (_: Exception) {
        false
    }

    /**
     * Default recording codec (issue #757): HEVC when the Recorder supports it, else AVC.
     * AV1 hardware encoders stall on several devices, so AV1 stays opt-in via Settings.
     */
    val defaultVideoCodec: VideoCodec
        get() = if (isHevcEncoderAvailable) VideoCodec.HEVC else VideoCodec.AVC
}
