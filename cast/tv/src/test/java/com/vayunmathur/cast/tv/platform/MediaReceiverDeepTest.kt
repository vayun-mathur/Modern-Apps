package com.vayunmathur.cast.tv.platform

import com.vayunmathur.cast.protocol.Negotiation
import com.vayunmathur.cast.protocol.SessionKeys
import com.vayunmathur.cast.protocol.StreamConfig
import com.vayunmathur.cast.protocol.StreamReady
import com.vayunmathur.cast.protocol.VideoCodec
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertTrue

/** MediaReceiver feedback and stray-datagram handling. */
class MediaReceiverDeepTest {

    @Test fun feedbackBeforeAndAfterSenderKnown() {
        val keys = SessionKeys.of(ByteArray(32) { 11 })
        val config = StreamConfig(
            width = 320, height = 240, frameRate = 30f, bitRate = 100_000,
            audio = true, video = true, audioSsrc = 1111, videoSsrc = 2222,
            videoCodec = VideoCodec.Hevc,
        )
        val negotiation = Negotiation.of(config, StreamReady(0, 3333, 4444), keys)
        val socket = DatagramSocket(0)
        var video = 0
        var audio = 0
        val receiver = MediaReceiver(socket, negotiation, { video++ }, { audio++ }, { true })

        // No sender known yet: feedback is computed but addressed nowhere.
        receiver.sendFeedback()
        receiver.sendFeedback(senderIdle = true)

        // A sender report (RTCP, version 2, not an RTP stream packet).
        val sender = DatagramSocket()
        val report = ByteArray(28) { 0 }
        report[0] = 0x80.toByte(); report[1] = 200.toByte() // SR
        sender.send(DatagramPacket(report, report.size, InetAddress.getByName("127.0.0.1"), receiver.port))
        assertTrue(receiver.pump())

        // Garbage short datagram: parsed as nothing, pump still answers true.
        sender.send(DatagramPacket(byteArrayOf(9, 9), 2, InetAddress.getByName("127.0.0.1"), receiver.port))
        assertTrue(receiver.pump())
        assertTrue(video == 0 && audio == 0)

        // Now feedback has a destination: the sender receives bytes back.
        sender.soTimeout = 3_000
        receiver.sendFeedback()
        val feedback = DatagramPacket(ByteArray(2048), 2048)
        sender.receive(feedback)
        assertTrue(feedback.length > 0)
        assertTrue(receiver.throughputSummary().isNotBlank())
        receiver.close()
        sender.close()
    }
}
