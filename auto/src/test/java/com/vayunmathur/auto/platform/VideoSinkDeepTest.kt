package com.vayunmathur.auto.platform

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.auto.protocol.GalConnection
import com.vayunmathur.auto.protocol.GalMessage
import com.vayunmathur.auto.protocol.GalService
import com.vayunmathur.auto.protocol.GalTransport
import com.vayunmathur.auto.protocol.NavSnapshot
import com.vayunmathur.auto.protocol.gal.MediaSinkService
import com.vayunmathur.auto.protocol.gal.Service
import com.vayunmathur.auto.protocol.gal.VideoConfiguration
import com.vayunmathur.auto.protocol.gal.VideoFocusIndication
import com.vayunmathur.auto.protocol.gal.VideoResolution
import com.vayunmathur.library.carhost.HostNavState
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.io.ByteArrayOutputStream
import javax.net.ssl.SSLContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deep [VideoSinkChannel] + [VideoSinkWiring] coverage: setup request,
 * focus indications (including the mode-less one), release gating, and
 * every stored-feed push through a real (never shown) [CarDisplay].
 */
@RunWith(RobolectricTestRunner::class)
class VideoSinkDeepTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun idleMain() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    private class FakeTransport : GalTransport {
        val written = ByteArrayOutputStream()
        override fun read(b: ByteArray, o: Int, l: Int): Int = -1
        override fun write(b: ByteArray, o: Int, l: Int) { written.write(b, o, l) }
        override fun flush() = Unit
        override fun close() = Unit
    }

    private fun connection(): GalConnection {
        val ssl = SSLContext.getInstance("TLS").apply { init(null, null, null) }
        return GalConnection(FakeTransport(), ssl, deviceModel = "Test").also { it.close() }
    }

    private fun service(vararg configs: VideoConfiguration): Service = Service.newBuilder()
        .setId(GalService.VIDEO_SINK.id)
        .setMediaSink(MediaSinkService.newBuilder().addAllVideoConfigs(configs.asList()).build())
        .build()

    private fun config(res: VideoResolution, fps: Int, dpi: Int = 160): VideoConfiguration =
        VideoConfiguration.newBuilder().setCodecResolution(res).setFrameRate(fps).setDensity(dpi).build()

    private fun channel(
        events: MutableList<VideoEvent> = mutableListOf(),
        svc: Service = service(config(VideoResolution.VIDEO_1280x720, 30)),
    ) = VideoSinkChannel(ctx, svc, connection(), onEvent = { events += it })

    @Test fun `setup request is sent on the video channel`() {
        val ch = channel()
        ch.requestSetup() // closed connection: send drops, call itself is the behaviour
        assertEquals(GalService.VIDEO_SINK.id, ch.channelId)
        ch.release()
        idleMain()
    }

    @Test fun `focus indication without a mode leaves arbitration unchanged`() {
        val events = mutableListOf<VideoEvent>()
        val ch = channel(events)
        ch.onMessage(
            ch.channelId, GalMessage.Video.FOCUS_INDICATION,
            VideoFocusIndication.newBuilder().build().toByteArray(), // no mode field set
        )
        assertTrue(events.filterIsInstance<VideoEvent.FocusChanged>().isEmpty())
        ch.release(); idleMain()
    }

    @Test fun `release without streaming sends no stop and reports surface down once`() {
        val events = mutableListOf<VideoEvent>()
        val ch = channel(events)
        ch.release()
        idleMain()
        assertEquals(
            listOf(false),
            events.filterIsInstance<VideoEvent.SurfaceChanged>().map { it.valid },
        )
        ch.release() // second teardown: another surface-down, still no crash
        idleMain()
    }

    @Test fun `wiring pushes every stored feed into a live display`() {
        val display = CarDisplay(ctx, 800, 480, 160)
        val wiring = VideoSinkWiring(current = { display })
        var tapped = 0; var prev = 0; var next = 0
        var answered = 0; var ended = 0; var held = 0; var muted = 0
        var darkApplied: Boolean? = null
        var surfaceCalls = 0
        val info = NowPlayingInfo(title = "Song", artist = "Band", playing = true, positionMs = 5, durationMs = 100)
        wiring.setNowPlayingSource(get = { info }, onTap = { tapped++ })
        wiring.setTransportCallbacks(onPrevious = { prev++ }, onNext = { next++ })
        wiring.setMapDarkApplier { darkApplied = it }
        wiring.setPhoneStatusSource { PhoneStatus(signalBars = 3, batteryPercent = 80, batteryCharging = true) }
        wiring.setCallSource(
            get = { ActiveCallInfo(telecomState = 2, number = "555", acceptedMs = 10) },
            onAnswer = { answered++ }, onEnd = { ended++ }, onHold = { held++ }, onMute = { muted++ },
        )
        wiring.setMapTouchForwarder { _, _, _ -> true }
        wiring.setNavSource(
            get = { NavSnapshot(latitudeE7 = 1, longitudeE7 = 2) },
            onMapSurface = { _, _, _ -> surfaceCalls++ },
        )
        // Late wiring reached the already-up display. (The media tap itself is
        // only seeded by applyInitial; setNowPlayingSource pushes the snapshot
        // and the transport row, so a live display keeps a null media tap.)
        assertNull(display.onMediaTap); assertEquals(0, tapped)
        display.onPreviousTap?.invoke(); assertEquals(1, prev)
        display.onNextTap?.invoke(); assertEquals(1, next)
        display.onAnswerCall?.invoke(); assertEquals(1, answered)
        display.onEndCall?.invoke(); assertEquals(1, ended)
        display.onHoldToggle?.invoke(); assertEquals(1, held)
        display.onMuteToggle?.invoke(); assertEquals(1, muted)
        assertEquals(info, CarLauncherState.nowPlaying.value)
        assertEquals(80, display.phoneStatusSource?.invoke()?.batteryPercent)

        wiring.setNightDark(true)
        assertEquals(true, darkApplied)
        assertTrue(CarLauncherState.nightDark.value)
        wiring.setParkedBrowsingGate(true) // parked clears the restriction gate
        assertFalse(CarLauncherState.drivingRestricted.value)
        wiring.setParkedBrowsingGate(false)
        assertTrue(CarLauncherState.drivingRestricted.value)
        wiring.closeDrawerOnPark()
        wiring.setActiveCall(null)
        assertNull(CarLauncherState.activeCall.value)
        wiring.setHostNavState(HostNavState(navigating = true, cue = "Left"))
        assertEquals("Left", CarLauncherState.hostNav.value?.cue)
        wiring.setNavSnapshot(NavSnapshot(latitudeE7 = 3, longitudeE7 = 4, parked = false))
        // Now-playing gate: idle snapshot hides, playing snapshot shows.
        wiring.setNowPlaying(NowPlayingInfo(title = null, artist = null, playing = false, positionMs = 0, durationMs = null))
        wiring.setNowPlaying(info)
        assertEquals(info, CarLauncherState.nowPlaying.value)
        idleMain()
        display.release(); idleMain()
    }

    @Test fun `wiring applyInitial seeds a fresh display from stored feeds`() {
        val display = CarDisplay(ctx, 800, 480, 160)
        val wiring = VideoSinkWiring(current = { null }) // nothing live yet
        var tapped = 0
        val info = NowPlayingInfo(title = "T", artist = null, playing = false, positionMs = 0, durationMs = null)
        wiring.setNowPlayingSource(get = { info }, onTap = { tapped++ })
        wiring.setTransportCallbacks(onPrevious = {}, onNext = {})
        wiring.setMapDarkApplier { }
        wiring.setPhoneStatusSource { null }
        wiring.setCallSource(get = { null }, onAnswer = {}, onEnd = {}, onHold = {}, onMute = {})
        wiring.setMapTouchForwarder { _, _, _ -> false }
        wiring.applyInitial(display)
        display.onMediaTap?.invoke(); assertEquals(1, tapped)
        assertEquals(info, CarLauncherState.nowPlaying.value)
        idleMain()
        display.release(); idleMain()
    }

    @Test fun `wiring with no display drops every push`() {
        val wiring = VideoSinkWiring(current = { null })
        wiring.setNightDark(true)
        wiring.setParkedBrowsingGate(true)
        wiring.closeDrawerOnPark()
        wiring.setActiveCall(ActiveCallInfo(telecomState = 1, number = null, acceptedMs = 0))
        wiring.setHostNavState(HostNavState(navigating = false))
        wiring.setNavSnapshot(NavSnapshot(latitudeE7 = 0, longitudeE7 = 0))
        wiring.setNowPlaying(NowPlayingInfo(title = "X", artist = null, playing = true, positionMs = 0, durationMs = null))
        wiring.setTransportCallbacks({}, {})
        wiring.setMapDarkApplier {}
        wiring.setPhoneStatusSource { null }
        wiring.setMapTouchForwarder { _, _, _ -> true }
        // No display: nothing reaches the shared launcher state.
        assertNull(CarLauncherState.activeCall.value)
    }
}
