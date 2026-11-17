package com.vayunmathur.photos.platform

import android.content.Context

/**
 * The CLIP text tokenizer, ported to Kotlin from OpenAI's reference
 * `simple_tokenizer.py`. MobileCLIP uses the exact same tokenizer as CLIP, so
 * the query text must be encoded the same way the text encoder was trained.
 *
 * It is a **byte-level BPE**:
 *  1. Text is lower-cased and whitespace-collapsed, then split into rough word
 *     pieces by [PATTERN] (the same regex CLIP uses).
 *  2. Every piece is turned into a string of "visible" unicode chars via
 *     [byteEncoder] (a reversible bytes→unicode map so no byte is unprintable).
 *  3. Byte-Pair-Encoding merges are applied greedily by rank ([bpeRanks]),
 *     following the merges list bundled as [MERGES_ASSET].
 *  4. Each resulting sub-word maps to an integer id via [encoder].
 *
 * [tokenize] wraps the ids with the start/end tokens and pads/truncates to the
 * fixed 77-token context the model expects. Padding is 0 (CLIP convention); the
 * text transformer is causal and pools at the end-of-text position, so tokens
 * after it — including padding — never affect the produced embedding.
 *
 * Vocab layout (total 49408):
 *  - 256 base byte chars, then 256 with the `</w>` word-end marker,
 *  - then one entry per BPE merge,
 *  - then the two specials `<|startoftext|>` (49406) and `<|endoftext|>` (49407).
 *
 * The merges file [MERGES_ASSET] (`bpe_simple_vocab_16e6.txt`, the OpenAI CLIP
 * vocab, MIT) ships as an asset. Its first line is a `#version` header (skipped);
 * the rest are space-separated merge pairs, most-frequent first. If it is missing,
 * [load] returns null and semantic search is simply disabled (no crash).
 */
class ClipTokenizer private constructor(
    private val encoder: Map<String, Int>,
    private val bpeRanks: Map<Pair<String, String>, Int>,
    private val byteEncoder: Map<Int, String>,
) {
    private val cache = HashMap<String, String>()

    /** Encode [text] into a fixed-length [CONTEXT_LENGTH] array of token ids. */
    fun tokenize(text: String): IntArray {
        val tokens = ArrayList<Int>()
        tokens.add(START_TOKEN)
        for (id in encode(text)) {
            tokens.add(id)
            if (tokens.size >= CONTEXT_LENGTH - 1) break // leave room for end token
        }
        tokens.add(END_TOKEN)

        val out = IntArray(CONTEXT_LENGTH) // zero-padded
        for (i in tokens.indices) out[i] = tokens[i]
        return out
    }

    private fun encode(text: String): List<Int> {
        val cleaned = whitespaceClean(basicClean(text)).lowercase()
        val ids = ArrayList<Int>()
        val matcher = PATTERN.matcher(cleaned)
        while (matcher.find()) {
            val piece = matcher.group()
            // Map the piece's UTF-8 bytes to reversible unicode chars.
            val sb = StringBuilder()
            for (b in piece.toByteArray(Charsets.UTF_8)) {
                sb.append(byteEncoder[b.toInt() and BYTE_MASK] ?: continue)
            }
            val token = sb.toString()
            if (token.isEmpty()) continue
            for (part in bpe(token).split(' ')) {
                encoder[part]?.let { ids.add(it) }
            }
        }
        return ids
    }

    /** Greedy BPE merge of a single byte-mapped [token]. */
    private fun bpe(token: String): String {
        cache[token]?.let { return it }

        // Start with each char as its own symbol; the last carries `</w>`.
        val word = ArrayList<String>(token.length)
        for (i in token.indices) {
            word.add(if (i == token.length - 1) token[i] + "</w>" else token[i].toString())
        }
        if (word.size == 1) return (token + "</w>").also { cache[token] = it }

        var current = word
        while (current.size > 1) {
            val (first, second) = bestMergePair(current) ?: break
            current = mergePairOccurrences(current, first, second)
        }

        return current.joinToString(" ").also { cache[token] = it }
    }

    /** Adjacent pair with the best (lowest) merge rank, or null if none merges. */
    private fun bestMergePair(word: List<String>): Pair<String, String>? {
        var bestRank = Int.MAX_VALUE
        var bestPair: Pair<String, String>? = null
        for (i in 0 until word.size - 1) {
            val pair = word[i] to word[i + 1]
            val rank = bpeRanks[pair] ?: continue
            if (rank < bestRank) {
                bestRank = rank
                bestPair = pair
            }
        }
        return bestPair
    }

    /** Merge every non-overlapping occurrence of ([first], [second]) in [word]. */
    private fun mergePairOccurrences(word: List<String>, first: String, second: String): ArrayList<String> {
        val merged = ArrayList<String>(word.size)
        var i = 0
        while (i < word.size) {
            if (i < word.size - 1 && word[i] == first && word[i + 1] == second) {
                merged.add(first + second)
                i += 2
            } else {
                merged.add(word[i])
                i += 1
            }
        }
        return merged
    }

    /** Shared constants and helpers for ClipTokenizer. */
    companion object {
        const val MERGES_ASSET = "clip/bpe_simple_vocab_16e6.txt"

        /** Fixed context window CLIP/MobileCLIP text encoders expect. */
        const val CONTEXT_LENGTH = 77

        /** Vocab ids of the special tokens (last two entries of the vocab). */
        const val START_TOKEN = 49406 // <|startoftext|>
        const val END_TOKEN = 49407 // <|endoftext|>

        private const val BYTE_ALPHABET = 256
        private const val BYTE_MAX = BYTE_ALPHABET - 1
        private const val BYTE_MASK = 0xFF
        // Number of merge lines to read (matches CLIP: 49152 - 256 - 2 + 1),
        // taken after skipping the first header line.
        private const val MERGE_COUNT = 49152 - BYTE_ALPHABET - 2 + 1

        // CLIP's word-splitting regex. \p{L}=letters, \p{N}=numbers.
        private val PATTERN = java.util.regex.Pattern.compile(
            "<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|\\p{L}+|\\p{N}|[^\\s\\p{L}\\p{N}]+",
            java.util.regex.Pattern.CASE_INSENSITIVE,
        )

        /**
         * Build a tokenizer from the bundled merges asset, or return null if the
         * asset is missing/unreadable (semantic search then stays disabled).
         */
        fun load(context: Context): ClipTokenizer? {
            return try {
                val lines = context.assets.open(MERGES_ASSET).use { raw ->
                    raw.bufferedReader(Charsets.UTF_8).readLines()
                }
                // Skip the header line, then take MERGE_COUNT merge rules.
                val mergeLines = lines.drop(1).take(MERGE_COUNT - 1)
                val merges = mergeLines.mapNotNull { line ->
                    val parts = line.split(' ')
                    if (parts.size == 2) parts[0] to parts[1] else null
                }

                val byteEncoder = bytesToUnicode()
                // Vocab in exact CLIP order: base chars, base+</w>, merges, specials.
                val vocab = ArrayList<String>(49408)
                vocab.addAll(byteEncoder.orderedValues)
                vocab.addAll(byteEncoder.orderedValues.map { it + "</w>" })
                for ((a, b) in merges) vocab.add(a + b)
                vocab.add("<|startoftext|>")
                vocab.add("<|endoftext|>")

                val encoder = HashMap<String, Int>(vocab.size * 2)
                for (i in vocab.indices) encoder[vocab[i]] = i
                val bpeRanks = HashMap<Pair<String, String>, Int>(merges.size * 2)
                for (i in merges.indices) bpeRanks[merges[i]] = i

                ClipTokenizer(encoder, bpeRanks, byteEncoder.byteToStr)
            } catch (_: Exception) {
                null
            }
        }

        private class ByteUnicode(
            val byteToStr: Map<Int, String>,
            val orderedValues: List<String>,
        )

        /**
         * Reversible bytes→unicode table (CLIP's `bytes_to_unicode`): the 188
         * printable byte values map to themselves; the remaining 68 map to fresh
         * code points 256+ so every byte becomes a single visible char.
         * [orderedValues] preserves the exact iteration order CLIP uses to build
         * its vocab.
         */
        private fun bytesToUnicode(): ByteUnicode {
            val bs = ArrayList<Int>()
            for (i in '!'.code..'~'.code) bs.add(i)
            for (i in '\u00A1'.code..'\u00AC'.code) bs.add(i)
            for (i in '\u00AE'.code..'\u00FF'.code) bs.add(i)
            val cs = ArrayList<Int>(bs)
            var n = 0
            for (b in 0..BYTE_MAX) {
                if (b !in bs) {
                    bs.add(b)
                    cs.add(BYTE_ALPHABET + n)
                    n++
                }
            }
            val byteToStr = HashMap<Int, String>(BYTE_ALPHABET)
            val ordered = ArrayList<String>(BYTE_ALPHABET)
            for (i in bs.indices) {
                val s = cs[i].toChar().toString()
                byteToStr[bs[i]] = s
                ordered.add(s)
            }
            return ByteUnicode(byteToStr, ordered)
        }

        private fun basicClean(text: String): String = text.trim()

        private fun whitespaceClean(text: String): String =
            text.replace(Regex("\\s+"), " ").trim()
    }
}
