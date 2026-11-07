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
import java.io.FileOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Encoder paths with fake hardware encoders installed (see [FakeEncoders]). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EncoderBackedTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun install() = FakeEncoders.install()
    @After fun reset() = FakeEncoders.reset()

    @Test
    fun `support reports both codecs in preference order with envelopes`() {
        val codecs = EncoderSupport.videoCodecs(1280, 720)
        assertEquals(listOf(VideoCodec.Av1, VideoCodec.Hevc), codecs.map { it.codec })
        val hevc = codecs.first { it.codec == VideoCodec.Hevc }
        assertEquals(3840, hevc.maxWidth)
        assertEquals(2160, hevc.maxHeight)
        assertEquals("fake.hevc.encoder", EncoderSupport.videoEncoderName(VideoCodec.Hevc))
        assertEquals("fake.opus.encoder", EncoderSupport.audioEncoderName())
        assertTrue(EncoderSupport.sustainableFrameRate(VideoCodec.Hevc, 1280, 720) > 0f)
    }

    @Test
    fun `clamp keeps a fitting size and gives up cleanly at an impossible rate`() {
        assertEquals(1280 to 720, EncoderSupport.clampToEncoder(VideoCodec.Hevc, 1280, 720, 30f))
        // No size can hold 100k fps: the ladder runs out and the caller's size is
        // returned rather than a guess (documented behaviour). This exercises the
        // full step-down loop against the fake encoder's measured rates.
        assertEquals(
            1920 to 1080,
            EncoderSupport.clampToEncoder(VideoCodec.Hevc, 1920, 1080, 100_000f),
        )
    }

    @Test
    fun `sustainable rate for degenerate sizes falls back to the envelope`() {
        assertTrue(EncoderSupport.sustainableFrameRate(VideoCodec.Hevc, 0, 0) > 0f)
        assertTrue(EncoderSupport.sustainableFrameRate(VideoCodec.Av1, -5, 100) > 0f)
    }

    @Test
    fun `demoted codec is skipped by controller codec choice`() {
        val tv = com.vayunmathur.cast.platform.FakeTv()
        try {
            val socket = com.vayunmathur.cast.network.ControlSocket("127.0.0.1", tv.port)
            socket.connect()
            val client = com.vayunmathur.cast.platform.MirrorClient(
                socket, senderId = "phone-1", storedDeviceKey = null, senderName = "P",
            )
            kotlin.test.assertTrue(
                client.begin() is com.vayunmathur.cast.platform.HandshakeOutcome.NeedsCode,
            )
            kotlinx.coroutines.runBlocking {
                MirrorPreferences.demoteCodec(context, "tv-fake-1", VideoCodec.Av1)
                val outcome = com.vayunmathur.cast.platform.chooseCodec(
                    context,
                    com.vayunmathur.cast.domain.CastDevice("tv-fake-1", "Fake TV", "127.0.0.1", tv.port),
                    client, 1280, 720,
                )
                val chosen = outcome as com.vayunmathur.cast.platform.CodecOutcome.Chosen
                kotlin.test.assertEquals(VideoCodec.Hevc, chosen.codec)
                MirrorPreferences.clearDemotedCodecs(context, "tv-fake-1")
            }
            client.sayGoodbye("done")
        } finally {
            tv.close()
        }
    }

    @Test
    fun `bitrate mode is answered for a known encoder`() {
        val mode = EncoderSupport.videoBitrateMode(VideoCodec.Hevc, "fake.hevc.encoder")
        assertTrue(
            mode == android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR ||
                mode == android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
            "mode $mode",
        )
    }

    @Test
    fun `video encoder lifecycle on the shadow codec`() {
        val encoder = VideoEncoder(VideoCodec.Hevc, 640, 480, 30f, 1_000_000)
        assertTrue(encoder.start())
        // The shadow codec has no real input surface; the encoder still started.
        assertNull(encoder.inputSurface)
        assertEquals(false, encoder.hasCodecConfig)
        assertTrue(encoder.drain().isEmpty())
        encoder.requestKeyFrame()
        encoder.requestKeyFrame() // rate-limited second ask
        encoder.release()
        encoder.release()
        assertTrue(encoder.drain().isEmpty())
    }

    @Test
    fun `av1 encoder starts and codec config stays absent`() {
        val configs = mutableListOf<ByteArray>()
        val encoder = VideoEncoder(VideoCodec.Av1, 320, 240, 30f, 500_000, onCodecConfig = { configs += it })
        assertTrue(encoder.start())
        assertTrue(encoder.drain().isEmpty())
        encoder.requestKeyFrame()
        assertTrue(configs.isEmpty())
        encoder.release()
    }

    @Test
    fun `opus encoder encodes small buffers and releases`() {
        val opus = OpusEncoder()
        assertTrue(opus.start())
        val chunks = opus.encode(ByteArray(512) { 3 }, 512)
        assertTrue(chunks.all { it.isKeyFrame })
        opus.encode(ByteArray(0), 0)
        opus.release()
        assertTrue(opus.encode(ByteArray(512), 512).isEmpty())
    }

    @Test
    fun `pcm encoder holds a partial frame without encoding it`() {
        val pipe = ParcelFileDescriptor.createPipe()
        val encoder = PcmAudioEncoder(pipe[0])
        assertTrue(encoder.start())
        FileOutputStream(pipe[1].fileDescriptor).use { it.write(ByteArray(100) { 5 }) }
        assertTrue(encoder.pump().isEmpty(), "a partial 20ms frame must be held, not encoded")
        encoder.release()
    }

    @Test
    fun `audio capture encoder starts against the fake opus codec`() {
        val encoder = AudioEncoder(null)
        assertTrue(encoder.start())
        // No pump: the shadow codec's input buffers are smaller than a real
        // encoder's, so feeding a full 20 ms frame is a shadow-only overflow.
        encoder.release()
        encoder.release()
    }

    private fun stream(kind: StreamKind) = NegotiatedStream(
        kind, senderSsrc = 1L, receiverSsrc = 2L,
        payloadType = if (kind == StreamKind.Video) 96 else 127,
        timebase = if (kind == StreamKind.Video) 90_000 else 48_000,
        keys = StreamKeys(ByteArray(16) { 1 }, ByteArray(16) { 2 }),
    )

    @Test
    fun `engine runs an audio-only content session and stops cleanly`() {
        val degraded = mutableListOf<MirrorDegradation>()
        val stopped = mutableListOf<MirrorStopReason>()
        val engine = MirrorEngine(
            context = context,
            source = MirrorSource.Content(appLabel = "Music", wantAudio = true),
            receiverHost = "127.0.0.1",
            negotiation = Negotiation(45_123, listOf(stream(StreamKind.Video), stream(StreamKind.Audio))),
            geometry = CaptureGeometry(640, 480, 160, 1_000_000, 30f),
            videoCodec = VideoCodec.Hevc,
            frameRate = 30f,
            onDegraded = { degraded += it },
            onStopped = { stopped += it },
        )
        assertTrue(engine.start(), "the PCM audio stream should carry the session; stopped=$stopped")
        assertNotNull(engine.audioWriteEnd)
        assertEquals(listOf(MirrorDegradation(videoUnavailable = true, audioUnavailable = false)), degraded)
        Thread.sleep(700) // let the RTCP loop tick at least once
        engine.stop()
        engine.stop()
        assertNull(engine.audioWriteEnd)
    }
}
