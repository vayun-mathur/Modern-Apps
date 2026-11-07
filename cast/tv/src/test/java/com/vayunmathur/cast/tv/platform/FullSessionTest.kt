package com.vayunmathur.cast.tv.platform

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.cast.protocol.Bye
import com.vayunmathur.cast.protocol.ContentReady
import com.vayunmathur.cast.protocol.ContentSession
import com.vayunmathur.cast.protocol.Hello
import com.vayunmathur.cast.protocol.NowPlaying
import com.vayunmathur.cast.protocol.PairOk
import com.vayunmathur.cast.protocol.PairProof
import com.vayunmathur.cast.protocol.PairRequired
import com.vayunmathur.cast.protocol.Ping
import com.vayunmathur.cast.protocol.PlaybackState
import com.vayunmathur.cast.protocol.ProtocolBase64
import com.vayunmathur.cast.protocol.SealedSecret
import com.vayunmathur.cast.protocol.SecretSealing
import com.vayunmathur.cast.protocol.SessionKeys
import com.vayunmathur.cast.protocol.StreamConfig
import com.vayunmathur.cast.protocol.StreamReady
import com.vayunmathur.cast.protocol.Transcript
import com.vayunmathur.cast.protocol.TvIdentity
import com.vayunmathur.cast.protocol.VideoCodec
import com.vayunmathur.cast.protocol.VideoCodecConfig
import java.net.Socket
import kotlin.io.encoding.Base64
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FullSessionTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun awaitPhase(description: String, timeoutMs: Long = 30_000, matches: (ReceiverPhase) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (matches(ReceiverController.state.value.phase)) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for $description; phase=${ReceiverController.state.value.phase}")
    }

    private class Phone(val channel: ControlChannel, val keys: SessionKeys, val transcript: Transcript)

    private fun connect(senderId: String, paired: Boolean): Phone {
        val port = ReceiverController.serverSocket!!.localPort
        val channel = ControlChannel(Socket("127.0.0.1", port))
        val transcript = Transcript()
        transcript.add(channel.send(Hello(senderName = "Pixel", senderId = senderId, paired = paired)))
        val identityReceived = channel.receive()!!
        val identity = identityReceived.message as TvIdentity
        transcript.add(identityReceived.body)
        assertTrue(identity.receiverId.isNotBlank())
        val secret = ByteArray(32) { 9 }
        val bundle = ProtocolBase64.decode(identity.publicBundle)!!
        transcript.add(channel.send(SealedSecret(ProtocolBase64.encode(SecretSealing.seal(bundle, secret)))))
        val keys = SessionKeys.of(secret)
        channel.codec.useSessionKey(keys.control)
        return Phone(channel, keys, transcript)
    }

    private fun pairByCode(phone: Phone): ByteArray {
        val required = phone.channel.receive()?.message as PairRequired
        assertEquals(true, required.code)
        awaitPhase("pairing on screen") { it is ReceiverPhase.Pairing }
        val code = (ReceiverController.state.value.phase as ReceiverPhase.Pairing).code
        // One wrong attempt first: attempts decrement and the session stays open.
        phone.channel.send(PairProof(ProtocolBase64.encode(byteArrayOf(0))))
        val failed = phone.channel.receive()?.message as com.vayunmathur.cast.protocol.PairFailed
        assertEquals(2, failed.attemptsLeft)
        phone.channel.send(PairProof(ProtocolBase64.encode(phone.keys.pairProof(code, phone.transcript.value()))))
        val ok = phone.channel.receive()?.message as PairOk
        return ProtocolBase64.decode(ok.deviceKey!!)!!
    }

    @Test fun pairedMirrorSessionAndRememberedReconnect() {
        ReceiverController.start(ctx)
        awaitPhase("advertising") { it is ReceiverPhase.Advertising }
        val phone = connect("full-phone-1", paired = false)
        val deviceKey = pairByCode(phone)
        awaitPhase("connected") { it is ReceiverPhase.Connected }
        // Start mirroring.
        phone.channel.send(StreamConfig(width = 1920, height = 1080, frameRate = 30f, bitRate = 1_000_000, audio = true, video = true, audioSsrc = 111, videoSsrc = 222, videoCodec = VideoCodec.Hevc, appLabel = "YouPipe"))
        assertTrue(phone.channel.receive()?.message is StreamReady)
        awaitPhase("mirroring") { it is ReceiverPhase.Mirroring }
        assertEquals("YouPipe", (ReceiverController.state.value.phase as ReceiverPhase.Mirroring).sourceName)
        phone.channel.send(VideoCodecConfig(csd = Base64.encode(byteArrayOf(0x0a, 1, 2))))
        phone.channel.send(PlaybackState(10_000, 60_000, playing = true, buffering = false, volume = 0.5f))
        phone.channel.send(NowPlaying(title = "Track"))
        phone.channel.send(Ping)
        assertTrue(phone.channel.receive()?.message is Ping) // echoed
        // A renegotiated config re-arms streaming.
        phone.channel.send(StreamConfig(width = 1280, height = 720, frameRate = 30f, bitRate = 500_000, audio = false, video = true, audioSsrc = 111, videoSsrc = 222, videoCodec = VideoCodec.Hevc))
        assertTrue(phone.channel.receive()?.message is StreamReady)
        phone.channel.send(Bye())
        awaitPhase("back to advertising") { it is ReceiverPhase.Advertising }
        phone.channel.close()

        // Reconnect with the remembered device key: no code needed.
        val phone2 = connect("full-phone-1", paired = true)
        val required = phone2.channel.receive()?.message as PairRequired
        assertEquals(false, required.code)
        phone2.channel.send(PairProof(ProtocolBase64.encode(phone2.keys.deviceProof(deviceKey, phone2.transcript.value()))))
        assertTrue(phone2.channel.receive()?.message is PairOk)
        awaitPhase("connected again") { it is ReceiverPhase.Connected }
        phone2.channel.send(Ping)
        assertTrue(phone2.channel.receive()?.message is Ping)
        phone2.channel.send(Bye())
        awaitPhase("advertising again") { it is ReceiverPhase.Advertising }
        phone2.channel.close()
        ReceiverController.stop()
    }

    @Test fun wrongVersionHelloIsRefused() {
        ReceiverController.start(ctx)
        awaitPhase("advertising") { it is ReceiverPhase.Advertising }
        val phone = ControlChannel(Socket("127.0.0.1", ReceiverController.serverSocket!!.localPort))
        phone.send(Hello(version = 999, senderName = "Old", senderId = "old-1"))
        awaitPhase("failed phase") { it is ReceiverPhase.Failed }
        assertEquals(ReceiverFailure.Handshake, (ReceiverController.state.value.phase as ReceiverPhase.Failed).reason)
        phone.close(); ReceiverController.stop()
    }

    @Test fun sealedToWrongIdentityFailsHandshake() {
        ReceiverController.start(ctx)
        awaitPhase("advertising") { it is ReceiverPhase.Advertising }
        val phone = ControlChannel(Socket("127.0.0.1", ReceiverController.serverSocket!!.localPort))
        phone.send(Hello(senderName = "Pixel", senderId = "bad-seal-1"))
        assertTrue(phone.receive()?.message is TvIdentity)
        phone.send(SealedSecret(ProtocolBase64.encode(byteArrayOf(1, 2, 3, 4))))
        awaitPhase("failed phase") { it is ReceiverPhase.Failed }
        phone.close(); ReceiverController.stop()
    }

    @Test fun unpairedHelloThatVanishesEndsSession() {
        ReceiverController.start(ctx)
        awaitPhase("advertising") { it is ReceiverPhase.Advertising }
        val phone = ControlChannel(Socket("127.0.0.1", ReceiverController.serverSocket!!.localPort))
        phone.close() // peer vanishes before greeting
        Thread.sleep(300)
        ReceiverController.stop()
        assertTrue(ReceiverController.state.value.phase is ReceiverPhase.Starting)
    }

    @Test fun audioOnlyContentRefusedWithoutOpusDecoder() {
        if (AudioPlayer.limits().isNotEmpty()) return // needs a TV with no Opus decoder
        ReceiverController.start(ctx)
        awaitPhase("advertising") { it is ReceiverPhase.Advertising }
        val phone = connect("content-phone-1", paired = false)
        pairByCode(phone)
        awaitPhase("connected") { it is ReceiverPhase.Connected }
        phone.channel.send(ContentSession(host = "127.0.0.1", port = 9, certificateFingerprint = Base64.encode(ByteArray(32)), token = "tok", video = false, appLabel = "Music"))
        val ready = phone.channel.receive()?.message as ContentReady
        assertEquals(false, ready.accepted)
        awaitPhase("failed phase") { it is ReceiverPhase.Failed }
        assertEquals(ReceiverFailure.NoAudioDecoder, (ReceiverController.state.value.phase as ReceiverPhase.Failed).reason)
        phone.channel.close(); ReceiverController.stop()
    }

    @Test fun secondConnectionRefusedWhileSessionActiveAndStartIsIdempotent() {
        ReceiverController.start(ctx)
        awaitPhase("advertising") { it is ReceiverPhase.Advertising }
        ReceiverController.start(ctx) // already listening: no-op
        val phone = connect("busy-phone-1", paired = false)
        // PairRequired means the first session is mid-handshake and owns the screen.
        assertTrue(phone.channel.receive()?.message is PairRequired)
        // A second phone is refused (socket closed) rather than displacing the session.
        val second = Socket("127.0.0.1", ReceiverController.serverSocket!!.localPort)
        second.soTimeout = 5000
        val refused = runCatching { second.getInputStream().read() }.getOrDefault(-2)
        assertTrue(refused == -1 || refused == -2)
        second.close()
        phone.channel.close()
        awaitPhase("advertising after refusal") { it is ReceiverPhase.Advertising }
        ReceiverController.stop()
    }
}
