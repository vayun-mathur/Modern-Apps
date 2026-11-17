package com.vayunmathur.cast.protocol

/** Which half of the stream a packet belongs to. */
enum class StreamKind { Audio, Video }

/**
 * Fixed parameters both ends agree on without negotiating them.
 *
 * These are the values that used to travel in the Cast OFFER, kept because they are still real
 * constraints - the Opus timebase has to match what `AudioEncoder` produces, and the payload types
 * have to match what the depacketizer routes on - just no longer things a peer gets a say in.
 */
object StreamConstants {

    /** The RTP payload type that marks each half of the stream. Ours to choose; these are Cast's. */
    const val AUDIO_PAYLOAD_TYPE = 127
    const val VIDEO_PAYLOAD_TYPE = 96

    /** Video RTP timestamps are 90 kHz, by long convention rather than by necessity. */
    const val VIDEO_TIMEBASE = 90_000

    /** Audio is timestamped in samples, so the timebase *is* the sample rate. */
    const val AUDIO_TIMEBASE = 48_000

    const val AUDIO_CHANNELS = 2
    const val AUDIO_BITRATE = 128_000

    /**
     * The rate a session aims for, and the rate a codec has to be able to hold to be chosen at all.
     *
     * **These are two different questions and used to be one number.** The floor is what
     * `CodecNegotiation.choose` enforces against the sender's own encoder, because an encoder that
     * silently under-delivers is worse than one that is refused. The ceiling is only an aspiration:
     * each geometry settles at the highest rate the panel, the decoder and this phone's encoder all
     * manage at that size, which is why 4K commonly lands at the floor while 1080p reaches the
     * ceiling.
     *
     * Raising the ceiling to 60 raises the bitrate ask with it - the target is linear in frame rate
     * - so a 4K session roughly doubles from ~15 to ~30 Mbit/s. That is the trade being made.
     */
    const val VIDEO_MAX_FRAME_RATE = 60f
    const val VIDEO_MIN_FRAME_RATE = 30f

    /**
     * How long the receiver buffers before playing, in ms.
     *
     * It is the budget a retransmission has to arrive within to still be useful, which is why the
     * sender's retransmit buffer is sized against it, and it is advertised in every RTCP report.
     *
     * **Deliberately not openscreen's `kDefaultTargetPlayoutDelay` of 400.** That figure was carried
     * over from a Cast receiver and then advertised by a receiver that implemented no buffer at all -
     * frames were presented at network-arrival spacing, so jitter became judder. 150 ms is what the
     * receiver's playout queue actually holds: deep enough to absorb Wi-Fi jitter and leave a NACK
     * time to be answered, shallow enough that mirroring still feels interactive.
     */
    const val TARGET_DELAY_MS = 150

    /**
     * SSRC ranges from openscreen `cast/streaming/ssrc.cc`. Audio is "high priority" and video is
     * "normal"; the ranges are arbitrary but keeping them apart means an SSRC alone says which
     * stream a stray datagram was meant for.
     */
    const val AUDIO_SSRC_MIN = 1
    const val AUDIO_SSRC_MAX = 50_000
    const val VIDEO_SSRC_MIN = 50_001
    const val VIDEO_SSRC_MAX = 100_000
}

/**
 * The AES-128 key and the IV mask for one stream.
 *
 * Per-stream and never shared: audio and video are independent frame-id sequences, so one key
 * across both would reuse a counter block for two different plaintexts.
 */
class StreamKeys(val key: ByteArray, val ivMask: ByteArray) {
    // Arrays, so a generated equals would compare identity. Content comparison is what a test
    // asserting the two ends derived the same schedule means.
    override fun equals(other: Any?): Boolean =
        other is StreamKeys && key.contentEquals(other.key) && ivMask.contentEquals(other.ivMask)

    override fun hashCode(): Int = 31 * key.contentHashCode() + ivMask.contentHashCode()
}

/**
 * One agreed stream: whose SSRC is whose, and what to encrypt with.
 *
 * Both SSRCs are carried rather than derived. The sender picks its own in `STREAM_CONFIG` and the
 * receiver picks its own in `STREAM_READY`, so neither end may assume anything about the other's -
 * the pair is what identifies a stream in every RTCP packet.
 */
data class NegotiatedStream(
    val kind: StreamKind,
    val senderSsrc: Long,
    val receiverSsrc: Long,
    val payloadType: Int,
    val timebase: Int,
    val keys: StreamKeys,
)

/** Everything the handshake settled about the media flow. */
data class Negotiation(
    val udpPort: Int,
    val streams: List<NegotiatedStream>,
) {
    val audio: NegotiatedStream? get() = streams.firstOrNull { it.kind == StreamKind.Audio }
    val video: NegotiatedStream? get() = streams.firstOrNull { it.kind == StreamKind.Video }
    val hasVideo: Boolean get() = video != null

    /** Builds the negotiated stream set both cast ends derive from the handshake. */
    companion object {
        /**
         * Build the routes both ends will use, from the two halves of the handshake.
         *
         * Called on the sender *and* the receiver with the same three inputs, which is the point:
         * the keys are derived rather than sent, so if either end computed a different schedule the
         * picture would be noise. A round-trip test asserts the two agree.
         */
        fun of(config: StreamConfig, ready: StreamReady, keys: SessionKeys): Negotiation {
            val streams = buildList {
                if (config.audio) {
                    add(
                        NegotiatedStream(
                            kind = StreamKind.Audio,
                            senderSsrc = config.audioSsrc,
                            receiverSsrc = ready.audioSsrc,
                            payloadType = StreamConstants.AUDIO_PAYLOAD_TYPE,
                            timebase = StreamConstants.AUDIO_TIMEBASE,
                            keys = keys.audio,
                        ),
                    )
                }
                if (config.video) {
                    add(
                        NegotiatedStream(
                            kind = StreamKind.Video,
                            senderSsrc = config.videoSsrc,
                            receiverSsrc = ready.videoSsrc,
                            payloadType = StreamConstants.VIDEO_PAYLOAD_TYPE,
                            timebase = StreamConstants.VIDEO_TIMEBASE,
                            keys = keys.video,
                        ),
                    )
                }
            }
            return Negotiation(ready.udpPort, streams)
        }
    }
}
