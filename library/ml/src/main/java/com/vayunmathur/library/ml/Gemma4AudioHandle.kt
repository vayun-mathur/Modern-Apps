package com.vayunmathur.library.ml

import android.os.ParcelFileDescriptor
import com.vayunmathur.library.log.Log
import java.io.File

/**
 * Gemma 4's audio tower: a waveform in, the soft tokens the decoder reads in its place.
 *
 * The sibling of [Gemma4VisionHandle]. [Gemma4Handle] is text-only; this produces the `[n, 1536]`
 * block that stands in for a clip in its prompt, and [Gemma4Handle.generate] splices it in.
 *
 * # A separate file and a separate handle
 *
 * The tower is its own `.maml` with its own graph id, downloaded separately. It is also the
 * largest of the three at 166 MB - bigger than the vision tower, because each of its twelve
 * layers carries two 4x feed-forwards plus a gated convolution module. A device that never sends
 * audio never fetches it, and a tower that fails to load leaves the assistant answering without
 * sound rather than taking it down.
 *
 * # Unlike the vision tower, there is no size to negotiate
 *
 * [Gemma4VisionHandle] has to ask native what to resize an image to, because the target is a
 * patch budget and being one block out changes the token count. Audio has no such choice: the
 * clip's own length decides, at one soft token per 40 ms. So [encode] takes samples and nothing
 * else.
 *
 * # What the caller owes
 *
 * 16 kHz mono, roughly -1..1. The front end has no gain of its own, so the scale it arrives in is
 * the scale the tower sees. Native truncates to [MAX_SAMPLES] and runs the log-mel itself.
 *
 * Do **not** pad the waveform. The reference pads to a multiple of 128 samples so a batch stacks
 * and then spends a validity mask through the whole tower undoing it; this runtime records a plan
 * per clip length and passes it at its true length, which was measured bit-identical against the
 * export. Padding here would silently append soft tokens of silence.
 *
 * # Threading
 *
 * Not thread-safe. A new clip length re-records the plan, so two concurrent encodes would race on
 * the recording.
 */
class Gemma4AudioHandle private constructor(private val file: File) : AutoCloseable {

    private var handle: Long = if (MlNative.isAvailable) create(file) else 0L

    /** Whether the tower came up. False leaves the assistant without audio rather than crashing. */
    val isAvailable: Boolean
        get() = handle != 0L

    /**
     * Soft tokens for [samples] as one flat `n * 1536` array, or null.
     *
     * [samples] is 16 kHz mono. Anything past [MAX_SAMPLES] is dropped rather than refused, on
     * the grounds that a caller handing over a minute of audio wants the first thirty seconds
     * encoded. Anything under [MIN_SAMPLES] returns null: the tower's attention band is twelve
     * tokens wide and a shorter clip cannot fill it.
     *
     * Divide the length by [OUT_DIM] for the number of soft tokens, which is what
     * [Gemma4Handle.generate] reserves positions for.
     */
    fun encode(samples: FloatArray): FloatArray? {
        if (handle == 0L) return null
        if (samples.size < MIN_SAMPLES) {
            Log.status(TAG, "${samples.size} samples is under the $MIN_SAMPLES the band needs")
            return null
        }
        return MlNative.encodeAudioGemma4(handle, samples)
    }

    override fun close() {
        val live = handle
        handle = 0L
        if (live != 0L) MlNative.destroyGemma4Audio(live)
    }

    override fun toString(): String = "Gemma 4 audio tower at $file"

    companion object {
        private const val TAG = "Gemma4AudioHandle"

        /** The tower. Native checks its graph id, so a wrong file fails at load. */
        const val AUDIO = "gemma4_audio.maml"

        /** Channels per soft token, which is the decoder's `hidden_size`. */
        const val OUT_DIM = 1536

        /** What the front end expects. Resampling is the caller's. */
        const val SAMPLE_RATE = 16_000

        /**
         * Thirty seconds, the reference's own truncation and what bounds the tower's arena.
         *
         * Mirrors `nets::gemma4_audio::MAX_SAMPLES`. A longer clip is not an error; native keeps
         * the first this many samples.
         *
         * # Thirty seconds fits the decoder's context
         *
         * This is the TOWER's limit and it knows nothing about the prompt it will be spliced
         * into. At 25 soft tokens a second a full clip is 750 positions, against a
         * [Gemma4Handle.MAX_CONTEXT] of 16384. The fixed prefix was last measured at ~1871
         * positions (twenty-four tool declarations plus the system prompt); with twenty-five
         * tools today that figure has moved, so re-measure rather than trusting it. Even at a
         * rounded-up 2000, the room for audio under the default 512-position reply reserve is
         * `16384 - 512 - 2000 = 13872` positions - a full 30 s clip with an order of magnitude
         * to spare.
         *
         * (An earlier revision of this docstring did the same arithmetic against a 2048 window,
         * where the prefix left 174 positions and no clip worth sending fit. That window is
         * gone: both the Kotlin [Gemma4Handle.MAX_CONTEXT] and native `nets::gemma4::MAX_CONTEXT`
         * are 16384.)
         *
         * Over budget, [Gemma4Handle.generate] still trims audio rather than returning null, so
         * the turn survives - but with this much headroom a trim means the conversation has grown
         * very long, not that the prefix is too big.
         *
         * P moves whenever a tool is added or removed; re-measure rather than trusting the
         * figure above. The lever is the NUMBER OF TOOLS declared rather than the length of
         * their prose - declaring three tools instead of twenty-five frees far more than
         * deleting every description.
         *
         * **The caller must budget.** Shortening this constant would be the wrong fix: the
         * headroom depends on the prompt, which this class cannot see.
         *
         * Reclaiming the prefix means **emitting fewer tokens**, not prefilling the same ones
         * faster. Caching the declaration prefix's KV would make turn two cheap and would free
         * no context at all - the positions are still occupied, so the budget above is
         * unchanged. Only shrinking the prefix moves it.
         */
        const val MAX_SAMPLES = 480_000

        /**
         * The shortest clip the tower can be recorded for: 45 mel frames, twelve soft tokens.
         *
         * The attention band is twelve wide and is a property of the trained model rather than of
         * the clip, so it cannot be narrowed for a short one. About 0.45 s.
         *
         * # Why 7201 and not 7200
         *
         * The round number is wrong, and by exactly one sample. `logmel::frame_count` pads by
         * half a frame and then subtracts `FRAME_SAMPLES + 1`, so 7200 samples is 44 frames and
         * eleven tokens - one short of the band - while 7201 is 45 and twelve. Deriving the
         * bound as 45 hops x 160 samples ignores both terms and lands a sample low, which lets a
         * clip through this guard for native to refuse a moment later.
         */
        const val MIN_SAMPLES = 7_201

        /** Milliseconds of audio per soft token: a 10 ms hop through two stride-2 convolutions. */
        const val MS_PER_TOKEN = 40

        /** The tower in a folder on disk. Construction never throws. */
        fun inDirectory(directory: File): Gemma4AudioHandle =
            Gemma4AudioHandle(File(directory, AUDIO))

        /**
         * Open the graph and hand the descriptor over.
         *
         * Native adopts it and closes it on every path including failure, so [handed] guards only
         * the window between detaching and the call being made.
         */
        private fun create(file: File): Long {
            if (!file.isFile) {
                Log.status(TAG, "${file.name} is missing")
                return 0L
            }
            val fd = runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    .use { it.detachFd() }
            }.getOrElse {
                Log.status(TAG, "cannot open ${file.name}: $it")
                return 0L
            }
            var handed = false
            try {
                val live = MlNative.createGemma4Audio(fd, 0L, file.length())
                handed = true
                return live
            } finally {
                if (!handed) runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
            }
        }
    }
}
