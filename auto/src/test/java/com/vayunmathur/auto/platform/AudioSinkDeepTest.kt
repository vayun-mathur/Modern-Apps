package com.vayunmathur.auto.platform

import com.vayunmathur.auto.protocol.AudioSinkRole
import com.vayunmathur.auto.protocol.FocusArbitration
import com.vayunmathur.auto.protocol.GalConnection
import com.vayunmathur.auto.protocol.GalMessage
import com.vayunmathur.auto.protocol.GalService
import com.vayunmathur.auto.protocol.GalTransport
import com.vayunmathur.auto.protocol.gal.AudioConfiguration
import com.vayunmathur.auto.protocol.gal.AudioFocusState
import com.vayunmathur.auto.protocol.gal.MediaCodecType
import com.vayunmathur.auto.protocol.gal.MediaSetupResponse
import com.vayunmathur.auto.protocol.gal.MediaSinkService
import com.vayunmathur.auto.protocol.gal.Service
import com.vayunmathur.auto.protocol.gal.VideoFocusMode
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deep [AudioSinkChannel] coverage: a started sink actually frames fed PCM
 * and WAV speech, muted focus drops frames, and an unconfirmable CONFIG
 * parks the sink unsupported.
 */
@RunWith(RobolectricTestRunner::class)
class AudioSinkDeepTest {
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

    private fun drainAudio() {
        for (looper in org.robolectric.shadows.ShadowLooper.getAllLoopers()) {
            runCatching { Shadows.shadowOf(looper).idle() }
        }
        Thread.sleep(50) // HandlerThread is a real thread; give its queue a beat
        for (looper in org.robolectric.shadows.ShadowLooper.getAllLoopers()) {
            runCatching { Shadows.shadowOf(looper).idle() }
        }
    }

    private fun service(role: AudioSinkRole): Service = Service.newBuilder()
        .setId(role.serviceId)
        .setMediaSink(
            MediaSinkService.newBuilder()
                .setAvailableType(MediaCodecType.MEDIA_CODEC_AUDIO_PCM)
                .addAudioConfigs(
                    AudioConfiguration.newBuilder()
                        .setSamplingRate(44_100).setNumberOfBits(16).setNumberOfChannels(1).build(),
                ).build(),
        ).build()

    private fun focus(gain: Boolean): FocusArbitration =
        if (gain) {
            FocusArbitration().also {
                it.onVideoFocus(VideoFocusMode.VIDEO_FOCUS_PROJECTED)
                it.onAudioFocus(AudioFocusState.AUDIO_FOCUS_STATE_GAIN)
            }
        } else {
            // No gain and no projected video (projected video is itself an
            // implicit grant in gainFor): the sink stays MUTED.
            FocusArbitration()
        }

    private fun wav(pcm: ByteArray, rate: Int = 44_100): ByteArray {
        val buffer = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII)); buffer.putInt(36 + pcm.size)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII)); buffer.putInt(16)
        buffer.putShort(1); buffer.putShort(1); buffer.putInt(rate)
        buffer.putInt(rate * 2); buffer.putShort(2); buffer.putShort(16)
        buffer.put("data".toByteArray(Charsets.US_ASCII)); buffer.putInt(pcm.size)
        buffer.put(pcm)
        return buffer.array()
    }

    private fun startedChannel(
        role: AudioSinkRole = AudioSinkRole.SYSTEM,
        gain: Boolean = true,
        events: MutableList<AudioEvent> = mutableListOf(),
    ): AudioSinkChannel {
        val channel = AudioSinkChannel(role, service(role), connection(), focus = { focus(gain) }, onEvent = { events += it })
        channel.requestSetup()
        channel.onMessage(
            role.serviceId, GalMessage.Media.CONFIG,
            MediaSetupResponse.newBuilder().setMediaStatus(0).build().toByteArray(),
        )
        return channel
    }

    @Test fun `started sink frames pcm and speaks wav`() {
        val events = mutableListOf<AudioEvent>()
        val channel = startedChannel(events = events)
        assertEquals(AudioSinkState.STARTED, events.filterIsInstance<AudioEvent.SinkStatus>().last().status.state)
        channel.feedPcm(ByteArray(4096) { (it % 128).toByte() }, 44_100)
        channel.feedWav(wav(ByteArray(2048) { 1 }))
        // Poll the real audio thread instead of sleeping a fixed span.
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            drainAudio()
            if (events.any { it is AudioEvent.TtsSpoken } && events.count { it is AudioEvent.FramesSent } >= 2) break
            Thread.sleep(50)
        }
        assertTrue(events.count { it is AudioEvent.FramesSent } >= 2, "expected framed PCM, got $events")
        assertEquals(listOf(1024), events.filterIsInstance<AudioEvent.TtsSpoken>().map { it.chars })
        channel.release()
        assertTrue(events.any { it is AudioEvent.SinkStopped })
    }

    @Test fun `muted focus drops fed frames`() {
        val events = mutableListOf<AudioEvent>()
        val channel = startedChannel(gain = false, events = events)
        channel.feedPcm(ByteArray(4096), 44_100)
        drainAudio()
        assertTrue(events.filterIsInstance<AudioEvent.FramesSent>().isEmpty())
        channel.release()
    }

    @Test fun `config the head unit cannot confirm parks unsupported`() {
        val events = mutableListOf<AudioEvent>()
        val role = AudioSinkRole.MEDIA
        val channel = AudioSinkChannel(role, service(role), connection(), focus = { focus(true) }, onEvent = { events += it })
        channel.requestSetup()
        channel.onMessage(
            role.serviceId, GalMessage.Media.CONFIG,
            MediaSetupResponse.newBuilder().addConfigurationIndices(99).setMediaStatus(0).build().toByteArray(),
        )
        assertEquals(AudioSinkState.UNSUPPORTED, events.filterIsInstance<AudioEvent.SinkStatus>().last().status.state)
        channel.release()
    }

    @Test fun `media role setup and double release are stable`() {
        val events = mutableListOf<AudioEvent>()
        val channel = startedChannel(role = AudioSinkRole.MEDIA, events = events)
        assertEquals("MEDIA", events.filterIsInstance<AudioEvent.SinkStarted>().single().role)
        channel.release()
        channel.release()
        assertEquals(2, events.filterIsInstance<AudioEvent.SinkStopped>().size)
    }
}
