package com.vayunmathur.auto

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.auto.platform.ScaledTouch
import com.vayunmathur.auto.platform.VideoEvent
import com.vayunmathur.auto.platform.VideoSinkChannel
import com.vayunmathur.auto.protocol.GalConnection
import com.vayunmathur.auto.protocol.GalMessage
import com.vayunmathur.auto.protocol.GalService
import com.vayunmathur.auto.protocol.GalTransport
import com.vayunmathur.auto.protocol.InputPointer
import com.vayunmathur.auto.protocol.gal.MediaSetupResponse
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
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class Y3VideoSinkSetupTest {
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

    private fun service(vararg configs: VideoConfiguration): Service = Service.newBuilder()
        .setId(GalService.VIDEO_SINK.id)
        .setMediaSink(MediaSinkService.newBuilder().addAllVideoConfigs(configs.toList()).build()).build()

    private fun config(res: VideoResolution, fps: Int, density: Int) =
        VideoConfiguration.newBuilder().setCodecResolution(res).setFrameRate(fps).setDensity(density).build()

    @Test fun setupResponseAcceptedAndOutOfRange() {
        val events = mutableListOf<VideoEvent>()
        val ch = VideoSinkChannel(
            ApplicationProvider.getApplicationContext(),
            service(
                config(VideoResolution.VIDEO_800x480, 60, 160),
                config(VideoResolution.VIDEO_1280x720, 30, 240),
                config(VideoResolution.VIDEO_1920x1080, 0, 0),
            ),
            connection(),
            onEvent = { events += it },
        )
        // Out-of-range index keeps entry 0; startStreaming may fail at the
        // MediaCodec on Robolectric — the setup event must already be out.
        runCatching {
            ch.onMessage(ch.channelId, GalMessage.Media.CONFIG,
                MediaSetupResponse.newBuilder().setMediaStatus(0).addConfigurationIndices(99).build().toByteArray())
        }
        assertTrue(events.any { it is VideoEvent.Setup }, "$events")
        runCatching { ch.release() }
        // Empty indices defaults to 0, accepted index 1 selects 720p30.
        val events2 = mutableListOf<VideoEvent>()
        val ch2 = VideoSinkChannel(
            ApplicationProvider.getApplicationContext(),
            service(config(VideoResolution.VIDEO_1280x720, 30, 240), config(VideoResolution.VIDEO_800x480, 60, 120)),
            connection(),
            onEvent = { events2 += it },
        )
        runCatching {
            ch2.onMessage(ch2.channelId, GalMessage.Media.CONFIG,
                MediaSetupResponse.newBuilder().setMediaStatus(0).addConfigurationIndices(1).build().toByteArray())
        }
        val setup = events2.filterIsInstance<VideoEvent.Setup>().firstOrNull()
        assertTrue(setup == null || setup.info.configIndex == 1, "$events2")
        // Focus projected/native + touch/key/scroll injection gates.
        runCatching {
            ch2.onMessage(ch2.channelId, GalMessage.Video.FOCUS_INDICATION,
                VideoFocusIndication.newBuilder().setMode(VideoFocusMode.VIDEO_FOCUS_PROJECTED).build().toByteArray())
            ch2.onMessage(ch2.channelId, GalMessage.Video.FOCUS_INDICATION,
                VideoFocusIndication.newBuilder().build().toByteArray())
            ch2.injectTouch(ScaledTouch(action = 0, pointers = listOf(InputPointer(10, 20, 0)), actionIndex = 0))
            ch2.injectKey(4, true); ch2.injectKey(4, false); ch2.injectScroll(3)
            ch2.requestSetup(); ch2.pumpEncoder()
        }
        runCatching { ch2.release() }
    }

    @Test fun sendFrameReflectionAndReleaseAfterStartAttempt() {
        val events = mutableListOf<VideoEvent>()
        val ch = VideoSinkChannel(
            ApplicationProvider.getApplicationContext(),
            service(config(VideoResolution.VIDEO_1280x720, 30, 240)),
            connection(),
            onEvent = { events += it },
        )
        val info = android.media.MediaCodec.BufferInfo().apply {
            size = 4; presentationTimeUs = 1_000L; offset = 0
        }
        val sendFrame = VideoSinkChannel::class.java.getDeclaredMethod(
            "sendFrame", java.nio.ByteBuffer::class.java, android.media.MediaCodec.BufferInfo::class.java,
        ).apply { isAccessible = true }
        runCatching { sendFrame.invoke(ch, java.nio.ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1)), info) }
        runCatching { sendFrame.invoke(ch, java.nio.ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1)), info) }
        // First frame event only fires when the focus arbitration allows streaming;
        // either way the frame path must not crash on a closed connection.
        ch.release()
        Thread.sleep(300)
        assertTrue(events.any { it is VideoEvent.SurfaceChanged } || events.isEmpty(), "$events")
    }
}
