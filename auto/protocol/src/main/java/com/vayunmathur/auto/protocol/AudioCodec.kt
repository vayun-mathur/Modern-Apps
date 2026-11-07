package com.vayunmathur.auto.protocol

import com.vayunmathur.auto.protocol.gal.AudioConfiguration
import com.vayunmathur.auto.protocol.gal.MediaAck
import com.vayunmathur.auto.protocol.gal.MediaCodecType
import com.vayunmathur.auto.protocol.gal.MediaSetupRequest
import com.vayunmathur.auto.protocol.gal.MediaSetupResponse
import com.vayunmathur.auto.protocol.gal.MediaStartRequest
import com.vayunmathur.auto.protocol.gal.MediaStopRequest
import com.vayunmathur.auto.protocol.gal.Service
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Which car speaker set a sink feeds.
 *
 * Service 4 (SYSTEM) carries transient sounds -- TTS prompts, notification
 * pings -- and asks for transient focus; service 5 (MEDIA) carries music and
 * asks for full gain. Guidance (service 3) is deliberately unowned: it rides
 * the same `jdk` sink shape, but nothing feeds it yet and opening a channel
 * we never start would only earn head-unit attention we cannot answer.
 */
enum class AudioSinkRole(val serviceId: Int) {
    SYSTEM(GalService.AUDIO_SINK_SYSTEM.id),
    MEDIA(GalService.AUDIO_SINK_MEDIA.id),
    ;

    companion object {
        fun forServiceId(id: Int): AudioSinkRole? = entries.firstOrNull { it.serviceId == id }
    }
}

/**
 * How loud a sink may emit right now.
 *
 * FULL streams as captured; DUCKED scales PCM down before sending (the
 * music-behind-TTS case); MUTED drops frames. Ducking is a sink-side sample
 * decision, not a play gate -- [FocusArbitration.mayPlayMedia] stays the
 * coarse "may the phone make noise" answer, and this refines it per sink.
 */
enum class AudioGain {
    FULL,
    DUCKED,
    MUTED,
}

/** A sink-side inbound message, already classified. Anything else is [Observed]. */
sealed interface InboundAudio {
    /** The head unit answered setup (0x8003): which discovery configs it accepts. */
    data class Config(val response: MediaSetupResponse) : InboundAudio

    /** The head unit consumed frames (0x8004). [ackSeq] is the mod-256 counter, unsigned. */
    data class Ack(val sessionId: Int, val ackSeq: Long?) : InboundAudio

    /** The no-payload 0x800B from the media-sink table; presumed stream sync. */
    data object Sync : InboundAudio

    /**
     * Anything else on the channel -- 0x8013 (`xkq`), malformed setup/ack bytes,
     * a future extension. Observed and ignored, never fatal.
     */
    data class Observed(val type: Int) : InboundAudio
}

/** One microphone bulk chunk with its capture timestamp, if it carried one. */
data class MicChunk(
    /** Microseconds from the 8-byte 0x0000 prefix, or -1 for prefix-less 0x0001. */
    val timestampUs: Long,
    val pcm: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MicChunk) return false
        return timestampUs == other.timestampUs && pcm.contentEquals(other.pcm)
    }

    override fun hashCode(): Int = 31 * timestampUs.hashCode() + pcm.contentHashCode()
}

/** 16-bit mono PCM unwrapped from a TTS WAV file, at its native rate. */
data class WavPcm(val sampleRateHz: Int, val pcm: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WavPcm) return false
        return sampleRateHz == other.sampleRateHz && pcm.contentEquals(other.pcm)
    }

    override fun hashCode(): Int = 31 * sampleRateHz + pcm.contentHashCode()
}

/**
 * Pure audio-channel (services 4/5 sinks, service 6 source) codecs and policy.
 *
 * Android-free like everything else in this module, so config negotiation,
 * the focus preemption matrix and the PCM helpers are all host-testable. The
 * sink bring-up mirrors video (`requestSetup` -> CONFIG -> focus -> START),
 * except the focus ask goes over control 0x18 first: gearhead's `jdk` sends
 * media setup from `onChannelOpened` and the audio sinks request focus before
 * starting the stream.
 *
 * Codec ground truth (FINDINGS.md media-sink table): sinks SEND 0x8000 setup,
 * 0x8001 start, 0x8002 stop and bulk 0x0000/0x0001, and RECEIVE 0x8003 config,
 * 0x8004 ack and no-payload 0x800B. The car owns the microphone, so the phone
 * RECEIVES 0x8006 requests and bulk audio and SENDS 0x8004 acks.
 *
 * Opus note: the teardown's codec enum (`xkj`) is PCM / AAC_LC / H264_BP /
 * AAC_LC_ADTS / VP9 / AV1 / H265 -- no Opus. Negotiation therefore requires
 * PCM (`MEDIA_CODEC_AUDIO_PCM`) and declines anything else rather than
 * starting a stream the head unit cannot decode; an Opus entry point goes in
 * [selectConfig] if a future teardown recovers one.
 */
object AudioCodec {

    /** `MediaSetupRequest.media_type`: 1 audio, 3 video. */
    const val MEDIA_TYPE_AUDIO = 1

    /** Mic acks name the session the sinks use: no mic start handshake carries one. */
    const val MIC_SESSION_ID = 0

    /** 0x0000 bulk carries this many timestamp bytes ahead of the audio. */
    const val TIMESTAMP_BYTES = Long.SIZE_BYTES

    /** Ducked music plays this much quieter behind transient audio (-12 dB). */
    const val DUCK_NUMERATOR = 1
    const val DUCK_DENOMINATOR = 4

    /** Phone -> HU: claim the channel for audio. Sent on the channel grant. */
    fun encodeSetup(): Pair<Int, ByteArray> =
        GalMessage.Media.SETUP_REQUEST to MediaSetupRequest.newBuilder()
            .setMediaType(MEDIA_TYPE_AUDIO)
            .build()
            .toByteArray()

    /** Phone -> HU: start streaming the confirmed configuration. */
    fun encodeStart(sessionId: Int, configurationIndex: Int): Pair<Int, ByteArray> =
        GalMessage.Media.START_REQUEST to MediaStartRequest.newBuilder()
            .setSessionId(sessionId)
            .setConfigurationIndex(configurationIndex)
            .build()
            .toByteArray()

    /** Phone -> HU: stop streaming. Genuinely empty, like video's. */
    fun encodeStop(): Pair<Int, ByteArray> =
        GalMessage.Media.STOP_REQUEST to MediaStopRequest.newBuilder().build().toByteArray()

    /**
     * Phone -> HU: the mic ack (`jaz` sends 0x8004). Session only -- the ack
     * counter is the head unit's own frame tracker on sink channels, and the
     * mic direction has no phone-side counter to report.
     */
    fun encodeMicAck(sessionId: Int = MIC_SESSION_ID): Pair<Int, ByteArray> =
        GalMessage.Media.ACK to MediaAck.newBuilder()
            .setSessionId(sessionId)
            .build()
            .toByteArray()

    /**
     * Picks the discovery configuration to offer, or null when the service
     * cannot carry PCM.
     *
     * Null means "do not start this sink": a non-PCM codec, no audio configs,
     * or no sane config (bits outside 8/16/24/32, channels outside mono/
     * stereo, absurd rate). The bring-up carries on with the remaining
     * services -- an unstarted sink is a missing feature, not a dead session.
     * The returned index addresses the discovery `audio_configs` list, which
     * is what the head unit's `configuration_indices` confirm against.
     */
    fun selectConfig(service: Service): SelectedConfig? {
        if (!service.hasMediaSink()) return null
        val sink = service.mediaSink
        if (sink.availableType != MediaCodecType.MEDIA_CODEC_AUDIO_PCM) return null
        var best: SelectedConfig? = null
        sink.audioConfigsList.forEachIndexed { index, config ->
            if (score(config) > (best?.let { score(it.config) } ?: -1)) {
                best = SelectedConfig(index, config)
            }
        }
        return best?.takeIf { score(it.config) >= 0 }
    }

    /**
     * Confirms our selection against the head unit's 0x8003 answer.
     *
     * An empty index list accepts our pick (same leniency as the video
     * channel's `firstOrNull() ?: 0`); otherwise the best-scoring confirmed
     * config wins, and null -- do not start -- when nothing confirmed is sane.
     */
    fun confirmConfig(
        response: MediaSetupResponse,
        selected: SelectedConfig,
        service: Service,
    ): SelectedConfig? {
        val indices = response.configurationIndicesList
        if (indices.isEmpty()) return selected
        val configs = service.mediaSink.audioConfigsList
        var best: SelectedConfig? = null
        for (index in indices) {
            val config = configs.getOrNull(index) ?: continue
            if (score(config) >= 0 && score(config) > (best?.let { score(it.config) } ?: -1)) {
                best = SelectedConfig(index, config)
            }
        }
        return best
    }

    /**
     * Classifies one inbound sink message. Never throws: malformed
     * setup/ack bytes and unknown types decode to [InboundAudio.Observed],
     * so the pump survives whatever a head unit sends.
     */
    fun decodeSinkInbound(type: Int, payload: ByteArray): InboundAudio = when (type) {
        GalMessage.Media.CONFIG -> runCatching { MediaSetupResponse.parseFrom(payload) }
            .getOrNull()
            ?.let { InboundAudio.Config(it) }
            ?: InboundAudio.Observed(type)
        GalMessage.Media.ACK -> runCatching { MediaAck.parseFrom(payload) }
            .getOrNull()
            ?.let { InboundAudio.Ack(it.sessionId, if (it.hasAck()) Integer.toUnsignedLong(it.ack) else null) }
            ?: InboundAudio.Observed(type)
        GalMessage.Audio.SYNC -> InboundAudio.Sync
        else -> InboundAudio.Observed(type)
    }

    /**
     * Strips one inbound mic bulk chunk, or null when it carries no audio.
     *
     * 0x0000 prefixes an 8-byte big-endian microsecond timestamp; 0x0001 has
     * no prefix. Anything else, a truncated prefix, or a timestamp-only
     * payload is observed-but-empty, never an error.
     */
    fun decodeMicData(type: Int, payload: ByteArray): MicChunk? = when (type) {
        GalMessage.Media.DATA_WITH_TIMESTAMP -> {
            if (payload.size <= TIMESTAMP_BYTES) null
            else {
                val timestamp = ByteBuffer.wrap(payload, 0, TIMESTAMP_BYTES)
                    .order(ByteOrder.BIG_ENDIAN).long
                MicChunk(timestamp, payload.copyOfRange(TIMESTAMP_BYTES, payload.size))
            }
        }
        GalMessage.Media.DATA -> if (payload.isEmpty()) null else MicChunk(-1, payload.copyOf())
        else -> null
    }

    /**
     * The focus preemption matrix: which sink may emit under each head-unit
     * audio state.
     *
     * - Full GAIN streams everything; transient gain lets SYS through and
     *   ducks MEDIA (TTS over music); MEDIA_ONLY mutes SYS; transient
     *   guidance-only mutes MEDIA; every loss mutes both except
     *   LOSS_TRANSIENT_CAN_DUCK, which keeps MEDIA flowing quietly.
     * - Projected video is the implicit grant: it lifts MUTED to FULL, but a
     *   transient gain still preempts MEDIA down to DUCKED -- the TTS case
     *   does not care which screen is up.
     */
    fun gainFor(
        role: AudioSinkRole,
        audioFocus: com.vayunmathur.auto.protocol.gal.AudioFocusState,
        videoFocus: VideoFocus,
    ): AudioGain {
        val base = when (audioFocus) {
            com.vayunmathur.auto.protocol.gal.AudioFocusState.AUDIO_FOCUS_STATE_GAIN ->
                AudioGain.FULL to AudioGain.FULL
            com.vayunmathur.auto.protocol.gal.AudioFocusState.AUDIO_FOCUS_STATE_GAIN_TRANSIENT ->
                AudioGain.FULL to AudioGain.DUCKED
            com.vayunmathur.auto.protocol.gal.AudioFocusState.AUDIO_FOCUS_STATE_GAIN_MEDIA_ONLY ->
                AudioGain.MUTED to AudioGain.FULL
            com.vayunmathur.auto.protocol.gal.AudioFocusState.AUDIO_FOCUS_STATE_GAIN_TRANSIENT_GUIDANCE_ONLY ->
                AudioGain.FULL to AudioGain.MUTED
            com.vayunmathur.auto.protocol.gal.AudioFocusState.AUDIO_FOCUS_STATE_LOSS_TRANSIENT_CAN_DUCK ->
                AudioGain.MUTED to AudioGain.DUCKED
            else -> AudioGain.MUTED to AudioGain.MUTED
        }
        val granted = if (role == AudioSinkRole.SYSTEM) base.first else base.second
        if (granted != AudioGain.MUTED || videoFocus != VideoFocus.PROJECTED) return granted
        // Implicit grant, except the transient preemption above still holds.
        return if (role == AudioSinkRole.MEDIA && audioFocus ==
            com.vayunmathur.auto.protocol.gal.AudioFocusState.AUDIO_FOCUS_STATE_GAIN_TRANSIENT
        ) {
            AudioGain.DUCKED
        } else {
            AudioGain.FULL
        }
    }

    /** Whether [role] may emit at all: anything but MUTED. */
    fun mayPlay(
        role: AudioSinkRole,
        audioFocus: com.vayunmathur.auto.protocol.gal.AudioFocusState,
        videoFocus: VideoFocus,
    ): Boolean = gainFor(role, audioFocus, videoFocus) != AudioGain.MUTED

    /**
     * Scales 16-bit little-endian PCM by [numerator]/[denominator], clamping
     * to short range. Odd trailing bytes are carried over untouched -- a
     * truncated frame is the sender's bug, not a reason to drop the buffer.
     */
    fun scalePcm16(pcm: ByteArray, numerator: Int, denominator: Int): ByteArray {
        if (numerator == denominator) return pcm.copyOf()
        val out = pcm.copyOf()
        val buffer = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i + 1 < pcm.size) {
            val scaled = (buffer.getShort(i).toLong() * numerator / denominator)
                .coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong())
            buffer.putShort(i, scaled.toShort())
            i += 2
        }
        return out
    }

    /**
     * Linear-resamples mono 16-bit PCM between rates. Identity copies;
     * anything else interpolates. Empty in, empty out.
     */
    fun resampleLinearMono16(pcm: ByteArray, fromRateHz: Int, toRateHz: Int): ByteArray {
        require(fromRateHz > 0 && toRateHz > 0) { "rates must be positive" }
        if (pcm.isEmpty()) return ByteArray(0)
        if (fromRateHz == toRateHz) return pcm.copyOf()
        val input = ShortArray(pcm.size / 2) { i ->
            ByteBuffer.wrap(pcm, i * 2, 2).order(ByteOrder.LITTLE_ENDIAN).short
        }
        val outSamples = ((input.size.toLong() * toRateHz) / fromRateHz).toInt().coerceAtLeast(1)
        val out = ByteBuffer.allocate(outSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (o in 0 until outSamples) {
            val position = o.toLong() * fromRateHz / toRateHz
            val index = position.toInt().coerceIn(0, input.size - 1)
            val nextIndex = (index + 1).coerceIn(0, input.size - 1)
            val fraction = ((o.toLong() * fromRateHz % toRateHz).toDouble() / toRateHz)
            // Interpolate between the two neighbouring SAMPLES. This used
            // (next - index) -- the index distance, 0 or 1 -- instead of
            // (input[next] - input[index]), so resampled audio collapsed to
            // the left sample plus at most 1 LSB instead of the ramp.
            val interpolated = input[index] + (input[nextIndex] - input[index]) * fraction
            out.putShort(kotlin.math.round(interpolated).toInt().toShort())
        }
        return out.array()
    }

    /**
     * Unwraps the WAV a TTS `synthesizeToFile` produces into raw mono PCM.
     *
     * Requires PCM format (1), 16-bit samples, mono or stereo (stereo is
     * downmixed by averaging pairs); anything else is null rather than
     * misdecoded audio. Chunks are scanned, not assumed in order -- the only
     * contract is RIFF/WAVE framing.
     */
    fun stripWavToPcm16Mono(wav: ByteArray): WavPcm? {
        val header = readWavHeader(wav) ?: return null
        return scanWavChunks(wav, header)
    }

    private fun readWavHeader(wav: ByteArray): ByteBuffer? {
        if (wav.size < MIN_WAV_SIZE) return null
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        if (header.getInt(RIFF_OFFSET) != RIFF_MAGIC ||
            header.getInt(WAVE_MAGIC_OFFSET) != WAVE_MAGIC
        ) {
            return null
        }
        return header
    }

    private fun scanWavChunks(wav: ByteArray, header: ByteBuffer): WavPcm? {
        var format: WavFormat? = null
        var offset = RIFF_HEADER_SIZE
        while (offset + CHUNK_HEADER_SIZE <= wav.size) {
            val chunkSize = header.getInt(offset + CHUNK_SIZE_OFFSET)
            val body = offset + CHUNK_HEADER_SIZE
            if (chunkSize < 0 || body + chunkSize > wav.size) return null
            when (header.getInt(offset)) {
                FMT_MAGIC -> format = parseFmtChunk(header, body, chunkSize) ?: return null
                DATA_MAGIC -> {
                    val found = format ?: return null
                    return extractMonoPcm(wav, body, chunkSize, found)
                }
            }
            offset = body + chunkSize + (chunkSize % 2)
        }
        return null
    }

    private fun parseFmtChunk(header: ByteBuffer, body: Int, chunkSize: Int): WavFormat? {
        if (chunkSize < FMT_BODY_MIN_SIZE) return null
        if (header.getShort(body) != PCM_FORMAT_TAG.toShort()) return null
        val channels = header.getShort(body + CHANNEL_COUNT_OFFSET).toInt()
        if (channels != MONO_CHANNELS && channels != STEREO_CHANNELS) return null
        val sampleRate = header.getInt(body + SAMPLE_RATE_OFFSET)
        if (sampleRate <= 0) return null
        if (header.getShort(body + BITS_PER_SAMPLE_OFFSET) != BITS_PER_SAMPLE_16.toShort()) return null
        return WavFormat(channels, sampleRate)
    }

    private fun extractMonoPcm(wav: ByteArray, body: Int, chunkSize: Int, format: WavFormat): WavPcm {
        val raw = wav.copyOfRange(body, body + chunkSize - (chunkSize % 2))
        val mono = if (format.channels == MONO_CHANNELS) raw else downmixStereo16(raw)
        return WavPcm(format.sampleRate, mono)
    }

    /**
     * Scores a discovery config for PCM streaming. Negative is unusable;
     * otherwise higher wins: 16-bit, then stereo, then 48 kHz > 44.1 kHz >
     * 16 kHz voice.
     */
    private fun score(config: AudioConfiguration): Int {
        if (!config.hasSamplingRate() || !config.hasNumberOfBits() || !config.hasNumberOfChannels()) {
            return -1
        }
        if (config.numberOfBits !in SUPPORTED_BIT_DEPTHS) return -1
        if (config.numberOfChannels != MONO_CHANNEL_COUNT &&
            config.numberOfChannels != STEREO_CHANNEL_COUNT
        ) {
            return -1
        }
        if (config.samplingRate !in MIN_SAMPLE_RATE_HZ..MAX_SAMPLE_RATE_HZ) return -1
        var score = 0
        if (config.numberOfBits == BITS_PER_SAMPLE_16) score += SCORE_16_BIT
        if (config.numberOfChannels == STEREO_CHANNEL_COUNT) score += SCORE_STEREO
        score += when (config.samplingRate) {
            RATE_48K_HZ -> SCORE_RATE_48K
            RATE_44K1_HZ -> SCORE_RATE_44K1
            RATE_16K_HZ -> SCORE_RATE_16K
            else -> 0
        }
        return score
    }

    private fun downmixStereo16(stereo: ByteArray): ByteArray {
        val frames = stereo.size / STEREO_FRAME_BYTES
        val out = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        val input = ByteBuffer.wrap(stereo).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) {
            out.putShort(((input.short.toInt() + input.short.toInt()) / 2).toShort())
        }
        return out.array()
    }

    private const val RIFF_MAGIC = 0x46464952 // "RIFF", little-endian
    private const val WAVE_MAGIC = 0x45564157 // "WAVE", little-endian
    private const val FMT_MAGIC = 0x20746D66 // "fmt ", little-endian
    private const val DATA_MAGIC = 0x61746164 // "data", little-endian

    /** Smallest valid WAV: the 44-byte canonical header with an empty data chunk. */
    private const val MIN_WAV_SIZE = 44

    /** Offsets of the RIFF/WAVE fourcc tags in the file header. */
    private const val RIFF_OFFSET = 0
    private const val WAVE_MAGIC_OFFSET = 8

    /** RIFF header size: "RIFF" + size + "WAVE" before the first chunk header. */
    private const val RIFF_HEADER_SIZE = 12

    /** Every chunk header is an 8-byte [id][size] pair. */
    private const val CHUNK_HEADER_SIZE = 8

    /** Offset of the size field within a chunk header. */
    private const val CHUNK_SIZE_OFFSET = 4

    /** "fmt " body must hold at least the 16 PCM descriptor bytes. */
    private const val FMT_BODY_MIN_SIZE = 16

    /** PCM format tag (1) in the "fmt " descriptor: anything else is declined. */
    private const val PCM_FORMAT_TAG = 1

    /** Offsets within the "fmt " descriptor body. */
    private const val CHANNEL_COUNT_OFFSET = 2
    private const val SAMPLE_RATE_OFFSET = 4
    private const val BITS_PER_SAMPLE_OFFSET = 14

    /** Only mono TTS output passes through; stereo is downmixed to this. */
    private const val MONO_CHANNELS = 1

    /** Stereo pairs are averaged into one mono channel. */
    private const val STEREO_CHANNELS = 2

    /** Bytes of one interleaved stereo 16-bit frame: two samples. */
    private const val STEREO_FRAME_BYTES = 4

    /** One channel count and one bit depth alias, for the discovery scorer. */
    private const val MONO_CHANNEL_COUNT = 1
    private const val STEREO_CHANNEL_COUNT = 2

    /** Bit depths a discovery config may advertise; only 16-bit scores highest. */
    private val SUPPORTED_BIT_DEPTHS = setOf(8, 16, 24, 32)

    /** Sane discovery sample-rate floor/ceiling: voice floor to hi-res ceiling. */
    private const val MIN_SAMPLE_RATE_HZ = 8_000
    private const val MAX_SAMPLE_RATE_HZ = 192_000

    /** Only PCM-16 discovery configs are preferred; see [score]. */
    private const val BITS_PER_SAMPLE_16 = 16

    /** Preferred discovery rates, highest score first. */
    private const val RATE_48K_HZ = 48_000
    private const val RATE_44K1_HZ = 44_100
    private const val RATE_16K_HZ = 16_000

    /** Discovery score weights: 16-bit, then stereo, then sample rate. */
    private const val SCORE_16_BIT = 4
    private const val SCORE_STEREO = 2
    private const val SCORE_RATE_48K = 3
    private const val SCORE_RATE_44K1 = 2
    private const val SCORE_RATE_16K = 1
}

/** Parsed "fmt " descriptor: channel count and rate needed to unwrap "data". */
private data class WavFormat(val channels: Int, val sampleRate: Int)

/** One usable discovery configuration and its index in `audio_configs`. */
data class SelectedConfig(val index: Int, val config: AudioConfiguration)
