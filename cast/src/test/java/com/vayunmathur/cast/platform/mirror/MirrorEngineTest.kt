package com.vayunmathur.cast.platform.mirror

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.cast.protocol.NegotiatedStream
import com.vayunmathur.cast.protocol.Negotiation
import com.vayunmathur.cast.protocol.StreamKeys
import com.vayunmathur.cast.protocol.StreamKind
import com.vayunmathur.cast.protocol.VideoCodec
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MirrorEngineTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val geometry = CaptureGeometry(640, 480, 160, 1_000_000, 30f)

    private fun stream(kind: StreamKind) = NegotiatedStream(
        kind, senderSsrc = 1L, receiverSsrc = 2L,
        payloadType = if (kind == StreamKind.Video) 96 else 127,
        timebase = if (kind == StreamKind.Video) 90_000 else 48_000,
        keys = StreamKeys(ByteArray(16) { 1 }, ByteArray(16) { 2 }),
    )

    private fun engine(
        source: MirrorSource,
        negotiation: Negotiation,
        stopped: MutableList<MirrorStopReason>,
        host: String = "127.0.0.1",
    ) = MirrorEngine(
        context = context,
        source = source,
        receiverHost = host,
        negotiation = negotiation,
        geometry = geometry,
        videoCodec = VideoCodec.Hevc,
        frameRate = 30f,
        onDegraded = {},
        onStopped = { stopped += it },
        onCodecConfig = {},
    )

    @Test
    fun `no streams means no encoders`() {
        val stopped = mutableListOf<MirrorStopReason>()
        val engine = engine(
            MirrorSource.Content(appLabel = "Music", wantAudio = false),
            Negotiation(udpPort = 45_000, streams = emptyList()),
            stopped,
        )
        assertEquals(false, engine.start())
        assertEquals(listOf(MirrorStopReason.NoEncoders), stopped)
        assertNull(engine.contentSurface)
        assertNull(engine.audioWriteEnd)
        engine.stop()
    }

    @Test
    fun `content streams degrade to no encoders without device codecs`() {
        val stopped = mutableListOf<MirrorStopReason>()
        val degraded = mutableListOf<MirrorDegradation>()
        val engine = MirrorEngine(
            context = context,
            source = MirrorSource.Content(appLabel = "Music", wantAudio = true),
            receiverHost = "127.0.0.1",
            negotiation = Negotiation(45_001, listOf(stream(StreamKind.Video), stream(StreamKind.Audio))),
            geometry = geometry,
            videoCodec = VideoCodec.Av1,
            frameRate = 30f,
            onDegraded = { degraded += it },
            onStopped = { stopped += it },
        )
        engine.hexDump = true
        assertEquals(false, engine.start())
        assertEquals(listOf(MirrorStopReason.NoEncoders), stopped)
        engine.stop()
        engine.stop()
    }

    @Test
    fun `system display source also reports no encoders`() {
        val stopped = mutableListOf<MirrorStopReason>()
        val source = MirrorSource.SystemDisplay()
        assertEquals("", source.appLabel)
        assertEquals(-1, source.displayId)
        val engine = engine(
            source,
            Negotiation(45_002, listOf(stream(StreamKind.Audio))),
            stopped,
        )
        assertEquals(false, engine.start())
        assertEquals(listOf(MirrorStopReason.NoEncoders), stopped)
    }

    @Test
    fun `bad host fails at the udp open`() {
        val stopped = mutableListOf<MirrorStopReason>()
        val engine = engine(
            MirrorSource.Content(appLabel = "", wantAudio = false),
            Negotiation(45_003, listOf(stream(StreamKind.Video))),
            stopped,
            host = "256.256.256.256",
        )
        assertEquals(false, engine.start())
        assertTrue(stopped.isNotEmpty())
    }

    @Test
    fun `pcm encoder cannot start without opus and releases its pipe`() {
        val pipe = ParcelFileDescriptor.createPipe()
        val encoder = PcmAudioEncoder(pipe[0])
        assertEquals(false, encoder.start())
        assertTrue(encoder.pump().isEmpty())
        encoder.release()
        encoder.release()
        runCatching { pipe[1].close() }
    }

    @Test
    fun `pcm pump before start is empty`() {
        val pipe = ParcelFileDescriptor.createPipe()
        val encoder = PcmAudioEncoder(pipe[0])
        assertTrue(encoder.pump().isEmpty())
        encoder.release()
        runCatching { pipe[1].close() }
    }

    @Test
    fun `audio encoder cannot start without opus`() {
        val encoder = AudioEncoder(null)
        assertEquals(false, encoder.start())
        assertTrue(encoder.pump().isEmpty())
        encoder.release()
    }

    @Test
    fun `degradation and geometry defaults`() {
        assertEquals(MirrorDegradation(), MirrorDegradation(videoUnavailable = false, audioUnavailable = false))
        val content = MirrorSource.Content(appLabel = "YouPipe", wantAudio = true)
        assertEquals("YouPipe", content.appLabel)
        assertTrue(content.wantAudio)
    }
}
