package com.vayunmathur.auto.platform

import com.vayunmathur.auto.protocol.GalConnection
import com.vayunmathur.auto.protocol.GalService
import com.vayunmathur.auto.protocol.GalTransport
import com.vayunmathur.auto.protocol.gal.AudioConfiguration
import com.vayunmathur.auto.protocol.gal.MediaCodecType
import com.vayunmathur.auto.protocol.gal.MediaSetupResponse
import com.vayunmathur.auto.protocol.gal.MediaSinkService
import com.vayunmathur.auto.protocol.gal.Service
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import javax.net.ssl.SSLContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Auto module: [GuidanceChannel] — the ch3 guidance sink. The grant claims
 * the channel; a TTS session that predates the grant arms the stream; CONFIG
 * confirms against discovery; stop parks without restarting. Sends ride a
 * closed real connection — see [InputChannelTest] for the seam.
 */
@RunWith(RobolectricTestRunner::class)
class GuidanceChannelTest {

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

    private fun guidanceService(vararg configs: AudioConfiguration): Service =
        Service.newBuilder()
            .setId(GalService.AUDIO_SINK_GUIDANCE.id)
            .setMediaSink(
                MediaSinkService.newBuilder()
                    .setAvailableType(MediaCodecType.MEDIA_CODEC_AUDIO_PCM)
                    .addAllAudioConfigs(configs.asList())
                    .build(),
            )
            .build()

    private fun configResponse(vararg indices: Int): ByteArray =
        MediaSetupResponse.newBuilder().setMediaStatus(0)
            .addAllConfigurationIndices(indices.asList()).build()
            .toByteArray()

    @Test
    fun `channel id comes from discovery`() {
        assertEquals(
            GalService.AUDIO_SINK_GUIDANCE.id,
            GuidanceChannel(guidanceService(config()), connection()).channelId,
        )
    }

    @Test
    fun `the grant claims the channel with a setup`() {
        val events = mutableListOf<SensorEvent>()
        GuidanceChannel(guidanceService(config()), connection(), onEvent = { events += it })
            .onChannelOpen()
        assertEquals(listOf<SensorEvent>(SensorEvent.GuidanceSetup), events)
    }

    @Test
    fun `an active tts session arms the stream on the grant`() {
        val events = mutableListOf<SensorEvent>()
        val channel = GuidanceChannel(
            guidanceService(config()), connection(),
            ttsActive = { true },
            onEvent = { events += it },
        )
        channel.onChannelOpen()
        assertEquals(listOf<SensorEvent>(SensorEvent.GuidanceSetup), events)
        // CONFIG confirms: the armed stream starts instead of parking idle.
        channel.onMessage(
            GalService.AUDIO_SINK_GUIDANCE.id,
            com.vayunmathur.auto.protocol.GalMessage.Media.CONFIG,
            configResponse(),
        )
        assertEquals(
            listOf(SensorEvent.GuidanceSetup, SensorEvent.GuidanceConfigured(true)),
            events,
        )
    }

    @Test
    fun `config without tts stays idle until start`() {
        val events = mutableListOf<SensorEvent>()
        val channel = GuidanceChannel(
            guidanceService(config()), connection(),
            ttsActive = { false },
            onEvent = { events += it },
        )
        channel.onChannelOpen()
        channel.onMessage(
            GalService.AUDIO_SINK_GUIDANCE.id,
            com.vayunmathur.auto.protocol.GalMessage.Media.CONFIG,
            configResponse(),
        )
        assertEquals(
            listOf(SensorEvent.GuidanceSetup, SensorEvent.GuidanceConfigured(true)),
            events,
        )
        // Start then stop: the stream parks, and a later CONFIG cannot restart it.
        channel.startStream()
        channel.stopStream()
        channel.onMessage(
            GalService.AUDIO_SINK_GUIDANCE.id,
            com.vayunmathur.auto.protocol.GalMessage.Media.CONFIG,
            configResponse(),
        )
        assertEquals(2, events.count { it is SensorEvent.GuidanceConfigured })
    }

    @Test
    fun `start before config arms and the confirm starts it`() {
        val events = mutableListOf<SensorEvent>()
        val channel = GuidanceChannel(guidanceService(config()), connection(), onEvent = { events += it })
        channel.onChannelOpen()
        channel.startStream()
        channel.onMessage(
            GalService.AUDIO_SINK_GUIDANCE.id,
            com.vayunmathur.auto.protocol.GalMessage.Media.CONFIG,
            configResponse(),
        )
        assertTrue(events.filterIsInstance<SensorEvent.GuidanceConfigured>().isNotEmpty())
    }

    @Test
    fun `wrong-channel traffic is ignored`() {
        val events = mutableListOf<SensorEvent>()
        val channel = GuidanceChannel(guidanceService(config()), connection(), onEvent = { events += it })
        channel.onChannelOpen()
        channel.onMessage(7, com.vayunmathur.auto.protocol.GalMessage.Media.CONFIG, configResponse())
        assertEquals(listOf<SensorEvent>(SensorEvent.GuidanceSetup), events)
    }

    @Test
    fun `acks and syncs are observed without events`() {
        val events = mutableListOf<SensorEvent>()
        val channel = GuidanceChannel(guidanceService(config()), connection(), onEvent = { events += it })
        channel.onChannelOpen()
        channel.onMessage(
            GalService.AUDIO_SINK_GUIDANCE.id,
            com.vayunmathur.auto.protocol.GalMessage.Media.ACK,
            com.vayunmathur.auto.protocol.gal.MediaAck.newBuilder().setSessionId(0).build().toByteArray(),
        )
        channel.onMessage(
            GalService.AUDIO_SINK_GUIDANCE.id,
            com.vayunmathur.auto.protocol.GalMessage.Audio.SYNC,
            byteArrayOf(),
        )
        assertEquals(listOf<SensorEvent>(SensorEvent.GuidanceSetup), events)
    }

    @Test
    fun `release parks the stream`() {
        val channel = GuidanceChannel(guidanceService(config()), connection())
        channel.onChannelOpen()
        channel.startStream()
        channel.release()
        // Post-release stop is safe (session id reset).
        channel.stopStream()
    }
}
