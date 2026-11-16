package com.vayunmathur.cast.tv.platform

import com.vayunmathur.cast.protocol.Crypto
import com.vayunmathur.cast.protocol.EncryptedFrame
import com.vayunmathur.cast.protocol.FrameId
import com.vayunmathur.cast.protocol.Negotiation
import com.vayunmathur.cast.protocol.RtpPacketizer
import com.vayunmathur.cast.protocol.SessionKeys
import com.vayunmathur.cast.protocol.StreamConfig
import com.vayunmathur.cast.protocol.StreamConstants
import com.vayunmathur.cast.protocol.StreamReady
import com.vayunmathur.cast.protocol.VideoCodec
import com.vayunmathur.cast.protocol.VideoCodecConfig
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The media pump against fake decoders: frames flow from UDP datagrams through
 * the playout queues into the shadow codecs, the decoder is rebuilt across a
 * surface swap, and an AV1 session without its codec config fails by name.
 */
@RunWith(RobolectricTestRunner::class)
class StreamingPumpDeepTest {

    @AfterTest fun tearDown() {
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

    private fun sendFrame(
        sender: DatagramSocket,
        port: Int,
        payloadType: Int,
        ssrc: Long,
        plain: ByteArray,
        key: ByteArray,
        ivMask: ByteArray,
    ) {
        val crypto = Crypto(key, ivMask)
        val frame = EncryptedFrame(FrameId(0), FrameId(0), 0L, true, crypto.crypt(FrameId(0), plain))
        for (packet in RtpPacketizer(payloadType, ssrc).packetize(frame)) {
            sender.send(DatagramPacket(packet, packet.size, InetAddress.getByName("127.0.0.1"), port))
        }
    }

    @Test fun pumpDecodesVideoAndAudioAndSurvivesSurfaceSwap() { runBlocking {
        FakeDecoders.install()
        val keys = SessionKeys.of(ByteArray(32) { 5 })
        val config = StreamConfig(
            width = 320, height = 240, frameRate = 30f, bitRate = 1_000_000,
            audio = true, video = true, audioSsrc = 1111, videoSsrc = 2222,
            videoCodec = VideoCodec.Hevc,
        )
        val socket = DatagramSocket(0)
        val ready = StreamReady(udpPort = socket.localPort, audioSsrc = 3333, videoSsrc = 4444)
        val negotiation = Negotiation.of(config, ready, keys)
        val (tvChannel, phoneChannel) = channelPair()
        val surface = TestSurface()
        ReceiverController.attachSurface(surface.surface)

        val job = async(Dispatchers.Default) { ReceiverController.pump(socket, negotiation, config, tvChannel) }
        delay(300)
        val sender = DatagramSocket()
        sendFrame(
            sender, socket.localPort, StreamConstants.VIDEO_PAYLOAD_TYPE, config.videoSsrc,
            ByteArray(64) { 3 }, keys.video.key, keys.video.ivMask,
        )
        sendFrame(
            sender, socket.localPort, StreamConstants.AUDIO_PAYLOAD_TYPE, config.audioSsrc,
            ByteArray(40) { 4 }, keys.audio.key, keys.audio.ivMask,
        )
        // Playout holds frames 150 ms; give the loop several passes over both.
        delay(900)

        // A panel-mode swap: surface goes away and a different one arrives.
        ReceiverController.detachSurface()
        delay(200)
        val replacement = TestSurface()
        ReceiverController.attachSurface(replacement.surface)
        delay(300)
        sendFrame(
            sender, socket.localPort, StreamConstants.VIDEO_PAYLOAD_TYPE, config.videoSsrc,
            ByteArray(64) { 6 }, keys.video.key, keys.video.ivMask,
        )
        delay(700)

        assertTrue(job.isActive, "pump should still be running with a decoder up")
        job.cancelAndJoin()
        sender.close()
        phoneChannel.close()
        surface.texture.release()
        replacement.texture.release()
        Unit
    } }

    @Test fun av1WithoutCodecConfigFailsByNameAndWithConfigStarts() { runBlocking {
        FakeDecoders.install()
        val keys = SessionKeys.of(ByteArray(32) { 6 })
        val config = StreamConfig(
            width = 320, height = 240, frameRate = 30f, bitRate = 1_000_000,
            audio = false, video = true, audioSsrc = 0, videoSsrc = 2222,
            videoCodec = VideoCodec.Av1,
        )
        // First: no config ever arrives, so the pump waits, then refuses by name.
        run {
            val socket = DatagramSocket(0)
            val ready = StreamReady(udpPort = socket.localPort, audioSsrc = 0, videoSsrc = 4444)
            val negotiation = Negotiation.of(config, ready, keys)
            val (tvChannel, phoneChannel) = channelPair()
            val surface = TestSurface()
            ReceiverController.attachSurface(surface.surface)
            val job = async(Dispatchers.Default) { ReceiverController.pump(socket, negotiation, config, tvChannel) }
            // The codec-config wait is five seconds; the pump must give up after it.
            val deadline = System.currentTimeMillis() + 12_000
            while (job.isActive && System.currentTimeMillis() < deadline) delay(200)
            assertTrue(!job.isActive, "pump should end when the codec config never arrives")
            job.join()
            assertEquals(
                ReceiverPhase.Failed(ReceiverFailure.MissingCodecConfig),
                ReceiverController.state.value.phase,
            )
            phoneChannel.close()
            surface.texture.release()
        }
        // Now hand the config over first: the decoder starts and the pump runs.
        ReceiverController.onVideoCodecConfig(
            VideoCodecConfig(csd = com.vayunmathur.cast.protocol.ProtocolBase64.encode(byteArrayOf(0x0a, 0x0e))),
            config,
        )
        val socket = DatagramSocket(0)
        val ready = StreamReady(udpPort = socket.localPort, audioSsrc = 0, videoSsrc = 4444)
        val negotiation = Negotiation.of(config, ready, keys)
        val (tvChannel, phoneChannel) = channelPair()
        val surface = TestSurface()
        ReceiverController.attachSurface(surface.surface)
        val job = async(Dispatchers.Default) { ReceiverController.pump(socket, negotiation, config, tvChannel) }
        delay(600)
        assertTrue(job.isActive, "pump should run once the AV1 decoder is up")
        job.cancelAndJoin()
        phoneChannel.close()
        surface.texture.release()
        Unit
    } }
}
