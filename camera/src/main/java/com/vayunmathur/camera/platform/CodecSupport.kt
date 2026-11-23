package com.vayunmathur.camera.platform

import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * Which modern video codecs this device can encode, sourced from the
 * platform [MediaCodecList] encoder registry (the Camera2 recording
 * engine configures a `MediaCodec` encoder directly, so the encoder
 * registry is the honest source). A mime appearing here means some
 * encoder on this device accepts it.
 */
object CodecSupport {

    private fun encoderMimeTypes(): List<String> = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder }
            .flatMap { info -> info.supportedTypes.toList() }
            .distinct()
    } catch (_: Exception) {
        emptyList()
    }

    private val supportedVideoMimeTypes: List<String> by lazy {
        encoderMimeTypes().filter { it.startsWith("video/") }
    }

    private val supportedAudioMimeTypes: List<String> by lazy {
        encoderMimeTypes().filter { it.startsWith("audio/") }
    }

    private fun supports(mimeType: String): Boolean =
        supportedVideoMimeTypes.any { it.equals(mimeType, ignoreCase = true) }

    private fun supportsAudio(mimeType: String): Boolean =
        supportedAudioMimeTypes.any { it.equals(mimeType, ignoreCase = true) }

    /** The Recorder can encode AV1 (`video/av01`) on this device. */
    val isHardwareAv1EncoderAvailable: Boolean by lazy { supports(MediaFormat.MIMETYPE_VIDEO_AV1) }

    /** The Recorder can encode HEVC/H.265 (`video/hevc`) on this device. */
    val isHevcEncoderAvailable: Boolean by lazy { supports(MediaFormat.MIMETYPE_VIDEO_HEVC) }

    /**
     * An Opus (`audio/opus`) encoder exists on this device. Opus is the
     * only audio encoding the recording core will ever use (see
     * AudioEncoderPolicy): false means recordings proceed video-only,
     * never AAC.
     */
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
     * True when some encoder for [mimeType] supports [fps] at
     * [width]x[height], ignoring bitrate. Used by the Phase-5 slo-mo
     * decision: an encoder that cannot take 120/240 fps input at the
     * high-speed size cannot record slo-mo, whatever its bitrate range.
     */
    fun supportsVideoFrameRate(
        mimeType: String,
        width: Int,
        height: Int,
        fps: Int,
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
                video.getSupportedFrameRatesFor(width, height).contains(fps.toDouble())
        }
    } catch (_: Exception) {
        false
    }

    /**
     * Default recording codec (issue #757): HEVC when an encoder supports it, else AVC.
     * AV1 hardware encoders stall on several devices, so AV1 stays opt-in via Settings.
     */
    val defaultVideoCodec: VideoCodec
        get() = if (isHevcEncoderAvailable) VideoCodec.HEVC else VideoCodec.AVC
}
