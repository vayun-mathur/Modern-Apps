package com.vayunmathur.cast.tv.platform

import android.graphics.SurfaceTexture
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.vayunmathur.cast.protocol.VideoCodec
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodec
import org.robolectric.shadows.ShadowMediaCodecList

/**
 * Fake hardware decoders in Robolectric's codec list, mirrored from the sender's
 * FakeEncoders. Reset afterwards: other tests must keep seeing a decoder-less TV.
 */
object FakeDecoders {

    fun install() {
        ShadowMediaCodecList.reset()
        addVideo("fake.hevc.decoder", VideoCodec.Hevc.mimeType, 3840 to 2160)
        addVideo("fake.av1.decoder", VideoCodec.Av1.mimeType, 1920 to 1080)
        addAudio("fake.opus.decoder")
    }

    fun reset() {
        ShadowMediaCodecList.reset()
        ShadowMediaCodec.clearCodecs()
    }

    private fun registerShadowCodec(name: String) {
        ShadowMediaCodec.addDecoder(
            name,
            ShadowMediaCodec.CodecConfig(BUFFER_BYTES, BUFFER_BYTES) { input, output ->
                output.put(input)
            },
        )
    }

    private const val BUFFER_BYTES = 16_384

    private fun addVideo(name: String, mime: String, size: Pair<Int, Int>) {
        val format = MediaFormat.createVideoFormat(mime, size.first, size.second)
        val caps = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(format)
            .setColorFormats(intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface))
            .setIsEncoder(false)
            .build()
        ShadowMediaCodecList.addCodec(
            MediaCodecInfoBuilder.newBuilder()
                .setName(name)
                .setIsEncoder(false)
                .setIsHardwareAccelerated(true)
                .setIsSoftwareOnly(false)
                .setCapabilities(caps)
                .build(),
        )
        registerShadowCodec(name)
    }

    private fun addAudio(name: String) {
        val format = MediaFormat.createAudioFormat(AudioPlayer.AUDIO_MIME, 48_000, 2)
        val caps = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(format)
            .setIsEncoder(false)
            .build()
        ShadowMediaCodecList.addCodec(
            MediaCodecInfoBuilder.newBuilder()
                .setName(name)
                .setIsEncoder(false)
                .setIsHardwareAccelerated(true)
                .setIsSoftwareOnly(false)
                .setCapabilities(caps)
                .build(),
        )
        registerShadowCodec(name)
    }
}

/** A Surface over a retained texture, for decoder construction. */
class TestSurface {
    val texture = SurfaceTexture(7)
    val surface = Surface(texture)
}
