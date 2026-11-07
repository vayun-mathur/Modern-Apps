package com.vayunmathur.cast.platform.mirror

import com.vayunmathur.cast.network.CastUdpTransport
import com.vayunmathur.cast.protocol.FrameId
import com.vayunmathur.cast.protocol.NegotiatedStream
import com.vayunmathur.cast.protocol.ReceiverFeedback
import com.vayunmathur.cast.protocol.StreamKeys
import com.vayunmathur.cast.protocol.StreamKind
import com.vayunmathur.cast.protocol.StreamingSession
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamSenderTest {

    private fun stream(kind: StreamKind = StreamKind.Video) = NegotiatedStream(
        kind = kind,
        senderSsrc = 12_345L,
        receiverSsrc = 67_890L,
        payloadType = 96,
        timebase = 90_000,
        keys = StreamKeys(ByteArray(16) { it.toByte() }, ByteArray(16) { (it + 1).toByte() }),
    )

    @Test
    fun `send packetizes to udp peer and advances stats and frame id`() {
        val peer = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        peer.soTimeout = 5_000
        try {
            val udp = CastUdpTransport("127.0.0.1", peer.localPort)
            assertTrue(udp.open())
            val sender = StreamSender(stream(), udp, StreamingSession())
            assertEquals(FrameId.First, sender.lastFrameId)
            sender.send(EncodedChunk(ByteArray(2_000) { 7 }, 1_000_000L, isKeyFrame = true))
            // At least the first datagram arrives, addressed from the transport.
            val buf = ByteArray(4_096)
            val packet = DatagramPacket(buf, buf.size)
            peer.receive(packet)
            assertTrue(packet.length > 12)
            assertTrue(sender.stats.packets >= 1)
            assertTrue(sender.stats.octets >= packet.length)
            assertEquals(0L, sender.stats.lastRtpTimestamp) // first frame is the origin
            val firstId = sender.lastFrameId

            sender.send(EncodedChunk(ByteArray(100) { 3 }, 1_033_333L, isKeyFrame = false))
            assertTrue(sender.lastFrameId.value > firstId.value)
            assertTrue(sender.stats.lastRtpTimestamp > 0)

            // Feedback with no NACKs needs no key frame and resends nothing.
            val recovery = sender.onFeedback(
                ReceiverFeedback(checkpoint = FrameId.First, playoutDelayMs = 150, nacks = emptyList(), ackedFrames = emptyList()),
            )
            sender.retransmit(recovery.retransmissions)
            udp.close()
        } finally {
            peer.close()
        }
    }

    @Test
    fun `nack feedback retransmits and unknown frames ask for a key frame`() {
        val peer = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        peer.soTimeout = 5_000
        try {
            val udp = CastUdpTransport("127.0.0.1", peer.localPort)
            kotlin.test.assertTrue(udp.open())
            val sender = StreamSender(stream(), udp, StreamingSession())
            sender.send(EncodedChunk(ByteArray(3_000) { 9 }, 500_000L, isKeyFrame = true))
            val firstId = sender.lastFrameId
            // Drain what the first send produced.
            peer.soTimeout = 300
            var drained = 0
            try {
                val buf = ByteArray(4_096)
                while (true) {
                    peer.receive(DatagramPacket(buf, buf.size))
                    drained++
                }
            } catch (_: java.net.SocketTimeoutException) {
                // burst over
            }
            kotlin.test.assertTrue(drained >= 2, "a 3KB frame is several packets, got $drained")

            val recovery = sender.onFeedback(
                ReceiverFeedback(
                    checkpoint = com.vayunmathur.cast.protocol.FrameId.Leader,
                    playoutDelayMs = 150,
                    nacks = listOf(
                        com.vayunmathur.cast.protocol.PacketNack.wholeFrame(firstId),
                        com.vayunmathur.cast.protocol.PacketNack(firstId, 0),
                    ),
                    ackedFrames = emptyList(),
                ),
            )
            kotlin.test.assertEquals(1, recovery.retransmissions.size)
            kotlin.test.assertEquals(null, recovery.retransmissions[0].packetIds)
            sender.retransmit(recovery.retransmissions)
            peer.soTimeout = 5_000
            val again = DatagramPacket(ByteArray(4_096), 4_096)
            peer.receive(again) // the retransmission arrives
            kotlin.test.assertTrue(again.length > 12)

            // A NACK for a frame we no longer hold asks for a key frame instead.
            val lost = sender.onFeedback(
                ReceiverFeedback(
                    checkpoint = firstId,
                    playoutDelayMs = 150,
                    nacks = listOf(
                        com.vayunmathur.cast.protocol.PacketNack.wholeFrame(
                            com.vayunmathur.cast.protocol.FrameId(firstId.value + 50),
                        ),
                    ),
                    ackedFrames = emptyList(),
                ),
            )
            kotlin.test.assertTrue(lost.needsKeyFrame)
            kotlin.test.assertTrue(lost.retransmissions.isEmpty())
            udp.close()
        } finally {
            peer.close()
        }
    }

    @Test
    fun `send without open transport still records frame locally`() {
        val udp = CastUdpTransport("127.0.0.1", 9) // never opened: sends drop
        val sender = StreamSender(stream(StreamKind.Audio), udp, StreamingSession())
        sender.send(EncodedChunk(ByteArray(50) { 1 }, 0L, isKeyFrame = true))
        assertEquals(0L, sender.stats.packets)
        assertEquals(FrameId.First, sender.lastFrameId)
    }
}
