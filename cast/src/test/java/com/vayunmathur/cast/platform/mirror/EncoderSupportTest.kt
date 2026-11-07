package com.vayunmathur.cast.platform.mirror

import android.media.MediaCodecInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.cast.protocol.AudioCodec
import com.vayunmathur.cast.protocol.VideoCodec
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EncoderSupportTest {

    @Test
    fun `audio mime comes from protocol`() {
        assertEquals(AudioCodec.Opus.mimeType, EncoderSupport.AUDIO_MIME)
    }

    @Test
    fun `no hardware encoders under robolectric means empty codec list`() {
        // Robolectric has no hardware MediaCodec encoders; the honest answer is empty/null/0,
        // which is exactly the refusal path production takes on incapable hardware.
        assertTrue(EncoderSupport.videoCodecs(1280, 720).isEmpty())
        assertNull(EncoderSupport.videoEncoderName(VideoCodec.Hevc))
        assertNull(EncoderSupport.videoEncoderName(VideoCodec.Av1))
        assertEquals(0f, EncoderSupport.sustainableFrameRate(VideoCodec.Hevc, 1280, 720))
    }

    @Test
    fun `clamp without encoder returns input unchanged`() {
        assertEquals(1344 to 2992, EncoderSupport.clampToEncoder(VideoCodec.Av1, 1344, 2992, 30f))
        assertEquals(0 to 0, EncoderSupport.clampToEncoder(VideoCodec.Hevc, 0, 0, 30f))
    }

    @Test
    fun `bitrate mode falls back to VBR when encoder unknown`() {
        assertEquals(
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
            EncoderSupport.videoBitrateMode(VideoCodec.Hevc, "no.such.encoder"),
        )
    }

    @Test
    fun `opus encoder cannot start without a device encoder`() {
        val opus = OpusEncoder()
        assertEquals(false, opus.start())
        assertTrue(opus.encode(byteArrayOf(1, 2, 3, 4), 4).isEmpty())
        assertTrue(opus.encode(byteArrayOf(), 0).isEmpty())
        opus.release()
        opus.release()
    }

    @Test
    fun `video encoder cannot start and drains empty`() {
        val encoder = VideoEncoder(VideoCodec.Hevc, 640, 480, 30f, 1_000_000)
        assertEquals(false, encoder.start())
        assertTrue(encoder.drain().isEmpty())
        assertEquals(false, encoder.hasCodecConfig)
        assertNull(encoder.inputSurface)
        encoder.requestKeyFrame()
        encoder.release()
        encoder.release()
    }

    @Test
    fun `encoded chunk equality is content based`() {
        val a = EncodedChunk(byteArrayOf(1, 2), 100L, true)
        val b = EncodedChunk(byteArrayOf(1, 2), 100L, true)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a != EncodedChunk(byteArrayOf(1, 3), 100L, true))
        assertTrue(a != EncodedChunk(byteArrayOf(1, 2), 101L, true))
        assertTrue(a != EncodedChunk(byteArrayOf(1, 2), 100L, false))
    }

    @Test
    fun `hex preview formats and truncates`() {
        assertEquals("00 0f ff", byteArrayOf(0, 15, -1).hexPreview())
        assertEquals("", byteArrayOf().hexPreview())
        val long = ByteArray(20) { it.toByte() }
        assertEquals(16, long.hexPreview().split(" ").size)
        assertEquals(4, long.hexPreview(4).split(" ").size)
    }
}
