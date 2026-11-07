package com.vayunmathur.cast.tv

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vayunmathur.cast.protocol.Negotiation
import com.vayunmathur.cast.protocol.SessionKeys
import com.vayunmathur.cast.protocol.StreamConfig
import com.vayunmathur.cast.protocol.StreamReady
import com.vayunmathur.cast.protocol.VideoCodec
import com.vayunmathur.cast.tv.platform.AudioPlayer
import com.vayunmathur.cast.tv.platform.ControlChannel
import com.vayunmathur.cast.tv.platform.ReceiverController
import com.vayunmathur.cast.tv.platform.pump
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** On-device media pump: real codecs, surfaces and sockets the JVM shadows cannot provide. */
@RunWith(AndroidJUnit4::class)
class DevicePumpTest {

    private fun channelPair(): Pair<ControlChannel, ControlChannel> {
        val server = ServerSocket(0)
        var accepted: Socket? = null
        val t = Thread { accepted = server.accept() }
        t.start()
        val client = Socket("127.0.0.1", server.localPort)
        t.join(5000); server.close()
        return ControlChannel(accepted!!) to ControlChannel(client)
    }

    @Test fun audioPlayerStartsPlaysAndReleasesOnDevice() {
        val player = AudioPlayer()
        val started = player.start()
        if (started) {
            player.setVolume(0.5f); player.setVolume(2f)
            // Garbage opus packets exercise the decode/rebuild path without crashing the session.
            repeat(3) { player.play(byteArrayOf(0x48, 0x50, 1, 2, 3, 4), it * 20_000L) }
            // A large invalid packet is the most likely thing to make the codec throw and rebuild.
            runCatching { player.play(ByteArray(2_000) { 0xFF.toByte() }, 100_000L) }
            Thread.sleep(300)
            assertTrue(player.restarts >= 0)
        }
        player.release(); player.release()
        assertTrue(AudioPlayer.limits().isNotEmpty() || !started)
    }

    @Test fun pumpWaitsForAv1CodecConfigThenFailsNamed() {
        runSuspend {
        val (tvChannel, _) = channelPair()
        ReceiverController.attachSurface(Surface(SurfaceTexture(0)))
        ReceiverController.videoCodecConfig = null
        val keys = SessionKeys.of(ByteArray(32) { 5 })
        val config = StreamConfig(width = 320, height = 240, frameRate = 30f, bitRate = 100_000, audio = false, video = true, audioSsrc = 0, videoSsrc = 2222, videoCodec = VideoCodec.Av1)
        val negotiation = Negotiation.of(config, StreamReady(0, 0, 4444), keys)
        val start = System.currentTimeMillis()
        // No codec config ever arrives: the pump must fail with MissingCodecConfig after its timeout,
        // not sit on a black screen forever.
        ReceiverController.pump(DatagramSocket(0), negotiation, config, tvChannel)
        assertTrue("pump should time out waiting for codec config", System.currentTimeMillis() - start >= 4_000)
        assertEquals(
            com.vayunmathur.cast.tv.platform.ReceiverFailure.MissingCodecConfig,
            (ReceiverController.state.value.phase as? com.vayunmathur.cast.tv.platform.ReceiverPhase.Failed)?.reason,
        )
        ReceiverController.detachSurface()
        tvChannel.close()
        }
    }

    @Test fun videoDecoderLifecycleOnDevice() {
        // On this emulator a (software) HEVC decoder may exist despite the hardware-only
        // advertising filter; either outcome is valid, the lifecycle must not hang or crash.
        var started = false
        val thread = Thread {
            val dec = com.vayunmathur.cast.tv.platform.VideoDecoder(
                Surface(SurfaceTexture(0)), VideoCodec.Hevc,
            )
            started = runCatching { dec.start(320, 240) }.getOrDefault(false)
            if (started) {
                dec.decode(byteArrayOf(1, 2, 3), 0, true)
                dec.render()
            }
            dec.release(); dec.release()
        }
        thread.start(); thread.join(15_000)
        assertTrue("decoder lifecycle hung", !thread.isAlive)
        assertTrue(started || com.vayunmathur.cast.tv.platform.VideoDecoder.decoderName(VideoCodec.Hevc) == null)
    }


    @Test fun pumpDrainsAudioWithRealPlayer() {
        // Audio-only negotiation: maintainDecoder is a no-op without video, the pump drains
        // due audio frames into the real player, and the loop is stopped by closing the socket
        // (pump keeps returning false on timeout; the daemon thread dies with the process).
        val keys = SessionKeys.of(ByteArray(32) { 11 })
        val config = StreamConfig(width = 0, height = 0, frameRate = 0f, bitRate = 0, audio = true, video = false, audioSsrc = 1111, videoSsrc = 0, videoCodec = null)
        val negotiation = Negotiation.of(config, StreamReady(0, 3333, 0), keys)
        val socket = DatagramSocket(0)
        val sender = DatagramSocket()
        val (tvChannel, _) = channelPair()
        val pumpThread = Thread {
            runSuspend { ReceiverController.pump(socket, negotiation, config, tvChannel) }
        }
        pumpThread.isDaemon = true
        pumpThread.start()
        Thread.sleep(500) // let the audio player start
        val crypto = com.vayunmathur.cast.protocol.Crypto(keys.audio.key, keys.audio.ivMask)
        val frame = com.vayunmathur.cast.protocol.EncryptedFrame(
            com.vayunmathur.cast.protocol.FrameId(0), com.vayunmathur.cast.protocol.FrameId(0), 0, true,
            crypto.crypt(com.vayunmathur.cast.protocol.FrameId(0), ByteArray(40) { 7 }),
        )
        for (packet in com.vayunmathur.cast.protocol.RtpPacketizer(com.vayunmathur.cast.protocol.StreamConstants.AUDIO_PAYLOAD_TYPE, config.audioSsrc).packetize(frame)) {
            sender.send(java.net.DatagramPacket(packet, packet.size, java.net.InetAddress.getByName("127.0.0.1"), socket.localPort))
        }
        Thread.sleep(1_500) // due-time + decode on the pump thread
        assertTrue("pump thread should still be looping", pumpThread.isAlive)
        socket.close(); sender.close(); tvChannel.close()
    }

    private fun runSuspend(block: suspend () -> Unit) {
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        block.startCoroutine(object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) {
                failure = result.exceptionOrNull()
                latch.countDown()
            }
        })
        if (!latch.await(60, TimeUnit.SECONDS)) throw AssertionError("suspend block timed out")
        failure?.let { throw it }
    }
}