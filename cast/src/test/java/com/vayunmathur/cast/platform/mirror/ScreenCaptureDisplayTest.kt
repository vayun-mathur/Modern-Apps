package com.vayunmathur.cast.platform.mirror

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.cast.protocol.NegotiatedStream
import com.vayunmathur.cast.protocol.Negotiation
import com.vayunmathur.cast.protocol.StreamKeys
import com.vayunmathur.cast.protocol.StreamKind
import com.vayunmathur.cast.protocol.VideoCodec
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [ShadowMediaProjectionGivingDisplay::class])
class ScreenCaptureDisplayTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `start succeeds when the platform gives a display`() {
        val capture = ScreenCapture(newFakeProjection(context))
        val surface = Surface(SurfaceTexture(89))
        assertEquals(true, capture.start(surface, CaptureGeometry(640, 480, 160, 1_000_000, 30f)))
        capture.release()
        capture.release()
        surface.release()
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [ShadowMediaCodecSynthesizing::class, ShadowMediaProjectionGivingDisplay::class])
class EngineScreenSourceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun install() = FakeEncoders.install()
    @After fun reset() = FakeEncoders.reset()

    private fun stream(kind: StreamKind) = NegotiatedStream(
        kind, senderSsrc = 1L, receiverSsrc = 2L,
        payloadType = if (kind == StreamKind.Video) 96 else 127,
        timebase = if (kind == StreamKind.Video) 90_000 else 48_000,
        keys = StreamKeys(ByteArray(16) { 1 }, ByteArray(16) { 2 }),
    )

    @Test
    fun `screen source engine attaches the capture display`() {
        val degraded = mutableListOf<MirrorDegradation>()
        val stopped = mutableListOf<MirrorStopReason>()
        val engine = MirrorEngine(
            context = context,
            source = MirrorSource.Screen(newFakeProjection(context)),
            receiverHost = "127.0.0.1",
            negotiation = Negotiation(45_555, listOf(stream(StreamKind.Video), stream(StreamKind.Audio))),
            geometry = CaptureGeometry(640, 480, 160, 1_000_000, 30f),
            videoCodec = VideoCodec.Hevc,
            frameRate = 30f,
            onDegraded = { degraded += it },
            onStopped = { stopped += it },
        )
        val started = engine.start()
        assertTrue(started, "video should carry the session; stopped=$stopped degraded=$degraded")
        Thread.sleep(300)
        engine.stop()
        engine.stop()
    }
}
