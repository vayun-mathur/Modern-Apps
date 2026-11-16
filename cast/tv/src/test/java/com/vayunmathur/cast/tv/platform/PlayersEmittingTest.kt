package com.vayunmathur.cast.tv.platform

import android.media.MediaCodec
import com.vayunmathur.cast.protocol.VideoCodec
import java.nio.ByteBuffer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowMediaCodec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A codec that emits: two output buffers, then "try again later". Per-instance
 * state (Robolectric keeps one shadow per codec), mirroring the sender's
 * ShadowMediaCodecSynthesizing.
 */
@Implements(MediaCodec::class)
class ShadowMediaCodecEmitting : ShadowMediaCodec() {

    private var emitted = 0

    @Suppress("UnusedParameter")
    @Implementation
    fun getInputBuffer(index: Int): ByteBuffer = ByteBuffer.allocate(INPUT_CAPACITY)

    @Suppress("UnusedParameter")
    @Implementation
    fun getOutputBuffer(index: Int): ByteBuffer =
        ByteBuffer.wrap(ByteArray(OUTPUT_CAPACITY) { (it * 5 + 2).toByte() })

    @Suppress("UnusedParameter")
    @Implementation
    fun dequeueOutputBuffer(info: MediaCodec.BufferInfo, timeoutUs: Long): Int {
        if (emitted >= EMITTED_BUFFERS) return MediaCodec.INFO_TRY_AGAIN_LATER
        emitted++
        info.offset = 0
        info.size = OUTPUT_CAPACITY
        info.presentationTimeUs = emitted * 20_000L
        info.flags = 0
        return emitted - 1
    }

    @Suppress("UnusedParameter")
    @Implementation
    override fun releaseOutputBuffer(index: Int, render: Boolean) = Unit

    private companion object {
        const val INPUT_CAPACITY = 64 * 1024
        const val OUTPUT_CAPACITY = 64
        const val EMITTED_BUFFERS = 2
    }
}

/** Play/render paths with a codec that actually produces output. */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowMediaCodecEmitting::class])
class PlayersEmittingTest {

    @AfterTest fun tearDown() {
        FakeDecoders.reset()
    }

    @Test fun audioPlayDrainsDecodedOutputToTrack() {
        FakeDecoders.install()
        val player = AudioPlayer()
        assertTrue(player.start())
        repeat(3) { player.play(ByteArray(60) { (it + 3).toByte() }, it * 20_000L) }
        assertFalse(player.failed)
        assertEquals(0, player.restarts)
        player.release()
    }

    @Test fun videoRenderDrainsDecodedFrames() {
        FakeDecoders.install()
        val surface = TestSurface()
        val decoder = VideoDecoder(surface.surface, VideoCodec.Hevc)
        assertTrue(decoder.start(320, 240))
        assertTrue(decoder.decode(ByteArray(96) { 5 }, 0L, isKeyFrame = true))
        decoder.render()
        decoder.render() // second pump: nothing left to drain
        decoder.release()
        surface.texture.release()
    }
}
