package com.vayunmathur.speech.platform

import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.json.JSONObject

/**
 * Decode-only Whisper tokenizer: token id to text, using the GPT-2 byte-level alphabet.
 *
 * Encoding is deliberately absent. The decoder prompt is nothing but special tokens
 * (`<|startoftranscript|><|en|><|transcribe|><|notimestamps|>`), so no text ever needs to be
 * BPE-encoded at runtime and `merges.txt` is not bundled.
 *
 * Special and timestamp tokens (id >= 50257) are not in `vocab.json` and are skipped, which is
 * what filters `<|endoftext|>` and the `<|x.xx|>` timestamps out of the returned text.
 */
class WhisperTokenizer private constructor(private val idToToken: Array<String?>) {

    /** Concatenate [ids] and map the byte-level alphabet back to real UTF-8 text. */
    fun decode(ids: List<Int>): String {
        val bytes = ByteArrayOutputStream(ids.size * 4)
        for (id in ids) {
            val piece = idToToken.getOrNull(id) ?: continue
            for (ch in piece) {
                val b = UNICODE_TO_BYTE[ch] ?: continue
                bytes.write(b)
            }
        }
        // Multi-byte characters are split across tokens, so decoding must happen once over the
        // whole byte stream rather than per token.
        return bytes.toString("UTF-8")
    }

    /** Shared constants and helpers for WhisperTokenizer. */
    companion object {
        /** Ids at or above this are special/timestamp tokens with no text form. */
        const val FIRST_SPECIAL_ID = 50257

        fun load(stream: InputStream): WhisperTokenizer {
            val json = JSONObject(stream.bufferedReader().readText())
            val table = arrayOfNulls<String>(FIRST_SPECIAL_ID)
            val keys = json.keys()
            while (keys.hasNext()) {
                val token = keys.next()
                val id = json.optInt(token, -1)
                if (id in 0 until FIRST_SPECIAL_ID) table[id] = token
            }
            return WhisperTokenizer(table)
        }

        /**
         * GPT-2's byte-to-printable-character map, inverted. Bytes that are already printable
         * keep their character; the rest are shifted into U+0100.. so a token string never
         * contains control characters or a literal space (space is U+0120).
         */
        private val UNICODE_TO_BYTE: Map<Char, Int> = buildMap {
            val printable = buildList {
                addAll('!'.code..'~'.code)
                addAll(0xA1..0xAC)
                addAll(0xAE..0xFF)
            }
            for (b in printable) put(b.toChar(), b)
            var n = 0
            for (b in 0..255) {
                if (b !in printable) {
                    put((256 + n).toChar(), b)
                    n++
                }
            }
        }
    }
}
