package com.vayunmathur.cast.platform.mirror

import android.media.MediaCodec
import android.media.MediaFormat
import com.vayunmathur.library.log.Log
import com.vayunmathur.cast.protocol.StreamConstants
import java.io.IOException

private const val TAG = "OpusEncoder"

private const val TIMEOUT_US = 10_000L

/**
 * PCM in, Opus access units out.
 *
 * Extracted from [AudioEncoder] rather than copied into [PcmAudioEncoder], because the two differ
 * only in where the PCM comes from - playback capture or a pipe from another app - and everything
 * after that is identical: the same `MediaFormat`, the same 20 ms framing, and the same
 * sample-counted timestamps.
 *
 * `audio/opus` encoders are not universally present, which is what [start] returning false means.
 */
class OpusEncoder {

    private var codec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()

    /**
     * Derived from the sample count rather than the clock: a timestamp that drifts against the
     * sample rate is what makes audio slowly desynchronise from video.
     */
    private var samplesWritten = 0L

    fun start(): Boolean {
        val name = EncoderSupport.audioEncoderName()
        if (name == null) {
            Log.status(TAG, "no ${EncoderSupport.AUDIO_MIME} encoder on this device")
            return false
        }
        return try {
            val format = MediaFormat.createAudioFormat(
                EncoderSupport.AUDIO_MIME,
                SAMPLE_RATE,
                CHANNELS,
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            }
            val created = MediaCodec.createByCodecName(name)
            created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            created.start()
            codec = created
            true
        } catch (e: IllegalStateException) {
            failStart(e)
        } catch (e: IllegalArgumentException) {
            failStart(e)
        } catch (e: IOException) {
            failStart(e)
        }
    }

    private fun failStart(e: Exception): Boolean {
        Log.status(TAG, "could not start the Opus encoder", e)
        release()
        return false
    }

    /**
     * Feed [length] bytes from [pcm] and return whatever the codec has ready.
     *
     * [length] may be zero, which is how a caller with nothing to send still drains the codec.
     */
    fun encode(pcm: ByteArray, length: Int): List<EncodedChunk> {
        val active = codec ?: return emptyList()
        if (length > 0) {
            val index = try {
                active.dequeueInputBuffer(TIMEOUT_US)
            } catch (e: IllegalStateException) {
                Log.status(TAG, "audio encoder went away", e)
                return emptyList()
            }
            if (index >= 0) {
                val input = active.getInputBuffer(index)
                input?.clear()
                input?.put(pcm, 0, length)
                active.queueInputBuffer(index, 0, length, presentationTimeUs, 0)
                samplesWritten += length / (2 * CHANNELS)
            }
        }
        val out = mutableListOf<EncodedChunk>()
        while (true) {
            val index = try {
                active.dequeueOutputBuffer(bufferInfo, 0)
            } catch (e: IllegalStateException) {
                Log.status(TAG, "audio encoder went away mid-drain", e)
                return out
            }
            if (index < 0) return out
            val buffer = active.getOutputBuffer(index)
            if (buffer != null &&
                bufferInfo.size > 0 &&
                bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
            ) {
                buffer.position(bufferInfo.offset)
                buffer.limit(bufferInfo.offset + bufferInfo.size)
                val bytes = ByteArray(bufferInfo.size).also { buffer.get(it) }
                // Every audio frame is independently decodable, so all of them are "key frames".
                out += EncodedChunk(bytes, bufferInfo.presentationTimeUs, isKeyFrame = true)
            }
            active.releaseOutputBuffer(index, false)
        }
    }

    fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }

    private val presentationTimeUs: Long
        get() = samplesWritten * 1_000_000L / SAMPLE_RATE

        /** Audio format constants shared with the cast protocol. */
    companion object {
        /**
         * The protocol's audio format, not a third copy of it.
         *
         * `StreamConstants` already had to state the sample rate because the RTP timebase *is* the
         * sample rate, and the channel count and bitrate with it. This encoder producing its own
         * numbers meant two definitions that had to agree and nothing that would notice if they
         * stopped. `CastContract` still states them separately, and has to: `:sdk:cast` may not
         * depend on the protocol, which is the whole point of brokering.
         */
        const val SAMPLE_RATE = StreamConstants.AUDIO_TIMEBASE
        const val CHANNELS = StreamConstants.AUDIO_CHANNELS
        const val BIT_RATE = StreamConstants.AUDIO_BITRATE

        /** 20 ms of stereo 16-bit PCM, which is the frame size Opus wants. */
        const val FRAME_BYTES = SAMPLE_RATE / 50 * 2 * CHANNELS
    }
}

/**
 * Where the Opus stream's PCM comes from.
 *
 * Two implementations, and [MirrorEngine]'s audio loop cannot tell them apart: [AudioEncoder]
 * captures what the phone is playing, [PcmAudioEncoder] reads what another app writes into a pipe.
 */
interface AudioStream {
    /** False when this source cannot be started, which degrades the session to video only. */
    fun start(): Boolean

    /** Move whatever PCM is available through the encoder. Empty means nothing yet. */
    fun pump(): List<EncodedChunk>

    fun release()
}
