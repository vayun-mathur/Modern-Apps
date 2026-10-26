package com.vayunmathur.openassistant.util

import com.vayunmathur.library.log.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import kotlin.math.min

/**
 * Minimal, dependency-free **SentencePiece Unigram** tokenizer for the Gemma
 * SentencePiece model (`tokenizer.model`, ~4 MB, 256k vocab) that SigLIP2 uses
 * for its text tower.
 *
 * We need raw token ids to feed SigLIP2's text ONNX model with SigLIP2's own
 * vocabulary, so this is a standalone re-implementation of the
 * two pieces we actually need:
 *
 *  1. **Model parsing** ([load]): the `.model` file is a serialized
 *     `sentencepiece.ModelProto`. We hand-parse just the repeated `pieces`
 *     (field 1) — each a `SentencePiece{ piece=1:string, score=2:float,
 *     type=3:enum }` — because pulling in a protobuf runtime for three fields is
 *     overkill. Piece **index == token id**.
 *  2. **Encoding** ([encode]): Viterbi best-path Unigram segmentation over the
 *     normalized text, with **byte fallback** (Gemma enables it and ships all
 *     256 `<0xNN>` byte pieces) so every input is representable.
 *
 * ## SigLIP2 text preprocessing (flagged review risk)
 *
 * The exact normalization SigLIP2 applies is the highest-uncertainty part of the
 * photos→OpenAssistant embedding move (see the plan's "Key risks"). We apply the
 * documented SigLIP convention — lowercase, NFKC, whitespace-collapse, a
 * SentencePiece dummy-prefix space, and pad/truncate to [SEQ_LEN] with the pad
 * id — and keep every knob a named constant so it is easy to retune against the
 * reference `transformers` `Siglip2Processor` output. Image and text vectors
 * only line up if this matches, so this is the first thing to verify.
 */
class SentencePieceTokenizer private constructor(
    private val pieceToId: HashMap<String, Int>,
    private val scores: FloatArray,
    private val byteToId: IntArray, // 256 entries, -1 if absent
    private val maxPieceLen: Int,
    val unkId: Int,
    val padId: Int,
) {

    companion object {
        private const val TAG = "SentencePieceTokenizer"

        /** SentencePiece meta symbol standing in for a space (U+2581, "▁"). */
        private const val SPACE = '\u2581'

        /** SigLIP2 text tower fixed context length (padded/truncated). */
        const val SEQ_LEN = 64

        // SentencePiece piece types (sentencepiece_model.proto).
        private const val TYPE_NORMAL = 1
        private const val TYPE_UNKNOWN = 2
        private const val TYPE_CONTROL = 3
        private const val TYPE_USER_DEFINED = 4
        private const val TYPE_BYTE = 6

        private const val BYTE_ALPHABET = 256
        private const val GEMMA_DEFAULT_UNK_ID = 3
        private const val GEMMA_DEFAULT_PAD_ID = 0
        private const val MAX_PIECE_LEN_CAP = 48
        private const val HEX_RADIX = 16
        private const val BYTE_MASK = 0xFF
        private const val VARINT_CONTINUATION = 0x80
        private const val VARINT_PAYLOAD_MASK = 0x7F
        private const val VARINT_SHIFT = 7
        private const val WIRE_VARINT = 0
        private const val WIRE_FIXED64 = 1
        private const val WIRE_LENGTH_DELIMITED = 2
        private const val WIRE_FIXED32 = 5
        private const val FIELD_PIECE = 1
        private const val FIELD_SCORE = 2
        private const val FIELD_TYPE = 3
        private const val FLOAT_BYTES = 4
        private const val FIXED64_BYTES = 8
        private const val BYTE_PIECE_LEN = 6
        private const val BYTE_HEX_START = 3
        private const val BYTE_HEX_END = 5

        /**
         * Parse a Gemma `tokenizer.model` file, or null if it can't be read.
         * `padId`/`unkId` default to the Gemma special-token ids and are refined
         * from the parsed pieces where possible.
         */
        fun load(modelFile: File): SentencePieceTokenizer? {
            return try {
                val bytes = modelFile.readBytes()
                parse(bytes)
            } catch (expected: Exception) {
                Log.error(TAG, "Failed to load SentencePiece model ${modelFile.absolutePath}", expected)
                null
            }
        }

        private fun parse(data: ByteArray): SentencePieceTokenizer {
            val pieces = ArrayList<String>(256_000)
            val scoreList = ArrayList<Float>(256_000)
            val types = ArrayList<Int>(256_000)

            var pos = 0
            val end = data.size
            while (pos < end) {
                val (tag, p1) = readVarint(data, pos)
                pos = p1
                val field = (tag ushr 3).toInt()
                val wire = (tag and 0x7).toInt()
                if (field == 1 && wire == 2) {
                    // pieces: length-delimited SentencePiece message.
                    val (len, p2) = readVarint(data, pos)
                    pos = p2
                    val msgEnd = pos + len.toInt()
                    parsePiece(data, pos, msgEnd, pieces, scoreList, types)
                    pos = msgEnd
                } else {
                    pos = skipField(data, pos, wire)
                }
            }

            val n = pieces.size
            val scoresArr = FloatArray(n) { scoreList[it] }
            val pieceToId = HashMap<String, Int>(n * 2)
            val byteToId = IntArray(BYTE_ALPHABET) { -1 }
            var unkId = GEMMA_DEFAULT_UNK_ID
            var padId = GEMMA_DEFAULT_PAD_ID
            var maxLen = 1

            for (id in 0 until n) {
                val piece = pieces[id]
                when (types[id]) {
                    TYPE_UNKNOWN -> unkId = id
                    TYPE_BYTE -> {
                        val b = parseBytePiece(piece)
                        if (b in 0 until BYTE_ALPHABET) byteToId[b] = id
                    }
                    TYPE_CONTROL -> {
                        if (piece == "<pad>") padId = id
                    }
                    TYPE_NORMAL, TYPE_USER_DEFINED -> {
                        pieceToId[piece] = id
                        val cpLen = piece.codePointCount(0, piece.length)
                        if (cpLen > maxLen) maxLen = cpLen
                    }
                }
            }
            // Cap the DP window; real Gemma pieces are short subwords.
            maxLen = min(maxLen, MAX_PIECE_LEN_CAP)
            Log.status(
                TAG,
                "Loaded SentencePiece: $n pieces, unkId=$unkId, padId=$padId, maxPieceLen=$maxLen",
            )
            return SentencePieceTokenizer(pieceToId, scoresArr, byteToId, maxLen, unkId, padId)
        }

        private fun parsePiece(
            data: ByteArray,
            start: Int,
            end: Int,
            pieces: ArrayList<String>,
            scores: ArrayList<Float>,
            types: ArrayList<Int>,
        ) {
            var pos = start
            var piece = ""
            var score = 0f
            var type = TYPE_NORMAL
            while (pos < end) {
                val (tag, p1) = readVarint(data, pos)
                pos = p1
                val field = (tag ushr VARINT_SHIFT).toInt()
                val wire = (tag and VARINT_PAYLOAD_MASK.toLong()).toInt()
                when {
                    field == FIELD_PIECE && wire == WIRE_LENGTH_DELIMITED -> {
                        val (len, p2) = readVarint(data, pos)
                        pos = p2
                        val l = len.toInt()
                        piece = String(data, pos, l, Charsets.UTF_8)
                        pos += l
                    }
                    field == FIELD_SCORE && wire == WIRE_FIXED32 -> {
                        score = ByteBuffer.wrap(data, pos, FLOAT_BYTES)
                            .order(ByteOrder.LITTLE_ENDIAN).float
                        pos += FLOAT_BYTES
                    }
                    field == FIELD_TYPE && wire == WIRE_VARINT -> {
                        val (t, p2) = readVarint(data, pos)
                        pos = p2
                        type = t.toInt()
                    }
                    else -> pos = skipField(data, pos, wire)
                }
            }
            pieces.add(piece)
            scores.add(score)
            types.add(type)
        }

        /** "<0x1F>" -> 31, else -1. */
        private fun parseBytePiece(piece: String): Int {
            if (piece.length == BYTE_PIECE_LEN &&
                piece.startsWith("<0x") &&
                piece.endsWith(">")
            ) {
                return piece.substring(BYTE_HEX_START, BYTE_HEX_END)
                    .toIntOrNull(HEX_RADIX) ?: -1
            }
            return -1
        }

        private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int> {
            var result = 0L
            var shift = 0
            var pos = start
            while (true) {
                val b = data[pos].toInt() and BYTE_MASK
                pos++
                result = result or ((b and VARINT_PAYLOAD_MASK).toLong() shl shift)
                if (b and VARINT_CONTINUATION == 0) break
                shift += VARINT_SHIFT
            }
            return result to pos
        }

        private fun skipField(data: ByteArray, start: Int, wire: Int): Int {
            var pos = start
            when (wire) {
                WIRE_VARINT -> pos = readVarint(data, pos).second
                WIRE_FIXED64 -> pos += FIXED64_BYTES
                WIRE_LENGTH_DELIMITED -> {
                    val (len, p) = readVarint(data, pos)
                    pos = p + len.toInt()
                }
                WIRE_FIXED32 -> pos += FLOAT_BYTES
                else -> throw IllegalStateException("Unsupported wire type $wire")
            }
            return pos
        }
    }

    /**
     * Encode [text] into a fixed [SEQ_LEN]-long id array (SigLIP2 text input),
     * padded with [padId] and truncated as needed.
     */
    fun encode(text: String): IntArray {
        val ids = encodeVariable(text)
        val out = IntArray(SEQ_LEN) { padId }
        val take = min(ids.size, SEQ_LEN)
        for (i in 0 until take) out[i] = ids[i]
        return out
    }

    /** Viterbi Unigram segmentation of the normalized text (no padding). */
    private fun encodeVariable(text: String): IntArray {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return IntArray(0)

        // Work over Unicode code points so multi-byte chars index cleanly.
        val cps = normalized.codePoints().toArray()
        val n = cps.size
        val bestScore = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        bestScore[0] = 0.0
        val prev = IntArray(n + 1) { -1 }
        val stepIds = arrayOfNulls<IntArray>(n + 1)

        for (i in 0 until n) {
            if (bestScore[i] == Double.NEGATIVE_INFINITY) continue
            relaxPieces(cps, n, i, bestScore, prev, stepIds)
            relaxByteFallback(cps, i, bestScore, prev, stepIds)
        }

        if (bestScore[n] == Double.NEGATIVE_INFINITY) {
            // Should not happen with byte fallback; degrade to a single unk.
            return intArrayOf(unkId)
        }

        // Backtrack, collecting ids, then reverse.
        val rev = ArrayList<Int>()
        var pos = n
        while (pos > 0) {
            val ids = stepIds[pos] ?: intArrayOf(unkId)
            for (k in ids.indices.reversed()) rev.add(ids[k])
            pos = prev[pos]
        }
        rev.reverse()
        return rev.toIntArray()
    }

    private fun relaxPieces(
        cps: IntArray,
        n: Int,
        i: Int,
        bestScore: DoubleArray,
        prev: IntArray,
        stepIds: Array<IntArray?>,
    ) {
        // (a) Try every vocab piece starting at i.
        val maxL = min(maxPieceLen, n - i)
        val sb = StringBuilder()
        for (l in 1..maxL) {
            sb.appendCodePoint(cps[i + l - 1])
            val id = pieceToId[sb.toString()] ?: continue
            val sc = bestScore[i] + scores[id]
            if (sc > bestScore[i + l]) {
                bestScore[i + l] = sc
                prev[i + l] = i
                stepIds[i + l] = intArrayOf(id)
            }
        }
    }

    private fun relaxByteFallback(
        cps: IntArray,
        i: Int,
        bestScore: DoubleArray,
        prev: IntArray,
        stepIds: Array<IntArray?>,
    ) {
        // (b) Byte fallback for the single code point at i, so every
        // position stays reachable even with no matching piece.
        val ch = String(Character.toChars(cps[i]))
        val chBytes = ch.toByteArray(Charsets.UTF_8)
        val fallbackIds = IntArray(chBytes.size)
        var fbScore = bestScore[i]
        for (k in chBytes.indices) {
            val bid = byteToId[chBytes[k].toInt() and BYTE_MASK]
            if (bid < 0) return
            fallbackIds[k] = bid
            fbScore += scores[bid]
        }
        if (fbScore > bestScore[i + 1]) {
            bestScore[i + 1] = fbScore
            prev[i + 1] = i
            stepIds[i + 1] = fallbackIds
        }
    }

    /**
     * SigLIP text normalization: lowercase, NFKC, collapse whitespace, then the
     * SentencePiece dummy-prefix space with spaces mapped to [SPACE] (▁).
     */
    private fun normalize(text: String): String {
        val lowered = text.lowercase()
        val nfkc = Normalizer.normalize(lowered, Normalizer.Form.NFKC)
        val collapsed = nfkc.trim().replace(Regex("\\s+"), " ")
        if (collapsed.isEmpty()) return ""
        // Dummy prefix + space→▁ (SentencePiece convention).
        return (" $collapsed").replace(' ', SPACE)
    }
}
