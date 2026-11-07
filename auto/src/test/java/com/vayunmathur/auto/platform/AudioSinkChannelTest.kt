package com.vayunmathur.auto.platform

import com.vayunmathur.auto.protocol.AudioSinkRole
import com.vayunmathur.auto.protocol.FocusArbitration
import com.vayunmathur.auto.protocol.GalConnection
import com.vayunmathur.auto.protocol.GalMessage
import com.vayunmathur.auto.protocol.GalService
import com.vayunmathur.auto.protocol.GalTransport
import com.vayunmathur.auto.protocol.gal.AudioConfiguration
import com.vayunmathur.auto.protocol.gal.MediaAck
import com.vayunmathur.auto.protocol.gal.MediaCodecType
import com.vayunmathur.auto.protocol.gal.MediaSetupResponse
import com.vayunmathur.auto.protocol.gal.MediaSinkService
import com.vayunmathur.auto.protocol.gal.Service
import com.vayunmathur.auto.protocol.gal.VideoFocusMode
import com.vayunmathur.auto.protocol.gal.AudioFocusState
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.io.ByteArrayOutputStream
import javax.net.ssl.SSLContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Auto module: [AudioSinkChannel] — one PCM sink (SYSTEM ch4 or MEDIA ch5).
 * An AAC-only discovery parks UNSUPPORTED; PCM negotiates setup → CONFIG →
 * START; acks/syncs count; malformed TTS wavs drop with a count; release
 * stops. Sends ride a closed real connection — see [InputChannelTest] for
 * the seam; the audio thread drains via idled loopers like
 * [MicSourceChannelTest].
 */
@RunWith(RobolectricTestRunner::class)
class AudioSinkChannelTest {

    private class FakeTransport : GalTransport {
        val written = ByteArrayOutputStream()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            written.write(bytes, offset, length)
        }
        override fun flush() = Unit
        override fun close() = Unit
    }

    private fun connection(): GalConnection {
        val context = SSLContext.getInstance("TLS").apply { init(null, null, null) }
        return GalConnection(FakeTransport(), context, deviceModel = "Test").also { it.close() }
    }

    private fun config(rate: Int = 44_100, bits: Int = 16, channels: Int = 1): AudioConfiguration =
        AudioConfiguration.newBuilder()
            .setSamplingRate(rate)
            .setNumberOfBits(bits)
            .setNumberOfChannels(channels)
            .build()

    private fun service(vararg configs: AudioConfiguration): Service =
        Service.newBuilder()
            .setId(GalService.AUDIO_SINK_SYSTEM.id)
            .setMediaSink(
                MediaSinkService.newBuilder()
                    .setAvailableType(MediaCodecType.MEDIA_CODEC_AUDIO_PCM)
                    .addAllAudioConfigs(configs.asList())
                    .build(),
            )
            .build()

    private fun projectingFocus(): FocusArbitration =
        FocusArbitration().also {
            it.onVideoFocus(VideoFocusMode.VIDEO_FOCUS_PROJECTED)
            it.onAudioFocus(AudioFocusState.AUDIO_FOCUS_STATE_GAIN)
        }

    private fun channel(
        service: Service = service(config()),
        events: MutableList<AudioEvent> = mutableListOf(),
    ): AudioSinkChannel = AudioSinkChannel(
        AudioSinkRole.SYSTEM,
        service,
        connection(),
        focus = ::projectingFocus,
        onEvent = { events += it },
    )

    private fun configResponse(): ByteArray =
        MediaSetupResponse.newBuilder().setMediaStatus(0).build().toByteArray()

    private fun drainLoopers() {
        for (looper in org.robolectric.shadows.ShadowLooper.getAllLoopers()) {
            runCatching { Shadows.shadowOf(looper).idle() }
        }
    }

    @Test
    fun `channel id comes from discovery`() {
        assertEquals(GalService.AUDIO_SINK_SYSTEM.id, channel().channelId)
    }

    @Test
    fun `an aac-only service parks unsupported`() {
        val events = mutableListOf<AudioEvent>()
        val aac = Service.newBuilder()
            .setId(GalService.AUDIO_SINK_SYSTEM.id)
            .setMediaSink(
                MediaSinkService.newBuilder()
                    .setAvailableType(MediaCodecType.MEDIA_CODEC_AUDIO_AAC_LC)
                    .addAudioConfigs(config())
                    .build(),
            )
            .build()
        channel(aac, events).requestSetup()
        val setup = events.filterIsInstance<AudioEvent.SinkSetup>()
        assertEquals(1, setup.size)
        assertEquals("SYSTEM", setup.single().role)
        val status = events.filterIsInstance<AudioEvent.SinkStatus>().last()
        assertEquals(AudioSinkState.UNSUPPORTED, status.status.state)
    }

    @Test
    fun `pcm setup negotiates and config starts the stream`() {
        val events = mutableListOf<AudioEvent>()
        val channel = channel(events = events)
        channel.requestSetup()
        assertTrue(events.filterIsInstance<AudioEvent.SinkSetup>().isNotEmpty())
        channel.onMessage(
            GalService.AUDIO_SINK_SYSTEM.id,
            GalMessage.Media.CONFIG,
            configResponse(),
        )
        val started = events.filterIsInstance<AudioEvent.SinkStarted>()
        assertEquals(1, started.size)
        assertEquals("SYSTEM", started.single().role)
        val status = events.filterIsInstance<AudioEvent.SinkStatus>().last()
        assertEquals(AudioSinkState.STARTED, status.status.state)
        channel.release()
        assertTrue(events.filterIsInstance<AudioEvent.SinkStopped>().isNotEmpty())
    }

    @Test
    fun `wrong-channel traffic is ignored`() {
        val events = mutableListOf<AudioEvent>()
        val channel = channel(events = events)
        channel.requestSetup()
        val before = events.size
        channel.onMessage(7, GalMessage.Media.CONFIG, configResponse())
        assertEquals(before, events.size)
        channel.release()
    }

    @Test
    fun `acks and syncs count`() {
        val events = mutableListOf<AudioEvent>()
        val channel = channel(events = events)
        channel.onMessage(
            GalService.AUDIO_SINK_SYSTEM.id,
            GalMessage.Media.ACK,
            MediaAck.newBuilder().setSessionId(0).build().toByteArray(),
        )
        channel.onMessage(GalService.AUDIO_SINK_SYSTEM.id, GalMessage.Audio.SYNC, byteArrayOf())
        assertEquals(1, events.filterIsInstance<AudioEvent.AckReceived>().size)
        assertEquals(1, events.filterIsInstance<AudioEvent.SyncReceived>().size)
        channel.release()
    }

    @Test
    fun `malformed tts wav drops with a count`() {
        val events = mutableListOf<AudioEvent>()
        val channel = channel(events = events)
        channel.requestSetup()
        channel.feedWav(byteArrayOf(1, 2, 3, 4))
        drainLoopers()
        assertTrue(events.filterIsInstance<AudioEvent.TtsDropped>().isNotEmpty())
        channel.release()
    }

    @Test
    fun `pcm before start drops without frames`() {
        val events = mutableListOf<AudioEvent>()
        val channel = channel(events = events)
        channel.requestSetup()
        // SETUP but no CONFIG: the stream never started, so feeds drop.
        channel.feedPcm(ByteArray(64), 44_100)
        drainLoopers()
        assertTrue(events.filterIsInstance<AudioEvent.FramesSent>().isEmpty())
        channel.release()
    }

    @Test
    fun `focus change republishes the sink status`() {
        val events = mutableListOf<AudioEvent>()
        val channel = channel(events = events)
        channel.requestSetup()
        val before = events.filterIsInstance<AudioEvent.SinkStatus>().size
        channel.onFocusChanged()
        assertEquals(before + 1, events.filterIsInstance<AudioEvent.SinkStatus>().size)
        channel.release()
    }

    @Test
    fun `unhandled types are observed and ignored`() {
        val events = mutableListOf<AudioEvent>()
        val channel = channel(events = events)
        channel.requestSetup()
        val before = events.size
        channel.onMessage(GalService.AUDIO_SINK_SYSTEM.id, 0x1234, byteArrayOf(1))
        assertEquals(before, events.size)
        channel.release()
    }
}
