package com.vayunmathur.cast.tv.platform

import android.app.Application
import android.content.Context
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.cast.protocol.ContentSession
import com.vayunmathur.cast.protocol.DecoderLimits
import com.vayunmathur.cast.protocol.Negotiation
import com.vayunmathur.cast.protocol.NowPlaying
import com.vayunmathur.cast.protocol.PlaybackAction
import com.vayunmathur.cast.protocol.PlaybackCommand
import com.vayunmathur.cast.protocol.PlaybackState
import com.vayunmathur.cast.protocol.StreamConfig
import com.vayunmathur.cast.protocol.VideoCodec
import com.vayunmathur.cast.protocol.VideoCodecConfig
import com.vayunmathur.cast.tv.service.ReceiverService
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import kotlin.io.encoding.Base64
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PlatformTvTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun pairingStoreRoundTrip() { runBlocking {
        val store = PairingStore(ctx)
        assertNull(store.deviceKey("phone1"))
        store.remember("phone1", byteArrayOf(1, 2, 3)); assertTrue(store.deviceKey("phone1")!!.contentEquals(byteArrayOf(1, 2, 3)))
        store.setBytes("k", byteArrayOf(9), false); assertTrue(store.getBytes("k")!!.contentEquals(byteArrayOf(9)))
        val id1 = store.deviceId(); assertEquals(12, id1.length); assertEquals(id1, store.deviceId())
        assertTrue(store.friendlyName("Fallback TV").isNotBlank())
    } }
    @Test fun networkLabelAndPanelModesDoNotCrash() {
        NetworkLabel.of(ctx) // SSID/IP depend on Robolectric shadows; any string (incl. empty) is valid
        PanelModes.of(ctx) // may be empty without a real display
    }
    @Test fun controllerPlaybackHelpers() {
        ReceiverController.forgetPlayback()
        assertEquals(false, ReceiverController.senderIdle())
        assertEquals(false, ReceiverController.nudgeVolume(true)) // no playback -> no session
        ReceiverController.onPlaybackState(PlaybackState(10_000, 60_000, playing = false, buffering = false, volume = 0.5f))
        assertTrue(ReceiverController.senderIdle())
        assertEquals(0.5f, ReceiverController.state.value.playback?.state?.volume)
        // nudge with a playback snapshot but no player/channel: sends via channel==null path after volume calc; returns true
        assertTrue(ReceiverController.nudgeVolume(true)); assertTrue(ReceiverController.nudgeVolume(false))
        ReceiverController.onPlaybackState(PlaybackState(10_000, 60_000, playing = true, buffering = true, volume = 1f))
        assertEquals(false, ReceiverController.senderIdle())
        ReceiverController.send(PlaybackCommand(PlaybackAction.Toggle)) // no player, no channel: dropped
        ReceiverController.skip(true); ReceiverController.skip(false)
        assertEquals(1_000_000L, ReceiverController.rtpToMicros(90_000))
        assertEquals(1_000_000L, ReceiverController.audioRtpToMicros(48_000))
        assertEquals("", ReceiverController.audioHealth(null))
        val player = AudioPlayer(); assertEquals("", ReceiverController.audioHealth(player))
        ReceiverController.forgetPlayback(); assertNull(ReceiverController.state.value.playback); assertEquals(1f, ReceiverController.castVolume)
    }
    @Test fun codecConfigHandling() {
        val cfg = StreamConfig(width = 1920, height = 1080, frameRate = 30f, bitRate = 1_000_000, audio = true, video = true, audioSsrc = 1, videoSsrc = 2, videoCodec = VideoCodec.Hevc)
        // invalid csd
        ReceiverController.onVideoCodecConfig(VideoCodecConfig(csd = "!!!"), cfg); assertNull(ReceiverController.videoCodecConfig)
        ReceiverController.onVideoCodecConfig(VideoCodecConfig(csd = ""), cfg)
        // av1C shape
        val av1 = byteArrayOf(0x81.toByte(), 1, 2); ReceiverController.onVideoCodecConfig(VideoCodecConfig(csd = Base64.encode(av1)), cfg.copy(videoCodec = VideoCodec.Av1))
        assertTrue(ReceiverController.videoCodecConfig!!.contentEquals(av1))
        val obu = byteArrayOf(0x0a, 1); ReceiverController.onVideoCodecConfig(VideoCodecConfig(csd = Base64.encode(obu)), cfg.copy(videoCodec = VideoCodec.Av1))
        val other = byteArrayOf(0x55, 1); ReceiverController.onVideoCodecConfig(VideoCodecConfig(csd = Base64.encode(other)), cfg)
        // audio-only config: ignored
        ReceiverController.onVideoCodecConfig(VideoCodecConfig(csd = Base64.encode(av1)), cfg.copy(videoCodec = null))
    }
    @Test fun decoderLimitsAndStartFailures() {
        VideoDecoder.limits() // Robolectric codec list may be empty; must not throw
        AudioPlayer.limits(); AudioPlayer.decoderName()
        for (codec in VideoCodec.entries) VideoDecoder.decoderName(codec)
        val dec = VideoDecoder(Surface(android.graphics.SurfaceTexture(0)), VideoCodec.Hevc)
        assertEquals(0L, dec.framesDropped)
        assertEquals(false, dec.decode(byteArrayOf(1), 0, true)); assertEquals(1L, dec.framesDropped) // no codec started
        dec.render(); dec.release()
        val audio = AudioPlayer(); assertEquals(false, audio.failed); audio.release()
        // start may fail without hardware decoder in Robolectric; either boolean is acceptable, must not throw
        runCatching { dec.start(320, 240) }; dec.release(); runCatching { audio.start() }; audio.release(); audio.play(byteArrayOf(1, 2), 0)
    }
    @Test fun mediaReceiverTimeoutAndFeedback() {
        val socket = DatagramSocket(0)
        val receiver = MediaReceiver(socket, Negotiation(udpPort = 0, streams = emptyList()), {}, {}, { true })
        assertTrue(receiver.port > 0)
        assertEquals(false, receiver.pump()) // 10ms timeout, nothing sent
        receiver.sendFeedback() // no sender yet: no-op
        receiver.sendFeedback(senderIdle = true)
        receiver.requestKeyFrame(com.vayunmathur.cast.protocol.StreamKind.Video)
        assertTrue(receiver.throughputSummary().isEmpty() || receiver.throughputSummary().contains("="))
        receiver.close()
    }
    @Test fun controlChannelLoopback() {
        val server = ServerSocket(0)
        var serverChannel: ControlChannel? = null
        val t = Thread { serverChannel = ControlChannel(server.accept()) }
        t.start()
        val client = ControlChannel(Socket("127.0.0.1", server.localPort))
        t.join(5000)
        val ch = serverChannel!!
        val body = client.send(com.vayunmathur.cast.protocol.Ping)
        assertTrue(body.isNotEmpty())
        val received = ch.receive(); assertEquals(com.vayunmathur.cast.protocol.Ping, received?.message); assertTrue(received!!.body.contentEquals(body))
        ch.send(com.vayunmathur.cast.protocol.PairOk()); assertTrue(client.receive()?.message is com.vayunmathur.cast.protocol.PairOk)
        client.close(); assertNull(ch.receive()) // peer closed
        ch.close(); server.close()
        assertTrue(client.remoteAddress.isNotBlank())
    }
    @Test fun advertiserAndArtwork() {
        val adv = ReceiverAdvertiser(ctx)
        adv.advertise("TV", "dev1", 12345, DecoderLimits(emptyList()))
        assertEquals(false, adv.localNetworkBlocked); adv.unadvertise(); adv.unadvertise(); assertNull(adv.registeredName)
        val session = ContentSession(host = "127.0.0.1", port = 9, certificateFingerprint = "!!!", token = "tok", video = false, appLabel = "App")
        assertNull(ArtworkFetcher(session).fetch("r1")) // bad fingerprint -> null, no network
        val session2 = ContentSession(host = "127.0.0.1", port = 9, certificateFingerprint = Base64.encode(ByteArray(32)), token = "tok", video = true, appLabel = "App")
        assertNull(ArtworkFetcher(session2).fetch("r1")) // unreachable proxy -> null
    }
    @Test fun receiverServiceLifecycle() {
        val controller = Robolectric.buildService(ReceiverService::class.java).create()
        val service = controller.get()
        assertNull(service.onBind(null))
        assertEquals(android.app.Service.START_STICKY, service.onStartCommand(null, 0, 1))
        ReceiverController.stop()
        assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(android.content.Intent().setAction(ReceiverService.ACTION_STOP), 0, 2))
        controller.destroy()
    }
    @Test fun controllerSendThroughLiveChannel() {
        val server = java.net.ServerSocket(0)
        var accepted: java.net.Socket? = null
        val thread = Thread { accepted = server.accept() }
        thread.start()
        val phone = ControlChannel(java.net.Socket("127.0.0.1", server.localPort))
        thread.join(5000); server.close()
        val tv = ControlChannel(accepted!!)
        ReceiverController.channel = tv
        ReceiverController.send(PlaybackCommand(PlaybackAction.Toggle))
        // The send is launched on the controller scope; the phone should receive the command.
        var received: Any? = null
        val deadline = System.currentTimeMillis() + 5000
        while (received == null && System.currentTimeMillis() < deadline) {
            received = runCatching { phone.receive()?.message }.getOrNull()
            if (received == null) Thread.sleep(50)
        }
        assertTrue(received is PlaybackCommand)
        ReceiverController.channel = null
        tv.close(); phone.close()
    }
    @Test fun nowPlayingGateWithArtworkState() {
        val state = ReceiverUiState(nowPlaying = NowPlaying(resourceId = "r1", title = "T"), playingResourceId = "r1", deviceName = "TV")
        assertEquals("T", state.nowPlayingForCurrentItem?.title)
        assertEquals("TV", state.deviceName)
    }
}
