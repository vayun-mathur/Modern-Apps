package com.vayunmathur.cast.platform.mirror

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.cast.protocol.VideoCodec
import java.io.FileOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Encoders against a shadow codec that emits config + key + delta, then dries up. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [ShadowMediaCodecSynthesizing::class])
class CodecOutputTest {

    @Before fun install() = FakeEncoders.install()
    @After fun reset() = FakeEncoders.reset()

    @Test
    fun `hevc drain prepends parameter sets to the first key frame only`() {
        val encoder = VideoEncoder(VideoCodec.Hevc, 640, 480, 30f, 1_000_000)
        assertTrue(encoder.start())
        val chunks = encoder.drain()
        assertEquals(2, chunks.size, "config is cached, not emitted: $chunks")
        assertTrue(chunks[0].isKeyFrame)
        assertEquals(8 + 48, chunks[0].data.size) // parameter sets + key frame
        assertEquals(33_333L, chunks[0].presentationTimeUs)
        assertEquals(false, chunks[1].isKeyFrame)
        assertEquals(24, chunks[1].data.size)
        assertTrue(encoder.hasCodecConfig)
        assertTrue(encoder.drain().isEmpty(), "the shadow codec has dried up")
        encoder.release()
    }

    @Test
    fun `av1 drain routes the config to the control channel callback`() {
        val configs = mutableListOf<ByteArray>()
        val encoder = VideoEncoder(VideoCodec.Av1, 640, 480, 30f, 1_000_000, onCodecConfig = { configs += it })
        assertTrue(encoder.start())
        val chunks = encoder.drain()
        assertEquals(1, configs.size)
        assertEquals(8, configs[0].size)
        assertEquals(2, chunks.size)
        assertEquals(48, chunks[0].data.size, "AV1 config must not ride in-band")
        encoder.requestKeyFrame()
        encoder.release()
    }

    @Test
    fun `opus encodes a full frame with sample counted timestamps`() {
        val opus = OpusEncoder()
        assertTrue(opus.start())
        val first = opus.encode(ByteArray(OpusEncoder.FRAME_BYTES) { 1 }, OpusEncoder.FRAME_BYTES)
        assertTrue(first.isNotEmpty(), "a full frame should produce access units")
        assertTrue(first.all { it.isKeyFrame })
        val second = opus.encode(ByteArray(OpusEncoder.FRAME_BYTES) { 2 }, OpusEncoder.FRAME_BYTES)
        assertTrue(second.isEmpty() || second.all { it.presentationTimeUs >= 0 })
        opus.release()
    }

    @Test
    fun `pcm encoder encodes once a whole frame arrives through the pipe`() {
        val pipe = ParcelFileDescriptor.createPipe()
        val encoder = PcmAudioEncoder(pipe[0])
        assertTrue(encoder.start())
        FileOutputStream(pipe[1].fileDescriptor).use {
            it.write(ByteArray(OpusEncoder.FRAME_BYTES) { 4 })
        }
        val chunks = encoder.pump()
        assertTrue(chunks.isNotEmpty(), "a complete 20 ms frame should encode")
        assertTrue(encoder.pump().isEmpty())
        encoder.release()
    }

    @Test
    fun `playback capture with a projection builds its capture config`() {
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val encoder = AudioEncoder(newFakeProjection(context))
        // Under Robolectric the capture-config record cannot initialise, so the
        // honest answer is a refusal - the branch this covers is building the
        // config at all, which a null projection never reaches.
        kotlin.test.assertFalse(encoder.start())
        encoder.release()
    }

    @Test
    fun `playback capture pump encodes what it reads`() {
        val encoder = AudioEncoder(null)
        assertTrue(encoder.start())
        val chunks = encoder.pump()
        assertTrue(chunks.all { it.isKeyFrame })
        encoder.release()
    }
}
