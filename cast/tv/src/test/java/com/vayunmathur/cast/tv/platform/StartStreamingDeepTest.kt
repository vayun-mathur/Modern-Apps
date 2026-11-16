package com.vayunmathur.cast.tv.platform

import com.vayunmathur.cast.protocol.SessionKeys
import com.vayunmathur.cast.protocol.StreamConfig
import com.vayunmathur.cast.protocol.StreamReady
import com.vayunmathur.cast.protocol.VideoCodec
import java.net.ServerSocket
import java.net.Socket
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * startStreaming end to end and its refusal: a live channel gets STREAM_READY
 * and the Mirroring phase; a dead channel refuses the session by name.
 */
@RunWith(RobolectricTestRunner::class)
class StartStreamingDeepTest {

    @AfterTest fun tearDown() {
        ReceiverController.endMedia()
        ReceiverController.detachSurface()
        ReceiverController.forgetPlayback()
        FakeDecoders.reset()
    }

    private fun channelPair(): Pair<ControlChannel, ControlChannel> {
        val server = ServerSocket(0)
        var accepted: Socket? = null
        val thread = Thread { accepted = server.accept() }
        thread.start()
        val client = Socket("127.0.0.1", server.localPort)
        thread.join(5_000)
        server.close()
        return ControlChannel(accepted!!) to ControlChannel(client)
    }

    private val config = StreamConfig(
        width = 320, height = 240, frameRate = 30f, bitRate = 1_000_000,
        audio = false, video = true, audioSsrc = 0, videoSsrc = 2222,
        videoCodec = VideoCodec.Hevc,
    )

    @Test fun startStreamingSendsReadyAndMirrors() {
        FakeDecoders.install()
        val (tv, phone) = channelPair()
        ReceiverController.startStreaming(tv, SessionKeys.of(ByteArray(32) { 8 }), config, "Pixel")
        val ready = phone.receive()?.message
        assertTrue(ready is StreamReady, "expected STREAM_READY, got $ready")
        assertTrue((ready as StreamReady).udpPort > 0)
        val phase = ReceiverController.state.value.phase
        assertTrue(phase is ReceiverPhase.Mirroring, phase.toString())
        assertEquals(320, (phase as ReceiverPhase.Mirroring).width)
        // The pump is running (no surface yet, so no decoder); ending media joins it.
        ReceiverController.endMedia()
        phone.close()
    }

    @Test fun startStreamingRefusesOnDeadChannel() {
        FakeDecoders.install()
        val (tv, phone) = channelPair()
        phone.close()
        tv.close()
        ReceiverController.startStreaming(tv, SessionKeys.of(ByteArray(32) { 9 }), config, "Pixel")
        // The ready could not be sent, so the session is refused on screen.
        assertEquals(
            ReceiverPhase.Failed(ReceiverFailure.StreamEnded),
            ReceiverController.state.value.phase,
        )
    }
}
