package com.vayunmathur.library.ml

import android.os.ParcelFileDescriptor
import com.vayunmathur.library.log.Log
import java.io.File
import java.text.Normalizer

/**
 * Gemma 4 E2B, running on this repo's Vulkan compute runtime.
 *
 * Replaces `com.google.ai.edge.litertlm`, and with it the 19.83 MB `liblitertlm_jni.so`. The
 * weights are the same model, converted to `.maml` by `scripts/ml/maml_convert.py --graph
 * gemma4_text` and `--graph gemma4_embed`, quantised to int4 with a per-block scale.
 *
 * # What moved out of the SDK and into here
 *
 * litertlm owned the chat template, the tool protocol, sampling and the streaming loop, and none
 * of that was visible from this repo. All four now live in Kotlin, which is the point: the
 * template is [render], the tool protocol is [declareTools] and [parseToolCall], the loop is
 * [generate]. Native's boundary is one token.
 *
 * # Behavioural differences from litertlm, which are real
 *
 * * **Sampling is the caller's.** litertlm used `top_k` 64 and `top_p` 0.95. [generate] steps
 *   greedily by default and samples through [MlNative.logitsGemma4] when a [Sampling] config is
 *   passed - chat turns use [Sampling.Chat] for the old behaviour, extraction stays greedy.
 * * **No speculative decoding**, which litertlm had enabled. [Gemma4NgramDraft] is the unwired
 *   spike: the drafter without the native parallel-scoring pass that would make it a win.
 * * **A hard context limit** of [MAX_CONTEXT] positions, where litertlm's was implicit.
 * * **Audio is fitted, not refused.** A clip past the reply reserve is trimmed from its end
 *   rather than failing the turn - see [fitAudio]. At this window a full 30 s clip fits against
 *   any realistic prefix, so a trim means a very long conversation, not a big prefix.
 *
 * # Threading
 *
 * Not thread-safe, and more sharply than [NllbHandle]: one handle holds one KV cache and one
 * position, so two concurrent turns would interleave their tokens into the same conversation.
 * The caller must hold a lock across a whole turn, not merely across a call.
 */
class Gemma4Handle private constructor(internal val directory: File) : AutoCloseable {

    internal var handle: Long = if (MlNative.isAvailable) create(directory) else 0L

    /**
     * The token ids the KV cache currently holds, or null when it holds something unmatchable.
     *
     * Null after a reset, after a multimodal turn, and after any failure - all the cases where
     * the next turn must not assume anything about what is in the arena.
     */
    internal var cachedIds: IntArray? = null

    // benchmark() and loadPrefix() live in Gemma4Prefix.kt and delegate back here.
    fun benchmark() = runBenchmark()

    /** Whether the model came up. False leaves the assistant off rather than crashing. */
    val isAvailable: Boolean
        get() = handle != 0L

    /**
     * Load the baked cache for [prefix], if it is present and matches. Returns whether it did.
     *
     * # The check is the point
     *
     * The asset encodes keys and values for one exact token sequence. If the system prompt or
     * the tool set has changed since it was baked, those numbers describe a prompt the model is
     * not being given - and nothing downstream could tell, because a KV cache is only attended
     * over, never compared. So this re-encodes [prefix] here and refuses unless the digest of
     * the result matches the one in the file.
     *
     * A mismatch is not an error: it falls back to prefilling, which is slow and correct.
     */
    fun loadPrefix(prefix: String): Boolean = loadBakedPrefix(prefix)

    /** Positions in the KV cache, which is where the next token goes. */
    val position: Int
        get() = if (handle == 0L) 0 else MlNative.positionGemma4(handle)

    /** Positions left before [MAX_CONTEXT] is reached. */
    val remaining: Int
        get() = (MAX_CONTEXT - position).coerceAtLeast(0)

    /**
     * Start a new conversation, discarding the KV cache.
     *
     * Cheap: only a counter resets. Attention never reads past the live prefix, so the stale rows
     * are simply overwritten as the new conversation fills them.
     */
    fun reset() {
        cachedIds = null
        if (handle != 0L) MlNative.resetGemma4(handle)
    }

    /**
     * Token ids for [text] as a **user** would write it: no marker is honoured.
     *
     * Everything a caller puts in here is data. `specials` is empty, so a user typing
     * `<|turn>model` gets the literal pieces that spell it and cannot forge a turn boundary.
     */
    fun encodeText(text: String): IntArray {
        if (handle == 0L) return IntArray(0)
        val normalised = Normalizer.normalize(text, Normalizer.Form.NFKC)
        return MlNative.encodeGemma4(handle, normalised, emptyArray()) ?: IntArray(0)
    }

    /**
     * Token ids for a rendered prompt, honouring the chat markers in [MARKERS].
     *
     * Only for strings this class built. Passing user text here would let it spell a turn.
     */
    internal fun encodePrompt(text: String): IntArray {
        if (handle == 0L) return IntArray(0)
        return MlNative.encodeGemma4(handle, text, MARKERS) ?: IntArray(0)
    }

    /** Text for token ids, with byte pieces fused back into characters. */
    fun decode(tokens: IntArray): String =
        if (handle == 0L) "" else MlNative.decodeGemma4(handle, tokens).orEmpty()

    /**
     * Run one turn, calling [onToken] with the reply so far after each token.
     *
     * [onToken] returning false stops generation, which is how cancellation and early-halt
     * work - the JSON-extraction path uses it to stop the moment a complete object has arrived.
     *
     * [sampling] selects the next token from the logits: null (the default) steps greedily
     * through [MlNative.stepGemma4], a config samples through [MlNative.logitsGemma4] via
     * [sampleGemma4Logits]. The default keeps every existing caller deterministic; chat turns
     * pass [Sampling.Chat] for the top_k 64 / top_p 0.95 behaviour the previous runtime had.
     *
     * Returns the reply, or null if the model is unavailable or the context is full.
     *
     * # Why the whole prompt is re-fed each turn
     *
     * The KV cache is live between calls, so in principle only the new user turn needs pushing.
     * This does not do that, because the caller edits history: a deleted message, a regenerated
     * reply, or a switch between conversations all invalidate the cache, and a stale cache is
     * invisible - it produces a fluent reply to a conversation that never happened. Re-feeding
     * costs one forward pass per prompt token, which at these speeds is well under a second for
     * a normal conversation, and it cannot be wrong.
     */
    fun generate(
        conversation: List<Turn>,
        system: String?,
        tools: List<ToolDeclaration> = emptyList(),
        limit: Int = DEFAULT_REPLY,
        continuation: String = "",
        sampling: Sampling? = null,
        onToken: (String) -> Boolean = { true },
    ): String? {
        if (handle == 0L) return null
        val parts = fitted(conversation, system, tools, limit, continuation)
        val tail = validatePromptTail(parts) ?: return null
        val prepared = prepareCache(parts, limit) ?: return null
        val length = prepared.length
        logPromptParts(parts, length, conversation, tools, limit)
        val flat = flattenTextParts(parts, length)
        val reused = reuseSharedCache(flat, length)
        if (!feedPromptParts(parts, flat, reused)) return null
        return generateTokens(tail, limit, sampling, onToken)
    }

    /** Last text part of the prompt, or null (with a log) when the prompt is empty. */
    private fun validatePromptTail(parts: List<Part>): Part.Tokens? {
        // The last part is always the generation prompt, so it is text and it is not empty.
        val tail = parts.lastOrNull() as? Part.Tokens
        if (tail == null || tail.ids.isEmpty()) {
            Log.status(TAG, "an empty prompt")
            return null
        }
        return tail
    }

    /** Cache capacity after growing, or null (with a log) when the prompt cannot fit. */
    private fun prepareCache(parts: List<Part>, limit: Int): PreparedPrompt? {
        val length = parts.sumOf { it.positions }
        // Grow the cache if this turn has outgrown it. Conversations start at the smallest
        // tier, so most never allocate more than 19 MB; the ones that keep going climb.
        val wantedPositions = length + limit + PromptConstants.PROMPT_SLACK_POSITIONS
        var capacity = MlNative.capacityGemma4(handle)
        if (capacity in 1 until wantedPositions) {
            val grown = MlNative.growGemma4(handle, wantedPositions)
            if (grown > capacity) {
                // `cachedIds` deliberately survives. Growing used to empty the cache, so this
                // cleared the record to match; it now copies the contents into the new arena and
                // keeps the position, so clearing here would throw away a cache that is still
                // there - which is exactly what made the precomputed prefix look useless: it
                // loaded, the first turn grew to make room for a reply, and the whole prefix
                // was then prefilled again anyway.
                capacity = grown
            }
        }
        if (capacity in 1 until (length + PromptConstants.PROMPT_SLACK_POSITIONS)) {
            Log.status(TAG, "a prompt of $length positions does not fit a $capacity cache")
            return null
        }
        return PreparedPrompt(length, capacity)
    }

    private data class PreparedPrompt(val length: Int, val capacity: Int)

    private fun logPromptParts(
        parts: List<Part>,
        length: Int,
        conversation: List<Turn>,
        tools: List<ToolDeclaration>,
        limit: Int
    ) {
        // What the model actually receives. A reply that reads as nonsense is either the model's
        // doing or the prompt's, and those are indistinguishable from the outside - so the
        // prompt is logged rather than guessed at. `fitted` truncates to make room for the
        // reply, and a prompt that lost its instructions to that truncation looks exactly like
        // a broken tokenizer.
        Log.status(
            TAG,
            "prompt $length positions in ${parts.size} parts, " +
                "${conversation.size} turns, ${tools.size} tools, reply budget $limit",
        )
        for ((index, part) in parts.withIndex()) {
            val what = when (part) {
                is Part.Tokens -> "text ${part.ids.size}: " +
                    decode(part.ids.take(PromptConstants.PROMPT_LOG_IDS).toIntArray())
                        .replace("\n", "\\n").take(PromptConstants.PROMPT_LOG_CHARS)
                is Part.Image -> "image ${part.positions}"
                is Part.Audio -> "audio ${part.positions}"
                // Untokenised text should never survive `fitted`, so seeing one here is itself
                // the bug rather than a case to render nicely.
                is Part.Text -> "UNTOKENISED ${part.text.take(PromptConstants.PROMPT_LOG_CHARS / 2)}"
            }
            Log.status(TAG, "  part $index  $what")
        }
    }

    private object PromptConstants {
        const val PROMPT_SLACK_POSITIONS = 2
        const val PROMPT_LOG_IDS = 40
        const val PROMPT_LOG_CHARS = 160
    }

    /** Flattened token ids for text-only prompts, or null when media forces re-feeding. */
    private fun flattenTextParts(parts: List<Part>, length: Int): IntArray? {
        // Text-only prompts only. An image or audio part contributes soft tokens rather than
        // ids, so deciding whether two of them are "the same" means comparing megabytes of
        // floats - and getting that wrong does not fail, it answers a conversation that never
        // happened. The multimodal path re-feeds, exactly as it did before.
        if (parts.any { it !is Part.Tokens }) return null
        return IntArray(length).also { out ->
            var at = 0
            for (part in parts) {
                val ids = (part as Part.Tokens).ids
                ids.copyInto(out, at)
                at += ids.size
            }
        }
    }

    /**
     * Reuse whatever of the cache this prompt shares with the last one, returning how many
     * leading positions were kept.
     *
     * A turn's prompt is nearly all of the previous turn's: the same system block, the same
     * tool declarations, the same history. Only the tail differs - the new user turn and the
     * generation prompt. The KV cache for the shared part is still in the arena and still
     * correct, because the tokens that produced it have not changed.
     */
    private fun reuseSharedCache(flat: IntArray?, length: Int): Int {
        if (flat == null) return 0
        var reused = 0
        val prior = cachedIds
        if (prior != null) {
            // Never reuse the final token: its logits are the prediction this turn needs, so it
            // has to be fed through `stepGemma4` rather than sat in the cache.
            val ceiling = minOf(prior.size, flat.size - 1)
            while (reused < ceiling && prior[reused] == flat[reused]) reused++
        }
        if (reused > 0 && MlNative.seekGemma4(handle, reused) == reused) {
            Log.status(TAG, "reused $reused of $length positions, feeding ${length - reused}")
        } else {
            reused = 0
            reset()
        }
        cachedIds = null
        return reused
    }

    /** Feeds every part but the final token into the cache; returns false on failure. */
    private fun feedPromptParts(
        parts: List<Part>,
        flat: IntArray?,
        reused: Int
    ): Boolean {
        // Everything but the very last token only fills the cache; its logits would be discarded.
        if (flat != null) {
            val feed = flat.copyOfRange(reused, flat.size - 1)
            if (feed.isNotEmpty() && MlNative.pushGemma4(handle, feed) < 0) return false
            // What the cache holds now. The reply's own tokens are appended as they are
            // generated, so the next turn matches against the whole exchange.
            cachedIds = flat.copyOfRange(0, flat.size - 1)
            return true
        }
        for ((index, part) in parts.withIndex()) {
            val fed = when (part) {
                is Part.Image -> MlNative.pushSoftGemma4(handle, part.soft)
                is Part.Audio -> MlNative.pushSoftGemma4(handle, part.soft)
                is Part.Tokens -> {
                    val ids = if (index == parts.lastIndex) part.ids.dropLast(1).toIntArray()
                    else part.ids
                    if (ids.isEmpty()) 0 else MlNative.pushGemma4(handle, ids)
                }
                is Part.Text -> -1
            }
            if (fed < 0) return false
        }
        return true
    }

    /** Generates up to [limit] tokens from the fed prompt, recording them in the cache. */
    @Suppress("LoopWithTooManyJumpStatements")
    private fun generateTokens(
        tail: Part.Tokens,
        limit: Int,
        sampling: Sampling?,
        onToken: (String) -> Boolean
    ): String? {
        val produced = ArrayList<Int>(limit)
        var next = tail.ids.last()
        val room = minOf(limit, remaining - 1)
        var step = 0
        while (step < room) {
            val token = nextToken(next, sampling, step) ?: break
            if (token in STOP) break
            produced.add(token)
            next = token
            if (!onToken(decode(produced.toIntArray()))) break
            step++
        }
        // The fed token and everything generated after it are in the cache too, so record them
        // and the next turn starts from the end of this reply rather than the start of the
        // conversation.
        cachedIds = cachedIds?.let { it + tail.ids.last() + produced.toIntArray() }
        return decode(produced.toIntArray())
    }

    /** One generation step: greedy native step, or a sampled draw from native candidates. */
    private fun nextToken(next: Int, sampling: Sampling?, step: Int): Int? {
        val token = when {
            sampling == null -> MlNative.stepGemma4(handle, next)
            sampling.topK in 1..GEMMA4_TOP_K_CANDIDATES -> {
                val before = MlNative.positionGemma4(handle)
                val candidates = MlNative.topKGemma4(handle, next, GEMMA4_TOP_K_CANDIDATES)
                when {
                    candidates != null -> sampleGemma4Candidates(candidates, sampling, step)
                    // Only when the failed call did not consume the token: feeding it again
                    // after it reached the cache would put it there twice.
                    MlNative.positionGemma4(handle) == before -> {
                        val logits = MlNative.logitsGemma4(handle, next) ?: return null
                        sampleGemma4Logits(logits, sampling, step)
                    }
                    else -> return null
                }
            }
            else -> {
                val logits = MlNative.logitsGemma4(handle, next) ?: return null
                sampleGemma4Logits(logits, sampling, step)
            }
        }
        return if (token < 0) null else token
    }

    /**
     * A prompt is not one string once an image is in it.
     *
     * An image has no token id to encode, so it cannot be spelled into the prompt and tokenised
     * with the rest. It arrives as the vision tower's `[n, 1536]` block and is pushed as an
     * embedding, which means the prompt has to be fed in order as a sequence of parts rather than
     * as one array.
     */
    internal sealed interface Part {
        /** How many positions in the KV cache this part occupies. */
        val positions: Int

        /** Text, before it has been tokenised. */
        data class Text(val text: String) : Part {
            override val positions: Int get() = 0
        }

        /** Text, tokenised. */
        data class Tokens(val ids: IntArray) : Part {
            override val positions: Int get() = ids.size

            override fun equals(other: Any?): Boolean =
                this === other || (other is Tokens && ids.contentEquals(other.ids))

            override fun hashCode(): Int = ids.contentHashCode()
        }

        /** One image's soft tokens, `n * 1536` from [Gemma4VisionHandle.encode]. */
        data class Image(val soft: FloatArray) : Part {
            override val positions: Int get() = soft.size / SOFT_TOKEN_WIDTH

            override fun equals(other: Any?): Boolean =
                this === other || (other is Image && soft.contentEquals(other.soft))

            override fun hashCode(): Int = soft.contentHashCode()
        }

        /**
         * One clip's soft tokens, `n * 1536` from [Gemma4AudioHandle.encode].
         *
         * Distinct from [Image] despite carrying the same shape and being pushed the same way,
         * because the two are bracketed by different markers and a clip in an image's brackets
         * is a prompt the model was never trained on. Collapsing them into one variant would
         * make that a one-character mistake.
         */
        data class Audio(val soft: FloatArray) : Part {
            override val positions: Int get() = soft.size / SOFT_TOKEN_WIDTH

            override fun equals(other: Any?): Boolean =
                this === other || (other is Audio && soft.contentEquals(other.soft))

            override fun hashCode(): Int = soft.contentHashCode()
        }
    }

    /**
     * [render]'s output, cut at every image and every clip.
     *
     * Media sits at the head of the turn that carries it, bracketed by [BOI]/[EOI] or
     * [BOA]/[EOA] the way the reference processor's `replace_image_token` and
     * `replace_audio_token` bracket their runs of placeholders. The placeholders themselves are
     * not emitted: their whole purpose is to reserve positions for the tower's rows, and the
     * rows are pushed directly.
     *
     * Images come before audio within a turn, which is the reference processor's own order.
     */
    private fun renderParts(
        conversation: List<Turn>,
        system: String?,
        tools: List<ToolDeclaration>,
        continuation: String = "",
    ): List<Part> {
        val builder = PromptBuilder()
        builder.appendHeader(system, declareTools(tools))
        for (turn in conversation) {
            builder.appendTurn(turn)
        }
        builder.appendModelOpen(continuation)
        return builder.build()
    }

    /** Incrementally assembles the prompt's text/media parts in order. */
    private class PromptBuilder {
        private val parts = ArrayList<Part>()
        private val text = StringBuilder()

        init {
            text.append("<bos>")
        }

        fun appendHeader(system: String?, declared: String) {
            if (!system.isNullOrBlank() || declared.isNotEmpty()) {
                text.append("<|turn>system\n")
                if (!system.isNullOrBlank()) text.append(system)
                text.append(declared)
                text.append("<turn|>\n")
            }
        }

        fun appendTurn(turn: Turn) {
            text.append("<|turn>").append(turn.role.marker).append('\n')
            for (image in turn.images) {
                if (image.isEmpty() || image.size % SOFT_TOKEN_WIDTH != 0) continue
                parts.add(Part.Text(text.toString() + BOI_MARKER))
                text.clear()
                parts.add(Part.Image(image))
                text.append(EOI_MARKER)
            }
            for (clip in turn.audio) {
                if (clip.isEmpty() || clip.size % SOFT_TOKEN_WIDTH != 0) continue
                parts.add(Part.Text(text.toString() + BOA_MARKER))
                text.clear()
                parts.add(Part.Audio(clip))
                text.append(EOA_MARKER)
            }
            text.append(turn.text)
            text.append("<turn|>\n")
        }

        fun appendModelOpen(continuation: String) {
            text.append("<|turn>model\n")
            // A tool call and its result belong **inside** the open model turn. Appending them
            // as a finished turn instead - `<|turn>model ... <turn|>` then a fresh `<|turn>model`
            // - made the model see its own turn ended and a new one begin, which reads as the
            // start of a conversation: it greeted instead of answering. Only the first message
            // showed it, because that is the one the system prompt forces a tool call on.
            text.append(continuation)
        }

        fun build(): List<Part> {
            if (text.isNotEmpty()) {
                parts.add(Part.Text(text.toString()))
                text.clear()
            }
            return parts
        }
    }

    override fun close() {
        val live = handle
        handle = 0L
        if (live != 0L) MlNative.destroyGemma4(live)
    }

    override fun toString(): String = "Gemma 4 E2B in $directory"

    /**
     * One message in a conversation.
     *
     * [images] are [Gemma4VisionHandle.encode]'s output and [audio] is
     * [Gemma4AudioHandle.encode]'s, one entry per item, each occupying `size / 1536` positions at
     * the head of the turn. Pass them unscaled - the reference scatters both towers' rows into
     * the decoder's embeddings as they are.
     */
    data class Turn(
        val role: Role,
        val text: String,
        val images: List<FloatArray> = emptyList(),
        val audio: List<FloatArray> = emptyList(),
    )

    /** Who spoke. Gemma's own names, not OpenAI's: the model turn is `model`, not `assistant`. */
    enum class Role(internal val marker: String) {
        USER("user"),
        MODEL("model"),
    }

    /** A tool the model may call, in the shape [declareTools] serialises. */
    data class ToolDeclaration(
        val name: String,
        val description: String,
        val parameters: List<Parameter>,
    ) {
        /** One argument. [type] is Gemma's spelling: `STRING`, `NUMBER`, `BOOLEAN`. */
        data class Parameter(
            val name: String,
            val description: String,
            val type: String,
            val required: Boolean = true,
        )
    }

    /**
     * The prompt [generate] would build, with audio already fitted into what the reply leaves.
     *
     * One budget for the window, computed once, with the reply reserve and the audio trim both
     * reading from it - so the two cannot each reserve the space the other reserved.
     *
     * The reply is reserved here rather than left to `remaining - 1` in [generate]. That
     * expression hands the reply every position the prompt did not use, so a turn that spends its
     * budget leaves nothing for the turn after it and the conversation dies.
     *
     * Derived from the prompt in hand on every call, never from a constant: the fixed cost is
     * dominated by the tool declarations and moves whenever those do.
     */
    private fun fitted(
        conversation: List<Turn>,
        system: String?,
        tools: List<ToolDeclaration>,
        limit: Int,
        continuation: String = "",
    ): List<Part> {
        val rendered = renderParts(conversation, system, tools, continuation).map { part ->
            when (part) {
                is Part.Text -> Part.Tokens(encodePrompt(part.text))
                is Part.Image, is Part.Audio, is Part.Tokens -> part
            }
        }
        val fixed = rendered.sumOf { if (it is Part.Audio) 0 else it.positions }
        return fitAudio(rendered, promptCeiling(limit) - fixed)
    }

    /**
     * Positions the prompt for [conversation] would occupy, so a caller can drop history before
     * [generate] refuses it.
     *
     * Exists because [generate] cannot fix an over-long conversation itself: trimming audio is a
     * decision about one attachment, but deciding which turns a user may lose is the caller's.
     * This is the same arithmetic [generate] performs, so the two cannot disagree about whether
     * something fits - compare it against [promptCeiling] with the same `limit`.
     *
     * Zero when the model is unavailable, which reads as "fits" rather than stranding a caller
     * in a loop dropping turns that would never have been sent.
     */
    fun positionsFor(
        conversation: List<Turn>,
        system: String?,
        tools: List<ToolDeclaration> = emptyList(),
        limit: Int = DEFAULT_REPLY,
    ): Int {
        if (handle == 0L) return 0
        return fitted(conversation, system, tools, limit).sumOf { it.positions }
    }

    /** A call the model asked for, as [parseToolCall] found it. */
    data class ToolCall(val name: String, val arguments: Map<String, String>)

    companion object {
        internal const val TAG = "Gemma4Handle"

        /**
         * Reply positions [generate] reserves unless a caller says otherwise.
         *
         * One constant so the reserve and anything measuring against it cannot drift apart.
         */
        const val DEFAULT_REPLY = 512

        /**
         * The most positions a prompt may occupy while still leaving [limit] for the reply.
         *
         * The guard's own bound and the reply reserve are not additive - they compete for the
         * same tail of the window, so the binding constraint is the larger of the two. `maxOf`
         * says that directly; subtracting both would give back one position fewer than the model
         * could have used, at every limit.
         */
        fun promptCeiling(limit: Int = DEFAULT_REPLY): Int =
            MAX_CONTEXT - maxOf(limit, MIN_REPLY_RESERVE)

        private const val MIN_REPLY_RESERVE = 3

        /**
         * [parts] with its audio trimmed to at most [budget] positions in total.
         *
         * A clip is trimmed from its end, so the model hears the start of a recording rather
         * than a window out of the middle of it, and earlier clips are served before later ones.
         *
         * A clip trimmed to nothing is dropped. The `<|audio>` brackets [renderParts] put around
         * it stay, because they are already tokenised into the neighbouring text by the time this
         * runs and two positions do not justify a second tokenisation pass to reclaim.
         *
         * Returns [parts] itself when there is no audio, so a text turn is untouched by all of
         * this - the budget can only ever take positions away from a clip, never from the prompt.
         *
         * Pure, and `internal` rather than private, so the unit tests can show the trim actually
         * firing. A guard that cannot fire is worse than no guard, and this one is invisible from
         * outside. The caller does the logging: keeping `android.util.Log` out of here is what
         * lets it be tested on the JVM at all.
         */
        internal fun fitAudio(parts: List<Part>, budget: Int): List<Part> {
            if (parts.none { it is Part.Audio }) return parts
            var left = budget.coerceAtLeast(0)
            val out = ArrayList<Part>(parts.size)
            for (part in parts) {
                if (part !is Part.Audio) {
                    out.add(part)
                    continue
                }
                val want = part.positions
                val keep = want.coerceAtMost(left)
                left -= keep
                when {
                    keep == want -> out.add(part)
                    keep > 0 -> out.add(Part.Audio(part.soft.copyOf(keep * SOFT_TOKEN_WIDTH)))
                }
            }
            return out
        }

        /** The decoder. Native checks its graph id, so a wrong file fails at load. */
        const val TEXT = "gemma4_text.maml"

        /** The two embedding tables, host-gathered. */
        const val EMBED = "gemma4_embed.maml"

        /** The GPU logits head (16 int8 chunks alone). Optional: native falls
         * back to the host head when it is absent, so old downloads without
         * it keep working — they just pay the ~2 s/token host SGEMV. */
        const val HEAD = "gemma4_head.maml"

        /** `scripts/ml/gemma4_tokenizer.py`'s output. */
        const val TOKENIZER = "gemma4_tokenizer.spm1"

        /** The files [inDirectory] needs, for a caller checking a download is complete. */
        val FILES: List<String> = listOf(TEXT, EMBED, TOKENIZER)

        /** The full set including the optional head, for a caller warming the download. */
        val FILES_WITH_HEAD: List<String> = listOf(TEXT, EMBED, HEAD, TOKENIZER)

        /** Positions the runtime offers. Mirrors `nets::gemma4::MAX_CONTEXT`. */
        const val MAX_CONTEXT = 16384

        /** Channels in one soft token, which is the decoder's `hidden_size`. */
        const val SOFT_TOKEN_WIDTH = 1536

        /**
         * The marker that opens an image, `boi_token` in the checkpoint's tokenizer config.
         *
         * The run of `<|image|>` placeholders the reference puts between this and [EOI_MARKER] is
         * **not** emitted here. Those exist to reserve positions for the tower's rows, and this
         * pushes the rows themselves - so spelling the placeholders as well would double every
         * image's cost in context and feed the model a run of embeddings it was never shown.
         */
        const val BOI_MARKER = "<|image>"

        /** The marker that closes an image, `eoi_token`. See [BOI_MARKER]. */
        const val EOI_MARKER = "<image|>"

        /**
         * The marker that opens a clip, `boa_token`, id 256000.
         *
         * Read off the tokenizer rather than assumed to mirror [BOI_MARKER]: the two families do
         * turn out to share a shape, `<|x>` opening and `<x|>` closing, but they interleave in
         * id order - image 255999, audio 256000, then `<|image|>` 258880, `<|audio|>` 258881,
         * `<image|>` 258882, `<audio|>` 258883 - so neither pair can be derived from the other.
         *
         * As with images, the `<|audio|>` placeholders the reference emits between these are
         * **not** written here: they exist to reserve positions for the tower's rows, and this
         * pushes the rows themselves.
         */
        const val BOA_MARKER = "<|audio>"

        /** The marker that closes a clip, `eoa_token`. See [BOA_MARKER]. */
        const val EOA_MARKER = "<audio|>"

        /**
         * Ids that end a reply, from the checkpoint's `generation_config.json`.
         *
         * Three, not one. `<eos>` is 1, but an instruction-tuned Gemma ends its reply with
         * `<turn|>` (106) and `<eos>` almost never appears - stopping only on 1 would let the
         * model run on emitting turn markers until the token budget stopped it instead.
         */
        val STOP = intArrayOf(1, 106, 50)

        /**
         * The markers [encodePrompt] honours, and which user text therefore cannot spell.
         *
         * Read off the checkpoint's `chat_template.jinja`, plus the image and audio brackets,
         * which are markers for the same reason the turn tags are: a user who could spell
         * [BOI_MARKER] could claim to have sent a picture.
         *
         * **Every marker [renderParts] emits must be in here.** A marker that is emitted but not
         * declared is encoded as literal characters instead of its own id, which is not an error
         * anywhere - the prompt still tokenises, the turn still runs, and the decoder simply
         * never sees the `boa` 256000 or `eoa` 258883 it was trained to bracket soft tokens
         * with. The audio pair was emitted for some hours before it was declared here.
         *
         * `internal` rather than private so `Gemma4MarkersTest` can enforce that coupling, which
         * nothing else does.
         */
        internal val MARKERS = arrayOf(
            "<bos>", "<eos>", "<|turn>", "<turn|>",
            "<|tool>", "<tool|>", "<|tool_call>", "<tool_call|>",
            "<|tool_response>", "<tool_response|>", "<|\"|>",
            BOI_MARKER, EOI_MARKER, BOA_MARKER, EOA_MARKER,
        )

        /**
         * A conversation as the prompt string Gemma was trained on.
         *
         * Ported from `chat_template.jinja` by rendering it and matching the output exactly.
         * The implementation lives in [renderGemma4Prompt]; this wrapper keeps existing
         * call sites working.
         */
        fun render(
            conversation: List<Turn>,
            system: String?,
            tools: List<ToolDeclaration> = emptyList(),
            continuation: String = "",
        ): String = renderGemma4Prompt(conversation, system, tools, continuation)

        /**
         * Tool declarations in the template's own syntax. Implemented in
         * [declareGemma4Tools]; this wrapper keeps existing call sites working.
         */
        fun declareTools(tools: List<ToolDeclaration>): String = declareGemma4Tools(tools)

        /**
         * The tool call in [reply], or null if there is not a complete one.
         * Implemented in [parseGemma4ToolCall]; this wrapper keeps existing call sites working.
         */
        fun parseToolCall(reply: String): ToolCall? = parseGemma4ToolCall(reply)

        /** A tool's result, in the shape the model expects to read back. */
        fun renderToolResponse(name: String, value: String): String =
            renderGemma4ToolResponse(name, value)

        /** Gemma's value delimiter, which is a token rather than a quotation mark. */
        private const val QUOTE = "<|\"|>"

        private fun quoted(value: String): String = QUOTE + value + QUOTE

        /**
         * The model in a folder on disk, which is the only place it lives.
         *
         * Construction never throws: a missing file or an unsupported device leaves
         * [isAvailable] false and the assistant simply off.
         */
        fun inDirectory(directory: File): Gemma4Handle = Gemma4Handle(directory)

        // Native handle construction (create, fd close, cache budget) lives in Gemma4Prefix.kt.
        private fun create(directory: File): Long = createGemma4Handle(directory)

        /** The baked prefix cache, beside the weights. Optional. */
        const val PREFIX_CACHE = "gemma4_prefix.kv"

        /** `GKV1` + positions + stride + a 32-byte digest. */
        internal const val HEADER = 4 + 4 + 4 + 32

        internal fun readInt(bytes: ByteArray, at: Int): Int =
            (bytes[at].toInt() and BYTE_MASK) or
                ((bytes[at + 1].toInt() and BYTE_MASK) shl BYTE_SHIFT_1) or
                ((bytes[at + 2].toInt() and BYTE_MASK) shl BYTE_SHIFT_2) or
                ((bytes[at + BYTE_OFFSET_3].toInt() and BYTE_MASK) shl BYTE_SHIFT_3)

        private const val BYTE_MASK = 0xFF
        private const val BYTE_SHIFT_1 = 8
        private const val BYTE_SHIFT_2 = 16
        private const val BYTE_SHIFT_3 = 24
        private const val BYTE_OFFSET_3 = 3

        /**
         * The digest `bake_gemma4_prefix` writes: FNV-1a over the token bytes, four times with
         * different seeds to fill 32 bytes.
         *
         * Not cryptographic, and does not need to be: it guards against a stale asset after
         * someone edits the prompt, not against an adversary who could replace the weights too.
         */
        internal fun digest(tokens: IntArray): ByteArray {
            val out = ByteArray(DIGEST_SIZE_BYTES)
            for (lane in 0 until DIGEST_LANES) {
                // `0x9e3779b9`, the **32-bit** golden ratio, because that is what
                // `bake_gemma4_prefix` seeds with. The 64-bit one was here first and matched on
                // lane 0 only - so the digest disagreed while the token count agreed, and a
                // perfectly good cache was rejected as "baked for a different prompt".
                var hash = GOLDEN_SEED xor (lane.toLong() * GOLDEN_RATIO_32)
                for (token in tokens) {
                    for (shift in 0 until BYTES_PER_INT) {
                        hash = hash xor
                            ((token ushr (shift * BYTE_SHIFT_1)).toLong() and BYTE_MASK_LONG)
                        hash *= FNV_PRIME
                    }
                }
                for (byte in 0 until BYTES_PER_LONG) {
                    out[lane * BYTES_PER_LONG + byte] =
                        (hash ushr (byte * BYTE_SHIFT_1)).toByte()
                }
            }
            return out
        }

        private const val DIGEST_SIZE_BYTES = 32
        private const val DIGEST_LANES = 4
        private const val BYTES_PER_INT = 4
        private const val BYTES_PER_LONG = 8
        private const val GOLDEN_SEED = -0x340d631b7bdddcdbL
        private const val GOLDEN_RATIO_32 = 0x9e3779b9L
        private const val FNV_PRIME = 0x100000001b3L

        private const val BYTE_MASK_LONG = 0xFFL

    }
}
