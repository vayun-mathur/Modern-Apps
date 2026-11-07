package com.vayunmathur.cast.platform.mirror

import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.vayunmathur.cast.protocol.VideoCodec
import java.nio.ByteBuffer
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodec
import org.robolectric.shadows.ShadowMediaCodecList

/**
 * Installs fake hardware encoders into Robolectric's codec list, so the encoder
 * support and pipeline code can run without a device. Call [reset] afterwards:
 * other code under test must keep seeing an encoder-less device.
 */
object FakeEncoders {

    fun install(
        hevcSize: Pair<Int, Int> = 3840 to 2160,
        av1Size: Pair<Int, Int> = 1920 to 1080,
        opus: Boolean = true,
    ) {
        ShadowMediaCodecList.reset()
        addVideo("fake.hevc.encoder", VideoCodec.Hevc.mimeType, hevcSize)
        addVideo("fake.av1.encoder", VideoCodec.Av1.mimeType, av1Size)
        if (opus) addAudio("fake.opus.encoder")
    }

    fun installSmallHevcOnly() {
        ShadowMediaCodecList.reset()
        val format = MediaFormat.createVideoFormat(VideoCodec.Hevc.mimeType, 640, 480).apply {
            setInteger(MediaFormat.KEY_MAX_WIDTH, 640)
            setInteger(MediaFormat.KEY_MAX_HEIGHT, 480)
        }
        addVideo("fake.small.hevc.encoder", format)
    }

    fun reset() {
        ShadowMediaCodecList.reset()
        ShadowMediaCodec.clearCodecs()
    }

    /** Big shadow-codec buffers: a real encoder takes a whole 20 ms frame at once. */
    private fun registerShadowCodec(name: String) {
        ShadowMediaCodec.addEncoder(
            name,
            ShadowMediaCodec.CodecConfig(BUFFER_BYTES, BUFFER_BYTES) { input: ByteBuffer, output: ByteBuffer ->
                output.put(input)
            },
        )
    }

    private const val BUFFER_BYTES = 16_384

    private fun addVideo(name: String, mime: String, size: Pair<Int, Int>) =
        addVideo(name, MediaFormat.createVideoFormat(mime, size.first, size.second))

    private fun addVideo(name: String, format: MediaFormat) {
        val caps = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(format)
            .setColorFormats(intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface))
            .setIsEncoder(true)
            .build()
        ShadowMediaCodecList.addCodec(
            MediaCodecInfoBuilder.newBuilder()
                .setName(name)
                .setIsEncoder(true)
                .setIsHardwareAccelerated(true)
                .setIsSoftwareOnly(false)
                .setCapabilities(caps)
                .build(),
        )
        registerShadowCodec(name)
    }

    private fun addAudio(name: String) {
        val format = MediaFormat.createAudioFormat(EncoderSupport.AUDIO_MIME, 48_000, 2)
        val caps = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(format)
            .setIsEncoder(true)
            .build()
        ShadowMediaCodecList.addCodec(
            MediaCodecInfoBuilder.newBuilder()
                .setName(name)
                .setIsEncoder(true)
                .setIsHardwareAccelerated(true)
                .setIsSoftwareOnly(false)
                .setCapabilities(caps)
                .build(),
        )
        registerShadowCodec(name)
    }
}
