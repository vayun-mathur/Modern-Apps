package com.vayunmathur.cast.platform.mirror

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.cast.protocol.NegotiatedStream
import com.vayunmathur.cast.protocol.Negotiation
import com.vayunmathur.cast.protocol.StreamKeys
import com.vayunmathur.cast.protocol.StreamKind
import com.vayunmathur.cast.protocol.VideoCodec
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
 * The full engine with a surface-producing shadow codec: both streams start,
 * the video drain and RTCP loops run, and stop joins them.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [ShadowMediaCodecWithSurface::class])
class EngineVideoLoopTest {

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
    fun `content engine starts both streams and its loops tick`() {
        val degraded = mutableListOf<MirrorDegradation>()
        val stopped = mutableListOf<MirrorStopReason>()
        val configs = mutableListOf<ByteArray>()
        val engine = MirrorEngine(
            context = context,
            source = MirrorSource.Content(appLabel = "Music", wantAudio = true),
            receiverHost = "127.0.0.1",
            negotiation = Negotiation(45_222, listOf(stream(StreamKind.Video), stream(StreamKind.Audio))),
            geometry = CaptureGeometry(640, 480, 160, 1_000_000, 30f),
            videoCodec = VideoCodec.Av1,
            frameRate = 30f,
            onDegraded = { degraded += it },
            onStopped = { stopped += it },
            onCodecConfig = { configs += it },
        )
        assertTrue(engine.start(), "stopped=$stopped")
        assertNotNull(engine.contentSurface, "the app should get a surface to draw into")
        assertNotNull(engine.audioWriteEnd)
        assertEquals(emptyList(), degraded)
        // Let the video drain poll, the audio pump poll, and the RTCP loop pass
        // its one-second stats log.
        Thread.sleep(1_300)
        engine.stop()
        assertTrue(stopped.isEmpty(), "nothing should have failed: $stopped")
        assertTrue(configs.isEmpty())
        engine.stop()
    }
}
