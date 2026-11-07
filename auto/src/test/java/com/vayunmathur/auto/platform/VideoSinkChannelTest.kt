package com.vayunmathur.auto.platform

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.auto.protocol.GalConnection
import com.vayunmathur.auto.protocol.GalMessage
import com.vayunmathur.auto.protocol.GalService
import com.vayunmathur.auto.protocol.GalTransport
import com.vayunmathur.auto.protocol.gal.MediaAck
import com.vayunmathur.auto.protocol.gal.MediaSinkService
import com.vayunmathur.auto.protocol.gal.Service
import com.vayunmathur.auto.protocol.gal.VideoConfiguration
import com.vayunmathur.auto.protocol.gal.VideoFocusIndication
import com.vayunmathur.auto.protocol.gal.VideoFocusMode
import com.vayunmathur.auto.protocol.gal.VideoResolution
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import javax.net.ssl.SSLContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class VideoSinkChannelTest {
    private class FakeTransport : GalTransport {
        val written = ByteArrayOutputStream()
        override fun read(b: ByteArray, o: Int, l: Int): Int = -1
        override fun write(b: ByteArray, o: Int, l: Int) { written.write(b, o, l) }
        override fun flush() = Unit
        override fun close() = Unit
    }
    private fun connection(): GalConnection {
        val ctx = SSLContext.getInstance("TLS").apply { init(null, null, null) }
        return GalConnection(FakeTransport(), ctx, deviceModel = "Test").also { it.close() }
    }
    private fun service(): Service = Service.newBuilder()
        .setId(GalService.VIDEO_SINK.id)
        .setMediaSink(MediaSinkService.newBuilder().addVideoConfigs(
            VideoConfiguration.newBuilder().setCodecResolution(VideoResolution.VIDEO_1280x720).setFrameRate(30).setDensity(160).build()
        ).build()).build()

    private fun channel(events: MutableList<VideoEvent> = mutableListOf()) =
        VideoSinkChannel(ApplicationProvider.getApplicationContext(), service(), connection(), onEvent = { events += it })

    @Test fun `channel id and no display before streaming`() {
        val ch = channel()
        assertEquals(GalService.VIDEO_SINK.id, ch.channelId)
        assertNull(ch.displaySize())
        assertFalse(ch.injectTouch(ScaledTouch(action = 0, pointers = listOf(com.vayunmathur.auto.protocol.InputPointer(1, 2, 0)), actionIndex = 0)))
        assertFalse(ch.injectKey(4, true))
        assertFalse(ch.injectScroll(1))
        ch.pumpEncoder() // no encoder: no-op, no event
        ch.release()
    }

    @Test fun `wrong channel and unhandled types are ignored`() {
        val events = mutableListOf<VideoEvent>()
        val ch = channel(events)
        ch.onMessage(99, GalMessage.Media.ACK, MediaAck.newBuilder().setSessionId(0).build().toByteArray())
        ch.onMessage(ch.channelId, 0x1234, byteArrayOf(1))
        ch.onMessage(ch.channelId, GalMessage.Video.UPDATE_UI_CONFIG_REQUEST, byteArrayOf(1, 2))
        assertTrue(events.isEmpty())
    }

    @Test fun `ack for matching session is received and mismatched is flagged`() {
        val events = mutableListOf<VideoEvent>()
        val ch = channel(events)
        // sessionId starts at -1 before streaming
        ch.onMessage(ch.channelId, GalMessage.Media.ACK,
            MediaAck.newBuilder().setSessionId(-1).setAck(7).build().toByteArray())
        assertEquals(1, events.filterIsInstance<VideoEvent.AckReceived>().size)
        assertEquals(7L, events.filterIsInstance<VideoEvent.AckReceived>().single().ackSeq)
        ch.onMessage(ch.channelId, GalMessage.Media.ACK,
            MediaAck.newBuilder().setSessionId(42).build().toByteArray())
        val mismatch = events.filterIsInstance<VideoEvent.AckMismatch>().single()
        assertEquals(-1, mismatch.expected); assertEquals(42, mismatch.actual)
    }

    @Test fun `focus indication publishes focus changed`() {
        val events = mutableListOf<VideoEvent>()
        val ch = channel(events)
        ch.onMessage(ch.channelId, GalMessage.Video.FOCUS_INDICATION,
            VideoFocusIndication.newBuilder().setMode(VideoFocusMode.VIDEO_FOCUS_NATIVE).build().toByteArray())
        assertEquals(listOf("VIDEO_FOCUS_NATIVE"), events.filterIsInstance<VideoEvent.FocusChanged>().map { it.mode })
    }

    @Test fun `wiring with no display stores feeds without crashing`() {
        val events = mutableListOf<VideoEvent>()
        val ch = channel(events)
        ch.wiring.setNightDark(true)
        ch.wiring.setParkedBrowsingGate(true)
        ch.wiring.closeDrawerOnPark()
        ch.wiring.setNowPlaying(NowPlayingInfo(title = "S", artist = null, playing = true, positionMs = 0, durationMs = null))
        ch.wiring.setActiveCall(null)
        ch.wiring.setTransportCallbacks({}, {})
        ch.wiring.setMapDarkApplier {}
        ch.wiring.setPhoneStatusSource { null }
        // No display up: all pushes no-op, channel still usable
        assertNull(ch.displaySize())
    }
}
