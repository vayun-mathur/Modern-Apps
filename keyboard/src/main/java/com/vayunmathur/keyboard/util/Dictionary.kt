package com.vayunmathur.keyboard.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Offline word dictionary backing the suggestion strip and autocorrect.
 *
 * Words are kept lowercase, sorted alphabetically in a parallel [words]/[freqs] pair so
 * prefix lookups are a binary search + short forward scan (a lightweight stand-in for a
 * trie that also gives O(log n) `contains`). Ranking is purely by frequency. The whole
 * thing is loaded once, off the main thread, from `assets/dict/words_en.txt`.
 *
 * A frequency of 0 means **known but never offered**: [contains] accepts the word, so it
 * is not treated as a misspelling and [autocorrect] will not rewrite it, but it is never
 * returned by [suggestions] and never proposed as a correction of something else. The
 * generator files profanity this way — dropping those words outright would make the
 * keyboard quietly "correct" them into whatever else is within one edit.
 */
class Dictionary private constructor(
    private val words: List<String>,
    private val freqs: IntArray,
    /**
     * Indices into [words] bucketed by (word length, first character), and the same by
     * second character — the two indexes [autocorrect] intersects its candidates from.
     * Only offerable words (frequency > 0) are indexed, since the others can never be
     * produced as a correction anyway. Keyed by [bucketKey].
     */
    private val byFirst: Map<Int, IntArray>,
    private val bySecond: Map<Int, IntArray>,
) {
    /** True if [word] (any case) is a known dictionary word. */
    fun contains(word: String): Boolean {
        if (word.isEmpty()) return false
        return indexOf(word.lowercase()) >= 0
    }

    /**
     * Up to [limit] completions of [prefix], most frequent first, capitalized to match how
     * the user typed the prefix. Returns empty for a blank prefix.
     */
    fun suggestions(prefix: String, limit: Int = 3): List<String> {
        if (prefix.isBlank() || limit <= 0) return emptyList()
        val lower = prefix.lowercase()

        // Runs on every keystroke, and a one-letter prefix matches thousands of words, so
        // this keeps a fixed top-[limit] instead of collecting every match and sorting it.
        val bestIdx = IntArray(limit) { -1 }
        val bestFreq = IntArray(limit)
        var i = lowerBound(lower)
        while (i < words.size && words[i].startsWith(lower)) {
            offer(bestIdx, bestFreq, i, freqs[i])
            i++
        }

        return bestIdx.filter { it >= 0 }.map { capitalizeLike(prefix, words[it]) }
    }

    /**
     * A best correction for a misspelled [word], or null if none is confident. Considers
     * dictionary words within edit distance 1, picking the most frequent.
     */
    fun autocorrect(word: String): String? {
        if (word.length < 2) return null
        val lower = word.lowercase()
        if (indexOf(lower) >= 0) return null

        val len = lower.length
        val first = lower[0]
        val second = lower[1]

        var best = -1
        var bestFreq = 0 // 0 also excludes never-offered words without a second check.
        fun scan(bucket: IntArray?) {
            if (bucket == null) return
            for (idx in bucket) {
                if (isBetterCandidate(lower, idx, bestFreq)) {
                    bestFreq = freqs[idx]
                    best = idx
                }
            }
        }

        // Bucketing by length alone meant reading the three fattest buckets in the list —
        // for a 7-letter word that is 17k of the 42k words, on the main thread, while the
        // user waits for the space they just pressed. Pinning a character position as well
        // cuts that by six to nine times (2.7k candidates for a 7-letter word, 300 for a
        // 3-letter one), and these seven buckets provably still contain every word within
        // one edit of `lower` (call it `w`, and note `len` >= 2 here):
        //
        //  - Same length: one substitution, or one transposition of adjacent characters.
        //    Either it leaves position 0 alone, so w[0] == first; or it is at position 0,
        //    where a substitution leaves w[1] == second and a transposition puts `second`
        //    itself at w[0].
        //  - One shorter (the typed word has the extra character): dropping that character
        //    leaves w[0] == first, unless what was dropped *was* the first, which leaves
        //    w[0] == second.
        //  - One longer (the candidate has the extra character): removing it leaves
        //    w[0] == first, unless it was the candidate's own first character, which
        //    leaves w[1] == first.
        //
        // Buckets overlap when first == second; re-scanning one is harmless (picking the
        // best is idempotent) but pointless, so those lookups are skipped.
        scan(byFirst[bucketKey(len, first)])
        scan(bySecond[bucketKey(len, second)])
        scan(byFirst[bucketKey(len - 1, first)])
        if (second != first) {
            scan(byFirst[bucketKey(len, second)])
            scan(byFirst[bucketKey(len - 1, second)])
        }
        scan(byFirst[bucketKey(len + 1, first)])
        scan(bySecond[bucketKey(len + 1, first)])

        return if (best >= 0) capitalizeLike(word, words[best]) else null
    }

    // --- internals ---

    /**
     * True when candidate [idx] should replace the current best: more frequent, more frequent
     * than the running floor (which also excludes never-offered freq-0 words), and within one
     * edit of [lower]. Extracted so the bucket scan is a single jump per loop.
     */
    private fun isBetterCandidate(lower: String, idx: Int, bestFreq: Int): Boolean =
        freqs[idx] > bestFreq && withinEditDistance1(lower, words[idx])

    /**
     * Insert [idx]/[freq] into a descending fixed-size top-k, dropping the smallest. Words
     * at frequency 0 are never offered, so they are refused outright.
     */
    private fun offer(bestIdx: IntArray, bestFreq: IntArray, idx: Int, freq: Int) {
        if (freq <= 0) return
        var pos = bestIdx.size
        while (pos > 0 && (bestIdx[pos - 1] < 0 || bestFreq[pos - 1] < freq)) pos--
        if (pos == bestIdx.size) return
        for (shift in bestIdx.size - 1 downTo pos + 1) {
            bestIdx[shift] = bestIdx[shift - 1]
            bestFreq[shift] = bestFreq[shift - 1]
        }
        bestIdx[pos] = idx
        bestFreq[pos] = freq
    }

    /** Index of an exact match, or -1. */
    private fun indexOf(w: String): Int {
        var lo = 0
        var hi = words.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = words[mid].compareTo(w)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    /** First index whose word is >= [w] (binary search lower bound). */
    private fun lowerBound(w: String): Int {
        var lo = 0
        var hi = words.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (words[mid] < w) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Shared constants and helpers for Dictionary. */
    companion object {
        /** An empty dictionary used before loading completes. */
        val EMPTY = Dictionary(emptyList(), IntArray(0), emptyMap(), emptyMap())

        /** Bits the word length is shifted to make room for the char code in a bucket key. */
        private const val LENGTH_SHIFT_BITS = 16

        /**
         * Key for the [byFirst]/[bySecond] buckets: a word length paired with the
         * character at the position that index pins.
         */
        private fun bucketKey(length: Int, c: Char): Int =
            (length shl LENGTH_SHIFT_BITS) or c.code

        /** Load and index the bundled word list off the main thread. */
        suspend fun load(context: Context): Dictionary = withContext(Dispatchers.IO) {
            build(readAsset(context))
        }

        /**
         * Index an in-memory word list. Exists so the ranking, edit-distance and
         * never-offered rules can be unit tested without an Android [Context] or the
         * 42k-entry asset.
         */
        internal fun fromEntries(entries: List<Pair<String, Int>>): Dictionary = build(entries)

        private fun readAsset(context: Context): List<Pair<String, Int>> {
            val entries = ArrayList<Pair<String, Int>>()
            runCatching {
                context.assets.open("dict/words_en.txt").bufferedReader().useLines { lines ->
                    // Fallback frequency = position from the top (file is ordered most-common first),
                    // so common words rank above rarer ones even without an explicit column.
                    var order = 0
                    for (raw in lines) {
                        parseWordLine(raw, order)?.let {
                            entries.add(it)
                            order++
                        }
                    }
                }
            }
            return entries
        }

        /**
         * Parse one `word[\tfreq]` line, or null when it is blank, a comment or unusable.
         * [order] is the count of valid words so far, which is what the fallback frequency
         * derives from — so it only advances on lines this accepts.
         */
        private fun parseWordLine(raw: String, order: Int): Pair<String, Int>? {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return null
            val parts = line.split('\t')
            val word = parts[0].trim().lowercase()
            if (word.isEmpty()) return null
            val freq = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: (1_000_000 - order)
            return word to freq
        }

        private fun build(entries: List<Pair<String, Int>>): Dictionary {
            // De-dup keeping the highest frequency, then sort alphabetically for binary search.
            val byWord = LinkedHashMap<String, Int>()
            for ((w, f) in entries) {
                val prev = byWord[w]
                if (prev == null || f > prev) byWord[w] = f
            }
            val sorted = byWord.entries.sortedBy { it.key }
            val words = sorted.map { it.key }
            val freqs = IntArray(sorted.size) { sorted[it].value }

            // Correction candidates, indexed on the two character positions autocorrect
            // pins. Words at frequency 0 are left out: they are never offered, so they can
            // never be the answer, and skipping them shrinks every bucket.
            val byFirst = HashMap<Int, MutableList<Int>>()
            val bySecond = HashMap<Int, MutableList<Int>>()
            for (i in words.indices) {
                if (freqs[i] <= 0) continue
                val w = words[i]
                byFirst.getOrPut(bucketKey(w.length, w[0])) { ArrayList() }.add(i)
                if (w.length > 1) {
                    bySecond.getOrPut(bucketKey(w.length, w[1])) { ArrayList() }.add(i)
                }
            }

            return Dictionary(words, freqs, byFirst.packed(), bySecond.packed())
        }

        /** Collapse the index's per-bucket lists into arrays, which is how they are read. */
        private fun Map<Int, MutableList<Int>>.packed(): Map<Int, IntArray> =
            mapValues { (_, indices) -> indices.toIntArray() }

        /** Apply [sample]'s leading capitalization to [word]. */
        private fun capitalizeLike(sample: String, word: String): String = when {
            sample.isNotEmpty() && sample.all { it.isUpperCase() } && sample.length > 1 -> word.uppercase()
            sample.isNotEmpty() && sample[0].isUpperCase() -> word.replaceFirstChar { it.uppercase() }
            else -> word
        }

        /**
         * True iff [a] is at most one edit from [b], counting a swap of two adjacent
         * characters as a single edit (Damerau-Levenshtein rather than plain Levenshtein).
         *
         * Transpositions have to count as one edit or the most common typos of all go
         * uncorrected: "teh", "recieve" and "thsi" are each two plain-Levenshtein edits
         * from the intended word, so a strict Levenshtein-1 rule either leaves them alone
         * or — worse — picks whatever unrelated word happens to be one substitution away
         * ("teh" -> "ten", "recieve" -> "relieve").
         */
        private fun withinEditDistance1(a: String, b: String): Boolean {
            val la = a.length
            val lb = b.length
            when (la - lb) {
                0 -> {
                    // Collect at most the first two mismatches; more than two can never be
                    // one edit, and exactly two is only reachable via a transposition.
                    var first = -1
                    var second = -1
                    for (i in 0 until la) {
                        if (a[i] == b[i]) continue
                        when {
                            first < 0 -> first = i
                            second < 0 -> second = i
                            else -> return false
                        }
                    }
                    if (second < 0) return true // identical, or one substitution
                    return second == first + 1 &&
                        a[first] == b[second] && a[second] == b[first]
                }
                1 -> return isOneInsertion(a, b) // a has the extra char
                -1 -> return isOneInsertion(b, a) // b has the extra char
                else -> return false
            }
        }

        /** True iff [longer] equals [shorter] with exactly one extra character inserted. */
        private fun isOneInsertion(longer: String, shorter: String): Boolean {
            var i = 0
            var j = 0
            var skipped = false
            while (i < longer.length && j < shorter.length) {
                if (longer[i] == shorter[j]) {
                    i++; j++
                } else {
                    if (skipped) return false
                    skipped = true
                    i++ // skip the extra character in the longer string
                }
            }
            return true
        }
    }
}
