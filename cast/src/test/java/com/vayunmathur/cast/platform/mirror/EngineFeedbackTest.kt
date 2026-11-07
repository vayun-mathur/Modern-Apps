package com.vayunmathur.cast.platform.mirror

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.cast.protocol.NegotiatedStream
import com.vayunmathur.cast.protocol.Negotiation
import com.vayunmathur.cast.protocol.ReceiverSession
import com.vayunmathur.cast.protocol.StreamKeys
import com.vayunmathur.cast.protocol.StreamKind
import com.vayunmathur.cast.protocol.VideoCodec
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The running engine against a UDP peer: frames leave, sender reports follow,
 * and a PLI from the peer is answered with a fresh key-frame request (which for
 * AV1 means the codec config goes out on the control channel again).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [ShadowMediaCodecSynthesizing::class])
class EngineFeedbackTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun install() = FakeEncoders.install()
    @After fun reset() = FakeEncoders.reset()

    private fun stream(kind: StreamKind) = NegotiatedStream(
        kind, senderSsrc = if (kind == StreamKind.Video) 50001L else 100L,
        receiverSsrc = if (kind == StreamKind.Video) 90001L else 200L,
        payloadType = if (kind == StreamKind.Video) 96 else 127,
        timebase = if (kind == StreamKind.Video) 90_000 else 48_000,
        keys = StreamKeys(ByteArray(16) { 1 }, ByteArray(16) { 2 }),
    )

    @Test
    fun `frames reports and pli feedback round trip`() {
        val peer = DatagramSocket(45_444, InetAddress.getByName("127.0.0.1"))
        peer.soTimeout = 300
        val degraded = mutableListOf<MirrorDegradation>()
        val stopped = mutableListOf<MirrorStopReason>()
        val configs = mutableListOf<ByteArray>()
        val video = stream(StreamKind.Video)
        val engine = MirrorEngine(
            context = context,
            source = MirrorSource.Content(appLabel = "Music", wantAudio = true),
            receiverHost = "127.0.0.1",
            negotiation = Negotiation(45_444, listOf(video, stream(StreamKind.Audio))),
            geometry = CaptureGeometry(640, 480, 160, 1_000_000, 30f),
            videoCodec = VideoCodec.Av1,
            frameRate = 30f,
            onDegraded = { degraded += it },
            onStopped = { stopped += it },
            onCodecConfig = { configs += it },
        )
        try {
            assertTrue(engine.start(), "stopped=$stopped")
            assertEquals(emptyList(), degraded)
            // One PCM frame in, so the audio stream also transmits.
            assertNotNull(engine.audioWriteEnd).let { writeEnd ->
                FileOutputStream(writeEnd.fileDescriptor).use {
                    it.write(ByteArray(OpusEncoder.FRAME_BYTES) { 6 })
                }
            }

            // Collect the burst and learn the engine's ephemeral port.
            val received = mutableListOf<DatagramPacket>()
            var engineAddress: java.net.InetSocketAddress? = null
            val burstDeadline = System.currentTimeMillis() + 4_000
            while (System.currentTimeMillis() < burstDeadline && received.size < 3) {
                val packet = DatagramPacket(ByteArray(2_048), 2_048)
                try {
                    peer.receive(packet)
                    received += packet
                    engineAddress = java.net.InetSocketAddress(packet.address, packet.port)
                } catch (_: java.net.SocketTimeoutException) {
                    // keep waiting
                }
            }
            assertTrue(received.size >= 3, "expected video+audio RTP, got ${received.size}")
            assertTrue(configs.isNotEmpty(), "AV1 config should have been reported")
            val configsAfterStart = configs.size

            // A PLI from the peer: the encoder is asked for a key frame, and for
            // AV1 the codec config is re-sent with the ask. Repeated, because the
            // encoder coalesces asks inside 400 ms of (shadow) uptime.
            val feedback = ReceiverSession(video).feedback()
            // Unmatched noise is dropped, not fatal.
            peer.send(DatagramPacket(byteArrayOf(1, 2, 3, 4), 4, engineAddress))
            // Robolectric's uptime clock is frozen unless advanced, and the
            // encoder coalesces key-frame asks within 400 ms of uptime.
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(5))
            val pliDeadline = System.currentTimeMillis() + 8_000
            while (configs.size <= configsAfterStart && System.currentTimeMillis() < pliDeadline) {
                peer.send(DatagramPacket(feedback, feedback.size, engineAddress))
                org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(600))
                Thread.sleep(300)
            }
            assertTrue(configs.size > configsAfterStart, "PLI should re-send the AV1 codec config")

            // Sender reports keep leaving after the frame burst is over.
            peer.soTimeout = 2_500
            val report = DatagramPacket(ByteArray(2_048), 2_048)
            peer.receive(report) // throws on timeout: the assertion
            assertTrue(report.length > 0)
            assertTrue(stopped.isEmpty(), "nothing failed: $stopped")
        } finally {
            engine.stop()
            peer.close()
        }
    }
}
