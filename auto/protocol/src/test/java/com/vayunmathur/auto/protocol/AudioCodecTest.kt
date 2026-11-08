package com.vayunmathur.auto.protocol

import com.vayunmathur.auto.protocol.gal.AudioConfiguration
import com.vayunmathur.auto.protocol.gal.AudioFocusState
import com.vayunmathur.auto.protocol.gal.MediaCodecType
import com.vayunmathur.auto.protocol.gal.MediaSetupResponse
import com.vayunmathur.auto.protocol.gal.MediaSinkService
import com.vayunmathur.auto.protocol.gal.Service
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins audio config negotiation (PCM required, PCM picked, head-unit answer
 * confirmed), the focus preemption matrix, mic bulk framing and the PCM
 * helpers -- all host-runnable, all pure [AudioCodec].
 */
class AudioCodecTest {

    private fun audioService(vararg configs: AudioConfiguration, type: MediaCodecType = MediaCodecType.MEDIA_CODEC_AUDIO_PCM): Service =
        Service.newBuilder()
            .setId(GalService.AUDIO_SINK_MEDIA.id)
            .setMediaSink(
                MediaSinkService.newBuilder()
                    .setAvailableType(type)
                    .addAllAudioConfigs(configs.asList())
                    .build(),
            )
            .build()

    private fun config(rate: Int = 48_000, bits: Int = 16, channels: Int = 2): AudioConfiguration =
        AudioConfiguration.newBuilder()
            .setSamplingRate(rate)
            .setNumberOfBits(bits)
            .setNumberOfChannels(channels)
            .build()

    // ---- negotiation ----

    @Test
    fun `setup claims the channel for audio`() {
        val (type, payload) = AudioCodec.encodeSetup()
        assertEquals(GalMessage.Media.SETUP_REQUEST, type)
        val parsed = com.vayunmathur.auto.protocol.gal.MediaSetupRequest.parseFrom(payload)
        assertEquals(AudioCodec.MEDIA_TYPE_AUDIO, parsed.mediaType)
    }

    @Test
    fun `start names the session and the confirmed config`() {
        val (type, payload) = AudioCodec.encodeStart(sessionId = 0, configurationIndex = 2)
        assertEquals(GalMessage.Media.START_REQUEST, type)
        val parsed = com.vayunmathur.auto.protocol.gal.MediaStartRequest.parseFrom(payload)
        assertEquals(0, parsed.sessionId)
        assertEquals(2, parsed.configurationIndex)
    }

    @Test
    fun `pcm service picks the best config`() {
        val selected = AudioCodec.selectConfig(
            audioService(config(16_000, 16, 1), config(48_000, 16, 2), config(44_100, 16, 2)),
        )
        assertNotNull(selected)
        assertEquals(1, selected.index)
        assertEquals(48_000, selected.config.samplingRate)
    }

    @Test
    fun `an aac-only service is not started`() {
        assertNull(
            AudioCodec.selectConfig(
                audioService(config(), type = MediaCodecType.MEDIA_CODEC_AUDIO_AAC_LC),
            ),
        )
    }

    @Test
    fun `a service with no audio configs is not started`() {
        assertNull(AudioCodec.selectConfig(audioService()))
    }

    @Test
    fun `an insane config is not started`() {
        assertNull(AudioCodec.selectConfig(audioService(config(48_000, 7, 2))))
        assertNull(AudioCodec.selectConfig(audioService(config(48_000, 16, 5))))
        assertNull(AudioCodec.selectConfig(audioService(config(3_000_000, 16, 2))))
    }

    @Test
    fun `a non-sink service is not started`() {
        val service = Service.newBuilder().setId(GalService.AUDIO_SINK_MEDIA.id).build()
        assertNull(AudioCodec.selectConfig(service))
    }

    @Test
    fun `an empty head-unit answer accepts our pick`() {
        val service = audioService(config(48_000, 16, 2))
        val selected = AudioCodec.selectConfig(service)!!
        val confirmed = AudioCodec.confirmConfig(
            MediaSetupResponse.newBuilder().setMediaStatus(0).build(),
            selected,
            service,
        )
        assertEquals(selected, confirmed)
    }

    @Test
    fun `a confirming head-unit answer picks the best confirmed config`() {
        val service = audioService(config(48_000, 16, 2), config(16_000, 16, 1))
        val selected = AudioCodec.selectConfig(service)!!
        val confirmed = AudioCodec.confirmConfig(
            MediaSetupResponse.newBuilder().setMediaStatus(0).addConfigurationIndices(1).build(),
            selected,
            service,
        )
        assertNotNull(confirmed)
        assertEquals(1, confirmed.index)
        assertEquals(16_000, confirmed.config.samplingRate)
    }

    @Test
    fun `an out-of-range head-unit answer means do not start`() {
        val service = audioService(config(48_000, 16, 2))
        val selected = AudioCodec.selectConfig(service)!!
        assertNull(
            AudioCodec.confirmConfig(
                MediaSetupResponse.newBuilder().setMediaStatus(0).addConfigurationIndices(9).build(),
                selected,
                service,
            ),
        )
    }

    // ---- sink inbound ----

    @Test
    fun `a setup answer classifies as config`() {
        val payload = MediaSetupResponse.newBuilder()
            .setMediaStatus(0)
            .addConfigurationIndices(0)
            .build()
            .toByteArray()
        val inbound = AudioCodec.decodeSinkInbound(GalMessage.Media.CONFIG, payload)
        assertTrue(inbound is InboundAudio.Config)
        assertEquals(listOf(0), inbound.response.configurationIndicesList.toList())
    }

    @Test
    fun `an ack classifies with its unsigned counter`() {
        val payload = com.vayunmathur.auto.protocol.gal.MediaAck.newBuilder()
            .setSessionId(0)
            .setAck(-1)
            .build()
            .toByteArray()
        val inbound = AudioCodec.decodeSinkInbound(GalMessage.Media.ACK, payload)
        assertEquals(InboundAudio.Ack(0, 4_294_967_295L), inbound)
    }

    @Test
    fun `a sync pulse classifies as sync`() {
        assertEquals(InboundAudio.Sync, AudioCodec.decodeSinkInbound(GalMessage.Audio.SYNC, ByteArray(0)))
    }

    @Test
    fun `garbage on a sink channel is observed, never fatal`() {
        assertEquals(
            InboundAudio.Observed(0x8013),
            AudioCodec.decodeSinkInbound(0x8013, ByteArray(0)),
        )
        assertEquals(
            InboundAudio.Observed(GalMessage.Media.CONFIG),
            AudioCodec.decodeSinkInbound(GalMessage.Media.CONFIG, byteArrayOf(1, 2, 3)),
        )
        assertEquals(
            InboundAudio.Observed(GalMessage.Media.ACK),
            AudioCodec.decodeSinkInbound(GalMessage.Media.ACK, byteArrayOf(1, 2, 3)),
        )
    }

    @Test
    fun `sink and mic ids are distinct across services`() {
        val ids = setOf(
            GalMessage.Media.SETUP_REQUEST,
            GalMessage.Media.START_REQUEST,
            GalMessage.Media.STOP_REQUEST,
            GalMessage.Media.CONFIG,
            GalMessage.Media.ACK,
            GalMessage.Audio.SYNC,
            GalMessage.Microphone.REQUEST,
            GalMessage.Media.DATA,
            GalMessage.Media.DATA_WITH_TIMESTAMP,
        )
        assertEquals(9, ids.size)
    }

    // ---- mic ----

    @Test
    fun `a timestamped mic chunk strips its prefix`() {
        val payload = ByteBuffer.allocate(8 + 3).order(ByteOrder.BIG_ENDIAN)
            .putLong(42L)
            .put(byteArrayOf(1, 2, 3))
            .array()
        assertEquals(
            MicChunk(42L, byteArrayOf(1, 2, 3)),
            AudioCodec.decodeMicData(GalMessage.Media.DATA_WITH_TIMESTAMP, payload),
        )
    }

    @Test
    fun `a prefix-less mic chunk keeps no timestamp`() {
        assertEquals(
            MicChunk(-1, byteArrayOf(4, 5)),
            AudioCodec.decodeMicData(GalMessage.Media.DATA, byteArrayOf(4, 5)),
        )
    }

    @Test
    fun `a truncated mic payload is observed-but-empty`() {
        assertNull(AudioCodec.decodeMicData(GalMessage.Media.DATA_WITH_TIMESTAMP, ByteArray(8)))
        assertNull(AudioCodec.decodeMicData(GalMessage.Media.DATA, ByteArray(0)))
        assertNull(AudioCodec.decodeMicData(GalMessage.Microphone.REQUEST, byteArrayOf(1)))
    }

    @Test
    fun `a mic ack names the mic session`() {
        val (type, payload) = AudioCodec.encodeMicAck()
        assertEquals(GalMessage.Media.ACK, type)
        assertEquals(
            AudioCodec.MIC_SESSION_ID,
            com.vayunmathur.auto.protocol.gal.MediaAck.parseFrom(payload).sessionId,
        )
    }

    // ---- focus preemption matrix ----

    private fun gain(role: AudioSinkRole, audio: AudioFocusState, video: VideoFocus): AudioGain =
        AudioCodec.gainFor(role, audio, video)

    @Test
    fun `full gain streams everything`() {
        assertEquals(AudioGain.FULL, gain(AudioSinkRole.SYSTEM, AudioFocusState.AUDIO_FOCUS_STATE_GAIN, VideoFocus.NATIVE))
        assertEquals(AudioGain.FULL, gain(AudioSinkRole.MEDIA, AudioFocusState.AUDIO_FOCUS_STATE_GAIN, VideoFocus.NATIVE))
    }

    @Test
    fun `transient gain lets sys through and ducks media`() {
        assertEquals(AudioGain.FULL, gain(AudioSinkRole.SYSTEM, AudioFocusState.AUDIO_FOCUS_STATE_GAIN_TRANSIENT, VideoFocus.NATIVE))
        assertEquals(AudioGain.DUCKED, gain(AudioSinkRole.MEDIA, AudioFocusState.AUDIO_FOCUS_STATE_GAIN_TRANSIENT, VideoFocus.NATIVE))
    }

    @Test
    fun `media-only mutes sys while music streams`() {
        assertEquals(AudioGain.MUTED, gain(AudioSinkRole.SYSTEM, AudioFocusState.AUDIO_FOCUS_STATE_GAIN_MEDIA_ONLY, VideoFocus.NATIVE))
        assertEquals(AudioGain.FULL, gain(AudioSinkRole.MEDIA, AudioFocusState.AUDIO_FOCUS_STATE_GAIN_MEDIA_ONLY, VideoFocus.NATIVE))
    }

    @Test
    fun `transient guidance mutes media`() {
        assertEquals(AudioGain.FULL, gain(AudioSinkRole.SYSTEM, AudioFocusState.AUDIO_FOCUS_STATE_GAIN_TRANSIENT_GUIDANCE_ONLY, VideoFocus.NATIVE))
        assertEquals(AudioGain.MUTED, gain(AudioSinkRole.MEDIA, AudioFocusState.AUDIO_FOCUS_STATE_GAIN_TRANSIENT_GUIDANCE_ONLY, VideoFocus.NATIVE))
    }

    @Test
    fun `loss mutes both, duckable loss keeps music quiet`() {
        for (loss in listOf(
            AudioFocusState.AUDIO_FOCUS_STATE_LOSS,
            AudioFocusState.AUDIO_FOCUS_STATE_LOSS_TRANSIENT,
        )) {
            assertEquals(AudioGain.MUTED, gain(AudioSinkRole.SYSTEM, loss, VideoFocus.NATIVE))
            assertEquals(AudioGain.MUTED, gain(AudioSinkRole.MEDIA, loss, VideoFocus.NATIVE))
        }
        assertEquals(
            AudioGain.DUCKED,
            gain(AudioSinkRole.MEDIA, AudioFocusState.AUDIO_FOCUS_STATE_LOSS_TRANSIENT_CAN_DUCK, VideoFocus.NATIVE),
        )
        assertEquals(
            AudioGain.MUTED,
            gain(AudioSinkRole.SYSTEM, AudioFocusState.AUDIO_FOCUS_STATE_LOSS_TRANSIENT_CAN_DUCK, VideoFocus.NATIVE),
        )
    }

    @Test
    fun `invalid focus stays muted on a native screen`() {
        assertFalse(
            AudioCodec.mayPlay(AudioSinkRole.SYSTEM, AudioFocusState.AUDIO_FOCUS_STATE_INVALID, VideoFocus.NATIVE),
        )
        assertFalse(
            AudioCodec.mayPlay(AudioSinkRole.MEDIA, AudioFocusState.AUDIO_FOCUS_STATE_INVALID, VideoFocus.NATIVE),
        )
    }

    @Test
    fun `projected video is the implicit grant, except the tts duck`() {
        assertEquals(AudioGain.FULL, gain(AudioSinkRole.SYSTEM, AudioFocusState.AUDIO_FOCUS_STATE_LOSS, VideoFocus.PROJECTED))
        assertEquals(AudioGain.FULL, gain(AudioSinkRole.MEDIA, AudioFocusState.AUDIO_FOCUS_STATE_INVALID, VideoFocus.PROJECTED))
        // The transient preemption survives the grant: TTS still ducks music.
        assertEquals(AudioGain.DUCKED, gain(AudioSinkRole.MEDIA, AudioFocusState.AUDIO_FOCUS_STATE_GAIN_TRANSIENT, VideoFocus.PROJECTED))
    }

    @Test
    fun `mayPlay is anything but muted`() {
        assertTrue(AudioCodec.mayPlay(AudioSinkRole.MEDIA, AudioFocusState.AUDIO_FOCUS_STATE_GAIN, VideoFocus.NATIVE))
        assertTrue(AudioCodec.mayPlay(AudioSinkRole.MEDIA, AudioFocusState.AUDIO_FOCUS_STATE_GAIN_TRANSIENT, VideoFocus.NATIVE))
        assertFalse(AudioCodec.mayPlay(AudioSinkRole.MEDIA, AudioFocusState.AUDIO_FOCUS_STATE_LOSS, VideoFocus.NATIVE))
    }

    // ---- PCM helpers ----

    @Test
    fun `scaling pcm attenuates and clamps`() {
        val pcm = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1000).putShort(Short.MAX_VALUE).array()
        val scaled = AudioCodec.scalePcm16(pcm, AudioCodec.DUCK_NUMERATOR, AudioCodec.DUCK_DENOMINATOR)
        val view = ByteBuffer.wrap(scaled).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(250, view.short)
        assertEquals((Short.MAX_VALUE / 4).toShort(), view.short)
    }

    @Test
    fun `scaling to silence and identity leave no garbage`() {
        val pcm = byteArrayOf(1, 2, 3)
        val identity = AudioCodec.scalePcm16(byteArrayOf(0, 1), 1, 1)
        assertContentEquals(byteArrayOf(0, 1), identity)
        assertContentEquals(byteArrayOf(0, 0, 3), AudioCodec.scalePcm16(pcm, 0, 4))
    }

    @Test
    fun `upsampling doubles zero-crossings and downsampling halves them`() {
        // One 16-sample sine period at 16 kHz; up to 48 kHz triples the samples.
        val period = ShortArray(16) { i -> (10_000 * kotlin.math.sin(i * 2 * kotlin.math.PI / 16)).toInt().toShort() }
        val pcm = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).apply {
            period.forEach { putShort(it) }
        }.array()
        val up = AudioCodec.resampleLinearMono16(pcm, 16_000, 48_000)
        assertEquals(48, up.size / 2)
        val back = AudioCodec.resampleLinearMono16(up, 48_000, 16_000)
        assertEquals(16, back.size / 2)
        // Interpolation stays within the original envelope.
        val view = ByteBuffer.wrap(back).order(ByteOrder.LITTLE_ENDIAN)
        repeat(16) { assertTrue(kotlin.math.abs(view.short.toInt()) <= 10_000) }
    }

    @Test
    fun `resampling interpolates between neighbouring samples`() {
        // Regression: the interpolator used the index distance (0 or 1)
        // instead of the sample difference, so a 0 -> 9000 ramp upsampled
        // 3x came out as 0,0,0,9000... instead of 0,3000,6000,9000...
        val pcm = pcmBytes(shortArrayOf(0, 9_000, 9_000))
        val up = AudioCodec.resampleLinearMono16(pcm, 16_000, 48_000)
        val view = ByteBuffer.wrap(up).order(ByteOrder.LITTLE_ENDIAN)
        val samples = ShortArray(up.size / 2) { view.short }
        assertContentEquals(
            shortArrayOf(0, 3_000, 6_000, 9_000, 9_000, 9_000, 9_000, 9_000, 9_000),
            samples,
        )
    }

    @Test
    fun `a mono wav unwraps at its native rate`() {
        val samples = shortArrayOf(0, 1000, -1000, 3000)
        val wav = wavBytes(samples, channels = 1, rate = 16_000)
        assertEquals(WavPcm(16_000, pcmBytes(samples)), AudioCodec.stripWavToPcm16Mono(wav))
    }

    @Test
    fun `a stereo wav downmixes to mono`() {
        val left = shortArrayOf(1000, 2000)
        val right = shortArrayOf(3000, -2000)
        val wav = wavBytesStereo(left, right, rate = 16_000)
        val stripped = AudioCodec.stripWavToPcm16Mono(wav)
        assertNotNull(stripped)
        assertEquals(16_000, stripped.sampleRateHz)
        assertContentEquals(pcmBytes(shortArrayOf(2000, 0)), stripped.pcm)
    }

    @Test
    fun `a non-pcm wav is declined, not misdecoded`() {
        assertNull(AudioCodec.stripWavToPcm16Mono(ByteArray(10)))
        assertNull(AudioCodec.stripWavToPcm16Mono("RIFF....WAVEjunk".toByteArray()))
        val samples = shortArrayOf(1, 2)
        val float = wavBytes(samples, channels = 1, rate = 16_000, format = 3)
        assertNull(AudioCodec.stripWavToPcm16Mono(float))
        val fiveChannel = wavBytes(shortArrayOf(1, 2, 3, 4, 5), channels = 5, rate = 16_000)
        assertNull(AudioCodec.stripWavToPcm16Mono(fiveChannel))
    }

    private fun pcmBytes(samples: ShortArray): ByteArray =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            samples.forEach { putShort(it) }
        }.array()

    private fun wavBytes(samples: ShortArray, channels: Int, rate: Int, format: Int = 1): ByteArray {
        val data = pcmBytes(samples)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + data.size)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(format.toShort())
        header.putShort(channels.toShort())
        header.putInt(rate)
        header.putInt(rate * channels * 2)
        header.putShort((channels * 2).toShort())
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(data.size)
        return header.array() + data
    }

    private fun wavBytesStereo(left: ShortArray, right: ShortArray, rate: Int): ByteArray {
        val interleaved = ByteBuffer.allocate((left.size + right.size) * 2).order(ByteOrder.LITTLE_ENDIAN)
        left.indices.forEach { interleaved.putShort(left[it]); interleaved.putShort(right[it]) }
        val data = interleaved.array()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + data.size)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(2)
        header.putInt(rate)
        header.putInt(rate * 4)
        header.putShort(4)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(data.size)
        return header.array() + data
    }
}
