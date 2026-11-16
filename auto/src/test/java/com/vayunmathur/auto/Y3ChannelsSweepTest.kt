package com.vayunmathur.auto

import com.vayunmathur.auto.platform.GuidanceChannel
import com.vayunmathur.auto.platform.NavStatusChannel
import com.vayunmathur.auto.protocol.NavStatusUpdate
import com.vayunmathur.auto.platform.SensorChannel
import com.vayunmathur.auto.platform.SensorEvent
import com.vayunmathur.auto.protocol.GalConnection
import com.vayunmathur.auto.protocol.GalMessage
import com.vayunmathur.auto.protocol.GalService
import com.vayunmathur.auto.protocol.GalTransport
import com.vayunmathur.auto.protocol.MapsGuidance
import com.vayunmathur.auto.protocol.NavSnapshot
import com.vayunmathur.auto.protocol.SensorCodec
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

@RunWith(RobolectricTestRunner::class)
class Y3ChannelsSweepTest {
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

    @Test fun sensorChannelOpenLiveEventsAndRelease() {
        val events = mutableListOf<SensorEvent>()
        val values = mutableListOf<com.vayunmathur.auto.protocol.SensorSnapshot>()
        val ch = SensorChannel(connection(), onEvent = { events += it }, onValues = { values += it })
        ch.postLiveEvents(emptyList()) // not open: dropped
        assertTrue(values.isEmpty())
        ch.onChannelOpen()
        assertTrue(events.any { it is SensorEvent.Subscribed })
        val snapshot = NavSnapshot(latitudeE7 = 100, longitudeE7 = 200, speedMps = 10.0, parked = false, isNight = true)
        ch.postLiveEvents(MapsGuidance.toSensorEvents(snapshot))
        assertTrue(values.isNotEmpty())
        assertEquals(snapshot.speedMps, values.last().speedMps)
        // Inbound: wrong channel, then a subscribe answer sweep on our own.
        ch.onMessage(999, GalMessage.Sensor.SENSOR_BATCH, byteArrayOf(1))
        ch.onMessage(ch.channelId, GalMessage.Sensor.SENSOR_BATCH, byteArrayOf(0, 1, 2))
        ch.onMessage(ch.channelId, 0x7777, byteArrayOf(1))
        ch.release(); ch.release()
    }

    @Test fun navStatusChannelDedupAndGuidanceLifecycle() {
        val nav = NavStatusChannel(connection())
        nav.postStatus(true, "Market", 100, "left") // not open
        nav.onChannelOpen()
        val update = NavStatusUpdate(true, "Market", 100, "left")
        nav.postUpdate(update)
        nav.postUpdate(update) // deduped
        nav.postUpdate(NavStatusUpdate(false, null, null, null))
        nav.postStatus(false)
        nav.onMessage(nav.channelId, 0x1234)
        nav.onMessage(999, 0x1234)
        nav.release()

        val audioConfig = AudioConfiguration.newBuilder().setSamplingRate(44_100).setNumberOfBits(16).setNumberOfChannels(1).build()
        val guidanceService = Service.newBuilder().setId(GalService.AUDIO_SINK_GUIDANCE.id)
            .setMediaSink(MediaSinkService.newBuilder().setAvailableType(MediaCodecType.MEDIA_CODEC_AUDIO_PCM).addAudioConfigs(audioConfig).build()).build()
        val guidance = GuidanceChannel(guidanceService, connection())
        guidance.onChannelOpen()
        guidance.startStream(); guidance.startStream()
        guidance.onMessage(guidance.channelId, GalMessage.Media.CONFIG,
            MediaSetupResponse.newBuilder().setMediaStatus(0).addConfigurationIndices(0).build().toByteArray())
        guidance.onMessage(guidance.channelId, GalMessage.Media.ACK, byteArrayOf(1, 2))
        guidance.onMessage(999, GalMessage.Media.CONFIG, byteArrayOf())
        guidance.stopStream(); guidance.release()
        assertEquals(GalService.AUDIO_SINK_GUIDANCE.id, guidance.channelId)
        assertTrue(SensorCodec.STUB_SUBSCRIPTIONS.isNotEmpty())
    }

    @Test fun sensorSubscribeTimeoutSurfacesSynthesizedErrors() {
        val events = mutableListOf<SensorEvent>()
        val ch = SensorChannel(connection(), onEvent = { events += it })
        ch.onChannelOpen()
        // Subscribes are marked at open; after the 2s answer window the next
        // inbound message sweeps them into synthesized timeout errors.
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(3))
        ch.onMessage(ch.channelId, GalMessage.Sensor.SENSOR_ERROR, byteArrayOf(0))
        assertTrue(events.any { it is SensorEvent.SensorError }, "$events")
        ch.release()
    }
}
