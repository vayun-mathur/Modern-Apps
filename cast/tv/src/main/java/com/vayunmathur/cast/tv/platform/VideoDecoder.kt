package com.vayunmathur.cast.tv.platform

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import com.vayunmathur.library.log.Log
import android.view.Surface
import com.vayunmathur.cast.protocol.CodecLimits
import com.vayunmathur.cast.protocol.CodecNegotiation
import com.vayunmathur.cast.protocol.DecoderLimits
import com.vayunmathur.cast.protocol.VideoCodec
import java.nio.ByteBuffer

private const val TAG = "VideoDecoder"

private const val TIMEOUT_US = 10_000L

/**
 * H.265 or AV1 access units in, pixels on a `Surface` out.
 *
 * The mirror of `:cast`'s `VideoEncoder`, and its `KEY_LOW_LATENCY` twin: without it a decoder is
 * entitled to buffer several frames before emitting the first, which for mirroring shows up as a
 * screen that lags the phone by a visible fraction of a second for no reason.
 *
 * **Where the codec configuration comes from depends on the codec.** For H.265 it is in-band, at the
 * front of the first key frame, exactly as H.264's SPS/PPS were - the sender caches the codec-config
 * buffer and prepends it, so nothing has to be passed to [start]. AV1's sequence header is not
 * expressible that way: `MediaCodec` wants it as `csd-0` at configure time, which is *before* any frame
 * has arrived, so it comes over the control channel and is handed in here instead. A decoder handed a
 * key frame with neither produces nothing and says nothing, which is exactly the failure mode this
 * pairing exists to make impossible.
 */
class VideoDecoder(private val surface: Surface, private val codec: VideoCodec) {

    private var mediaCodec: MediaCodec? = null

    /**
     * Frames the codec would not take.
     *
     * Worth counting rather than only returning: a refused frame desyncs the decoder while the
     * session has already advanced past it, so a run of these is the difference between "the network
     * is lossy" and "this decoder cannot keep up with what we are sending it".
     */
    var framesDropped: Long = 0
        private set

    /** So a broken codec is traced once rather than at frame rate. */
    private var tracedFailure = false

    /**
     * False when there is no usable decoder, which is not recoverable and has to be said out loud.
     *
     * [codecConfig] must be present for a codec whose configuration cannot travel in-band, and is
     * ignored for one whose can - the caller is what enforces that, because it is also what has to wait
     * for the bytes to arrive.
     */
    fun start(width: Int, height: Int, codecConfig: ByteArray? = null): Boolean {
        val name = decoderName(codec)
        if (name == null) {
            Log.status(TAG, "no hardware ${codec.mimeType} decoder on this TV")
            return false
        }
        return try {
            val format = MediaFormat.createVideoFormat(codec.mimeType, width, height).apply {
                // Mirroring is interactive: every millisecond of decoder buffering is a millisecond
                // the picture lags the phone.
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                if (codecConfig != null) {
                    setByteBuffer("csd-0", ByteBuffer.wrap(codecConfig))
                }
            }
            val created = MediaCodec.createByCodecName(name)
            created.configure(format, surface, null, 0)
            created.start()
            mediaCodec = created
            Log.status(
                TAG,
                "decoding ${codec.label} ${width}x$height with $name" +
                    if (codecConfig == null) {
                        ", parameter sets expected in-band"
                    } else {
                        ", csd-0 = ${codecConfig.size} bytes"
                    },
            )
            true
        } catch (e: IllegalArgumentException) {
            Log.status(TAG, "could not start the ${codec.label} decoder", e)
            release()
            false
        } catch (e: IllegalStateException) {
            Log.status(TAG, "could not start the ${codec.label} decoder", e)
            release()
            false
        } catch (e: UnsupportedOperationException) {
            Log.status(TAG, "could not start the ${codec.label} decoder", e)
            release()
            false
        }
    }

    /**
     * Queue one access unit.
     *
     * [presentationTimeUs] comes from the RTP timestamp rather than from arrival time. It is not what
     * decides *when* the frame appears - the caller's playout queue holds each frame until its turn
     * and then renders it - but it keeps the codec's own notion of time honest.
     *
     * Returns false when the codec would not take it, which at 30 fps is worth dropping a frame over
     * rather than blocking the socket loop. A caller that has already counted the frame as delivered
     * must ask for a key frame when this happens.
     */
    fun decode(data: ByteArray, presentationTimeUs: Long, isKeyFrame: Boolean): Boolean {
        val active = mediaCodec ?: return dropped(null)
        return try {
            val index = active.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) return dropped(null)
            val input = active.getInputBuffer(index) ?: return dropped(null)
            input.clear()
            input.put(data)
            val flags = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            active.queueInputBuffer(index, 0, data.size, presentationTimeUs, flags)
            true
        } catch (e: IllegalStateException) {
            dropped(e)
        } catch (e: IllegalArgumentException) {
            dropped(e)
        }
    }

    /**
     * Always false, counted on the way past, and traced at most once.
     *
     * The trace is latched because this is called from the socket loop at frame rate: a codec that
     * has entered a broken state would otherwise log 30 stack traces a second, which is how the audio
     * path once buried its own root cause under 2,634 of them.
     */
    private fun dropped(cause: RuntimeException?): Boolean {
        framesDropped++
        if (cause != null && !tracedFailure) {
            tracedFailure = true
            Log.status(TAG, "could not queue a frame; further failures are counted only", cause)
        }
        return false
    }

    /**
     * Hand whatever the decoder has finished to the surface.
     *
     * `releaseOutputBuffer(index, true)` is what actually renders, so this has to be pumped or the
     * picture never appears however well the decode went.
     *
     * Rendered immediately rather than with a `releaseOutputBuffer(index, renderTimestampNs)`
     * deadline: the caller's playout queue already holds each frame on the *input* side until its
     * turn, and the output buffer pool is finite - asking the decoder to hold frames as well is how a
     * pipeline starves itself of buffers to decode into.
     */
    fun render() {
        val active = mediaCodec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = try {
                active.dequeueOutputBuffer(info, 0)
            } catch (e: IllegalStateException) {
                Log.status(TAG, "decoder went away mid-render", e)
                return
            }
            if (index < 0) return
            active.releaseOutputBuffer(index, true)
        }
    }

    fun release() {
        runCatching { mediaCodec?.stop() }
        runCatching { mediaCodec?.release() }
        mediaCodec = null
    }

    /** Stateless decoder discovery: the hardware decoders this TV can advertise to a sender. */
    companion object {

        /** Bit rate per Mbit/s, for the human-readable line in `limits`. */
        private const val MICROS_PER_UNIT = 1_000_000.0

        /**
         * What to put in `TV_IDENTITY` - the whole reason the receiver is ours.
         *
         * These are the decoders' own numbers, so the sender can stop guessing. The 1080p ceiling
         * the old sender used was openscreen's *mirroring policy* (`"Currently mirroring only
         * supports 1080P"` in `capture_recommendations.h`), never a decoder limit, so it does not
         * apply here: a TV that reports 4K gets 4K-shaped frames, subject to what the phone can
         * encode.
         *
         * **One entry per codec, and no fallback.** Guessing "1080p30 H.264" was defensible while
         * H.264 was universal; with H.264 gone it would be a fabrication - so a TV that can enumerate
         * nothing advertises an empty list and the phone refuses with a named failure. Every codec
         * listed is logged with the decoder it came from, because "which codec was chosen, and why" is
         * a question that can only be answered on hardware and only from these two log lines.
         */
        fun limits(): DecoderLimits = DecoderLimits(
            videoCodecs = CodecNegotiation.PREFERENCE.mapNotNull { codec ->
                val name = decoderName(codec) ?: run {
                    Log.status(TAG, "no hardware decoder for ${codec.label} (${codec.mimeType})")
                    return@mapNotNull null
                }
                val video = runCatching {
                    MediaCodecList(MediaCodecList.REGULAR_CODECS)
                        .codecInfos
                        .first { it.name == name }
                        .getCapabilitiesForType(codec.mimeType)
                        .videoCapabilities
                }.getOrNull() ?: return@mapNotNull null
                val advertised = runCatching {
                    CodecLimits(
                        codec = codec,
                        maxWidth = video.supportedWidths.upper,
                        maxHeight = video.supportedHeights.upper,
                        maxFrameRate = video.supportedFrameRates.upper.toInt(),
                        maxBitRate = video.bitrateRange.upper,
                    )
                }.getOrNull() ?: return@mapNotNull null
                Log.status(
                    TAG,
                    "advertising ${codec.label} up to ${advertised.maxWidth}x" +
                        "${advertised.maxHeight} @ ${advertised.maxFrameRate}fps, " +
                        "${advertised.maxBitRate / MICROS_PER_UNIT} Mbit/s from $name (hardware)",
                )
                advertised
            },
        )

        /**
         * The hardware decoder for [codec], or null.
         *
         * **Hardware is required rather than preferred, and the platform's list order is not trusted.**
         * This used to take the first match and justify it with "the platform lists hardware first",
         * which held only because every device has a hardware H.264 decoder. On a Google TV box AV1
         * arrives as `c2.android.av1.decoder` - a software codec that publishes a large envelope - and
         * an ordering assumption would advertise that envelope to a phone that then believes it.
         * `REGULAR_CODECS` so the list matches what an ordinary app may instantiate, and the largest
         * advertised frame wins among the survivors.
         */
        fun decoderName(codec: VideoCodec): String? {
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            return codecs.filter { info ->
                !info.isEncoder &&
                    info.isHardwareAccelerated &&
                    !info.isSoftwareOnly &&
                    info.supportedTypes.any { it.equals(codec.mimeType, ignoreCase = true) } &&
                    runCatching {
                        info.getCapabilitiesForType(codec.mimeType).videoCapabilities != null
                    }.getOrDefault(false)
            }.maxByOrNull { info -> info.advertisedArea(codec) }?.name
        }

        private fun MediaCodecInfo.advertisedArea(codec: VideoCodec): Long = runCatching {
            val video = getCapabilitiesForType(codec.mimeType).videoCapabilities ?: return 0L
            video.supportedWidths.upper.toLong() * video.supportedHeights.upper
        }.getOrDefault(0L)
    }
}
