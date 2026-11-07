package com.vayunmathur.cast.tv.platform

import com.vayunmathur.cast.protocol.Crypto
import com.vayunmathur.cast.protocol.DecodableFrame
import com.vayunmathur.cast.protocol.EncryptedFrame
import com.vayunmathur.cast.protocol.FrameId
import com.vayunmathur.cast.protocol.Negotiation
import com.vayunmathur.cast.protocol.RtpPacketizer
import com.vayunmathur.cast.protocol.SessionKeys
import com.vayunmathur.cast.protocol.StreamConfig
import com.vayunmathur.cast.protocol.StreamConstants
import com.vayunmathur.cast.protocol.StreamKind
import com.vayunmathur.cast.protocol.StreamReady
import com.vayunmathur.cast.protocol.VideoCodec
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaPumpTest {
    @Test fun pumpDeliversVideoAndAudioFramesAndFeedback() {
        val keys = SessionKeys.of(ByteArray(32) { 3 })
        val config = StreamConfig(width = 320, height = 240, frameRate = 30f, bitRate = 100_000, audio = true, video = true, audioSsrc = 1111, videoSsrc = 2222, videoCodec = VideoCodec.Hevc)
        val ready = StreamReady(udpPort = 0, audioSsrc = 3333, videoSsrc = 4444)
        val negotiation = Negotiation.of(config, ready, keys)
        val receiverSocket = DatagramSocket(0)
        val senderSocket = DatagramSocket()
        val videoFrames = mutableListOf<DecodableFrame>()
        val audioFrames = mutableListOf<DecodableFrame>()
        val receiver = MediaReceiver(receiverSocket, negotiation, { videoFrames += it }, { audioFrames += it }, { true })
        val loopback = InetAddress.getByName("127.0.0.1")
        fun send(bytes: ByteArray) = senderSocket.send(DatagramPacket(bytes, bytes.size, loopback, receiver.port))

        // Video key frame, single packet.
        val videoCrypto = Crypto(keys.video.key, keys.video.ivMask)
        val videoPlain = ByteArray(40) { it.toByte() }
        val videoFrame = EncryptedFrame(FrameId(0), FrameId(0), 0, true, videoCrypto.crypt(FrameId(0), videoPlain))
        for (packet in RtpPacketizer(StreamConstants.VIDEO_PAYLOAD_TYPE, config.videoSsrc).packetize(videoFrame)) {
            send(packet); assertTrue(receiver.pump())
        }
        assertEquals(1, videoFrames.size); assertTrue(videoFrames[0].payload.contentEquals(videoPlain))

        // Audio frame.
        val audioCrypto = Crypto(keys.audio.key, keys.audio.ivMask)
        val audioPlain = ByteArray(20) { (it + 50).toByte() }
        val audioFrame = EncryptedFrame(FrameId(0), FrameId(0), 0, true, audioCrypto.crypt(FrameId(0), audioPlain))
        for (packet in RtpPacketizer(StreamConstants.AUDIO_PAYLOAD_TYPE, config.audioSsrc).packetize(audioFrame)) {
            send(packet); assertTrue(receiver.pump())
        }
        assertEquals(1, audioFrames.size); assertTrue(audioFrames[0].payload.contentEquals(audioPlain))

        // Garbage datagram: handled as a possible sender report, delivers nothing.
        send(byteArrayOf(1, 2, 3)); assertTrue(receiver.pump())
        assertEquals(1, videoFrames.size)

        // Feedback goes back to the sender's address/port.
        senderSocket.soTimeout = 3000
        receiver.sendFeedback()
        val feedback = DatagramPacket(ByteArray(2048), 2048)
        senderSocket.receive(feedback); assertTrue(feedback.length > 0)
        receiver.sendFeedback(senderIdle = true)
        assertTrue(receiver.throughputSummary().contains("Video="))
        receiver.requestKeyFrame(StreamKind.Video); receiver.requestKeyFrame(StreamKind.Audio)
        receiver.close(); senderSocket.close()
    }

    @Test fun videoDroppedBeforeSessionWhenNotReady() {
        val keys = SessionKeys.of(ByteArray(32) { 4 })
        val config = StreamConfig(width = 320, height = 240, frameRate = 30f, bitRate = 100_000, audio = false, video = true, audioSsrc = 0, videoSsrc = 2222, videoCodec = VideoCodec.Hevc)
        val negotiation = Negotiation.of(config, StreamReady(0, 0, 4444), keys)
        val receiverSocket = DatagramSocket(0)
        val senderSocket = DatagramSocket()
        var delivered = 0
        val receiver = MediaReceiver(receiverSocket, negotiation, { delivered++ }, {}, { false })
        val crypto = Crypto(keys.video.key, keys.video.ivMask)
        val frame = EncryptedFrame(FrameId(0), FrameId(0), 0, true, crypto.crypt(FrameId(0), ByteArray(10) { 1 }))
        for (packet in RtpPacketizer(StreamConstants.VIDEO_PAYLOAD_TYPE, config.videoSsrc).packetize(frame)) {
            senderSocket.send(DatagramPacket(packet, packet.size, InetAddress.getByName("127.0.0.1"), receiver.port))
            receiver.pump()
        }
        assertEquals(0, delivered) // dropped pre-session, never reaches the (absent) decoder
        receiver.close(); senderSocket.close()
    }
}
