package com.vayunmathur.library.ml

/**
 * Google's always-on music gate, ported to the CPU.
 *
 * The cheap half of Now Playing. Feed it microphone PCM and it answers "is this music?"
 * once every 10 ms, from 8,200 int8 parameters. [NnfpHandle] is the other half - the
 * expensive fingerprinter you only run once this has said yes - and the two are different
 * networks with different shapes, not two entry points to one model.
 *
 * # Not on the GPU, unlike everything else here
 *
 * Every other handle in this package dispatches Vulkan compute. This does not, and so it
 * has no asset, no file descriptor and no device: [inProcess] cannot fail for want of
 * hardware. At 100 Hz over ~8,200 multiply-accumulates the arithmetic is microseconds,
 * while a GPU dispatch would be 100 submit/fence/readback round trips a second on a
 * workload whose whole point is being cheap enough to leave running. Google ships it on a
 * DSP for the same reason.
 *
 * # Streaming, and the warm-up
 *
 * Stateful: six four-deep circular buffers carry 230 ms of context between calls, so
 * scores depend on everything pushed since the last [reset]. The first ~23 hops after a
 * reset are computed from cold buffers, so they are withheld rather than reported - a
 * [push] can legitimately return nothing at all. A caller starting a fresh listening
 * session must [reset] or its first scores describe the previous session's audio.
 *
 * # Not thread-safe
 *
 * One [push] at a time, and no [push] concurrent with [close]. Hold a lock across both.
 *
 * # What this is not
 *
 * It is not a reproduction of Google's shipped behaviour. The network and its
 * quantization are recovered exactly, but two links in the chain are inferences - the
 * sigmoid reading of the model's int16 score, and the smoothing inside the latching
 * classifier - and the runtime threshold overrides in `music_detector.descriptor` are
 * unrecoverable, because that file is a symbol and byte-offset table with no values in
 * it. Treat the probabilities as a well-founded signal to tune against, not as ground
 * truth. See `detector/ARCHITECTURE.md` section 3.
 */
class MusicGate private constructor() : AutoCloseable {
    private var handle: Long = 0L

    /** False when the native library is missing. Every other member is then inert. */
    val isAvailable: Boolean get() = handle != 0L

    /**
     * Feed [count] samples from the front of [pcm] and collect one probability in `0f..1f`
     * per completed hop, oldest first.
     *
     * Empty when the call completed no hop or the gate is still warming up. Null only when
     * the gate is unavailable or [count] does not fit [pcm].
     */
    fun push(pcm: ShortArray, count: Int = pcm.size): FloatArray? {
        if (handle == 0L) return null
        if (count < 0 || count > pcm.size) return null
        return MlNative.musicGatePush(handle, pcm, count)
    }

    /** Discard the streaming state so the next [push] starts a fresh session. */
    fun reset() {
        if (handle != 0L) MlNative.resetMusicGate(handle)
    }

    /** Free the gate. Idempotent. */
    override fun close() {
        val live = handle
        handle = 0L
        if (live != 0L) MlNative.destroyMusicGate(live)
    }

    override fun toString(): String = "Now Playing music gate (CPU)"

    /** Front-end timing constants and the in-process factory for [MusicGate]. */
    companion object {
        const val SAMPLE_RATE = 16_000

        /** 10 ms. One probability comes out per hop. */
        const val HOP_SAMPLES = 160

        /** The front end's analysis window. The first hop needs this many samples. */
        const val FRAME_SAMPLES = 400

        /** Hops of context behind a reported score: `1 + 6*(4-1) + (5-1)`, so 230 ms. */
        const val RECEPTIVE_FIELD_HOPS = 23

        /** Samples after a [reset] before the first probability appears. */
        const val WARMUP_SAMPLES = FRAME_SAMPLES + HOP_SAMPLES * (RECEPTIVE_FIELD_HOPS - 1)

        /**
         * Build a gate. Never throws; check [isAvailable].
         *
         * Named for the fact that there is nothing to open - no asset, no descriptor - to
         * distinguish it from [NnfpHandle.inAssets], which does have a file behind it.
         */
        fun inProcess(): MusicGate {
            val instance = MusicGate()
            instance.handle = if (MlNative.isAvailable) MlNative.createMusicGate() else 0L
            return instance
        }
    }
}
