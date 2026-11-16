package com.vayunmathur.cast.tv.platform

import com.vayunmathur.cast.protocol.VideoCodec
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun addDecoderForTest(name: String, mime: String, size: Pair<Int, Int>, hardware: Boolean) {
    val format = android.media.MediaFormat.createVideoFormat(mime, size.first, size.second)
    val caps = org.robolectric.shadows.MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
        .setMediaFormat(format)
        .setColorFormats(intArrayOf(android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface))
        .setIsEncoder(false)
        .build()
    org.robolectric.shadows.ShadowMediaCodecList.addCodec(
        org.robolectric.shadows.MediaCodecInfoBuilder.newBuilder()
            .setName(name)
            .setIsEncoder(false)
            .setIsHardwareAccelerated(hardware)
            .setIsSoftwareOnly(!hardware)
            .setCapabilities(caps)
            .build(),
    )
    org.robolectric.shadows.ShadowMediaCodec.addDecoder(
        name,
        org.robolectric.shadows.ShadowMediaCodec.CodecConfig(16_384, 16_384) { input, output -> output.put(input) },
    )
}

/**
 * The decoder/player layer against fake hardware codecs.
 *
 * Without fakes these classes only exercise their no-decoder branches on the
 * host; with them the start/decode/play paths run against Robolectric's shadow
 * MediaCodec and AudioTrack, and the capability advertisement is verified
 * against the envelopes the fakes publish.
 */
@RunWith(RobolectricTestRunner::class)
class PlayersDeepTest {

    @AfterTest fun tearDown() {
        FakeDecoders.reset()
    }

    @Test fun limitsAdvertiseFakeDecoders() {
        FakeDecoders.install()
        val limits = VideoDecoder.limits()
        // Every codec the fakes publish must be advertised with a positive envelope.
        assertEquals(VideoCodec.entries.size, limits.videoCodecs.size)
        for (advertised in limits.videoCodecs) {
            assertTrue(advertised.maxWidth > 0, advertised.toString())
            assertTrue(advertised.maxHeight > 0, advertised.toString())
        }
        assertEquals("fake.hevc.decoder", VideoDecoder.decoderName(VideoCodec.Hevc))
        assertEquals("fake.av1.decoder", VideoDecoder.decoderName(VideoCodec.Av1))
        assertEquals(listOf(com.vayunmathur.cast.protocol.AudioCodec.Opus), AudioPlayer.limits())
        assertEquals("fake.opus.decoder", AudioPlayer.decoderName())
    }

    @Test fun videoDecoderStartsDecodesRendersAndReleases() {
        FakeDecoders.install()
        val surface = TestSurface()
        val decoder = VideoDecoder(surface.surface, VideoCodec.Hevc)
        // Frames before start are dropped and counted, never queued.
        assertFalse(decoder.decode(ByteArray(16) { 1 }, 0L, isKeyFrame = true))
        assertEquals(1L, decoder.framesDropped)
        decoder.render() // no codec: a no-op, not a fault

        assertTrue(decoder.start(320, 240))
        assertTrue(decoder.decode(ByteArray(64) { 2 }, 33_000L, isKeyFrame = true))
        assertTrue(decoder.decode(ByteArray(64) { 3 }, 66_000L, isKeyFrame = false))
        decoder.render()
        decoder.release()
        // After release, frames drop again.
        assertFalse(decoder.decode(ByteArray(8) { 1 }, 0L, isKeyFrame = false))
        surface.texture.release()
    }

    @Test fun av1DecoderTakesSynthesisedConfig() {
        FakeDecoders.install()
        val surface = TestSurface()
        val decoder = VideoDecoder(surface.surface, VideoCodec.Av1)
        assertTrue(decoder.start(640, 480, codecConfig = byteArrayOf(0x0a, 0x0e, 0x00)))
        assertTrue(decoder.decode(ByteArray(32) { 9 }, 0L, isKeyFrame = true))
        decoder.render()
        decoder.release()
        surface.texture.release()
    }

    @Test fun audioPlayerStartsPlaysAndReleases() {
        FakeDecoders.install()
        val player = AudioPlayer()
        // Nothing before start.
        player.play(ByteArray(20) { 1 }, 0L)
        player.setVolume(0.5f) // no track yet: ignored
        assertTrue(player.start())
        player.setVolume(0.5f)
        player.setVolume(2f) // clamped onto the track
        repeat(3) { player.play(ByteArray(40) { (it + 1).toByte() }, it * 20_000L) }
        assertFalse(player.failed)
        assertEquals(0, player.restarts)
        player.release()
        // Released: play is a no-op, release is idempotent.
        player.play(ByteArray(20) { 1 }, 0L)
        player.release()
    }

    @Test fun decoderNamePicksLargestHardwareDecoder() {
        // Two rival HEVC decoders plus a software one: the largest hardware wins.
        org.robolectric.shadows.ShadowMediaCodecList.reset()
        addDecoderForTest("fake.hevc.small", VideoCodec.Hevc.mimeType, 640 to 480, hardware = true)
        addDecoderForTest("fake.hevc.big", VideoCodec.Hevc.mimeType, 3840 to 2160, hardware = true)
        addDecoderForTest("fake.hevc.software", VideoCodec.Hevc.mimeType, 7680 to 4320, hardware = false)
        assertEquals("fake.hevc.big", VideoDecoder.decoderName(VideoCodec.Hevc))
        val limits = VideoDecoder.limits()
        val hevc = limits.videoCodecs.single { it.codec == VideoCodec.Hevc }
        assertEquals(3840, hevc.maxWidth)
        FakeDecoders.reset()
    }

    @Test fun playersFailSoftWithoutDecoders() {
        // No fakes installed: the honest "this TV cannot" answers.
        assertTrue(VideoDecoder.limits().videoCodecs.isEmpty())
        assertTrue(AudioPlayer.limits().isEmpty())
        val surface = TestSurface()
        val decoder = VideoDecoder(surface.surface, VideoCodec.Hevc)
        assertFalse(decoder.start(320, 240))
        val player = AudioPlayer()
        assertFalse(player.start())
        player.release()
        surface.texture.release()
    }
}
