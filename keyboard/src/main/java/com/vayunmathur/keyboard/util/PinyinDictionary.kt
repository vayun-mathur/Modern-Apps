package com.vayunmathur.keyboard.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pinyin syllable → Han characters, backing the Chinese layouts' candidate strip.
 *
 * Built by `scripts/keyboard/generate_pinyin.py` from Unicode's Han readings, in two
 * variants (`pinyin_sc` / `pinyin_tc`) that differ only in how the candidates for a syllable
 * are ordered — a traditional-script writer offered 国 before 國 has to skip a character they
 * will never want.
 *
 * The index is toneless, and `ü` is folded into `u` (typing `lu` offers both 路 and 绿), which
 * is what every phone pinyin keyboard does: there are no tone keys to type with.
 */
class PinyinDictionary private constructor(
    /** Syllable → its characters, best candidate first. Kept sorted for the prefix scan. */
    private val syllables: List<String>,
    private val characters: List<String>,
) {
    /**
     * Candidates for a partly- or fully-typed spelling. An exact syllable contributes all of
     * its characters; longer syllables that merely start with the spelling contribute their
     * best few, so typing `zh` still suggests something without burying the exact match.
     */
    fun candidates(spelling: String, limit: Int = 40): List<String> {
        if (spelling.isEmpty()) return emptyList()
        val out = ArrayList<String>(limit)
        var i = lowerBound(spelling)
        val exact = if (i < syllables.size && syllables[i] == spelling) i else -1
        if (exact >= 0) {
            characters[exact].forEach { if (out.size < limit) out.add(it.toString()) }
        }
        while (i < syllables.size && syllables[i].startsWith(spelling) && out.size < limit) {
            if (i != exact) {
                characters[i].take(PREFIX_CANDIDATES).forEach {
                    if (out.size < limit) out.add(it.toString())
                }
            }
            i++
        }
        return out
    }

    /** True if [spelling] is a complete syllable, which is what lets 注音 end one. */
    fun isSyllable(spelling: String): Boolean {
        val i = lowerBound(spelling)
        return i < syllables.size && syllables[i] == spelling
    }

    private fun lowerBound(spelling: String): Int {
        var lo = 0
        var hi = syllables.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (syllables[mid] < spelling) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Shared constants and helpers for PinyinDictionary. */
    companion object {
        /** How many characters a merely-prefix-matching syllable may contribute. */
        private const val PREFIX_CANDIDATES = 3

        val EMPTY = PinyinDictionary(emptyList(), emptyList())

        /** Loads `dict/[asset].txt` off the main thread. */
        suspend fun load(context: Context, asset: String): PinyinDictionary =
            withContext(Dispatchers.IO) {
                val syllables = ArrayList<String>(512)
                val characters = ArrayList<String>(512)
                runCatching {
                    context.assets.open("dict/$asset.txt").bufferedReader().useLines { lines ->
                        for (raw in lines) {
                            parseSyllableLine(raw)?.let { (syllable, chars) ->
                                syllables.add(syllable)
                                characters.add(chars)
                            }
                        }
                    }
                }
                PinyinDictionary(syllables, characters)
            }

        /** Split one `syllable<TAB>chars` line, or null for blanks/comments/malformed rows. */
        private fun parseSyllableLine(raw: String): Pair<String, String>? {
            if (raw.isEmpty() || raw.startsWith("#")) return null
            val tab = raw.indexOf('\t')
            if (tab <= 0) return null
            return raw.substring(0, tab) to raw.substring(tab + 1)
        }

        /** Reads `dict/bopomofo.txt`: 注音 spelling → the pinyin syllable it stands for. */
        suspend fun loadBopomofo(context: Context): Map<String, String> =
            withContext(Dispatchers.IO) {
                val map = HashMap<String, String>(512)
                runCatching {
                    context.assets.open("dict/bopomofo.txt").bufferedReader().useLines { lines ->
                        for (raw in lines) {
                            if (raw.isEmpty() || raw.startsWith("#")) continue
                            val tab = raw.indexOf('\t')
                            if (tab > 0) map[raw.substring(0, tab)] = raw.substring(tab + 1)
                        }
                    }
                }
                map
            }

        /** Builds an index directly, for tests that shouldn't need the 60k-character asset. */
        internal fun fromEntries(entries: List<Pair<String, String>>): PinyinDictionary {
            val sorted = entries.sortedBy { it.first }
            return PinyinDictionary(sorted.map { it.first }, sorted.map { it.second })
        }
    }
}
