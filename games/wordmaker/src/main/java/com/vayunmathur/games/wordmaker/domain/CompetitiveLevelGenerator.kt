package com.vayunmathur.games.wordmaker.domain

import android.content.Context
import com.vayunmathur.games.wordmaker.data.CrosswordData
import kotlin.random.Random

/**
 * Runtime crossword generator. This is a Kotlin port of scripts/wordmaker/generate_levels.py
 * (the script that pre-builds the casual levels): it picks a 7-8 letter "wheel" word, finds
 * shorter words that can be spelled from its letters, and places them on a grid with
 * intersections. Competitive mode calls [generate] on demand to produce a fresh board each round
 * instead of loading a static asset.
 *
 * The expensive frequency-index "difficulty scoring" from the script is dropped because
 * competitive difficulty is driven by the countdown timer, not board complexity. Word choice is
 * restricted to a common-frequency prefix of the word list so generated boards stay playable.
 */
class CompetitiveLevelGenerator(private val words: List<String>) {

    // Restrict generation to the most common words for playability and speed.
    private val pool: List<String> = words.take(COMMON_LIMIT).filter { it.length in 3..8 }
    private val wheelCandidates: List<String> = pool.filter { it.length in 7..8 }

    /** Generates a fresh random crossword, or null if generation failed after the attempt budget. */
    fun generate(): CrosswordData? = generate(Random.Default)

    /**
     * Generates a crossword using the supplied [rng]. Passing a seeded [Random] makes generation
     * deterministic, so a given seed (e.g. a casual level number) always yields the same board.
     */
    fun generate(rng: Random): CrosswordData? {
        if (wheelCandidates.isEmpty()) return null
        repeat(WHEEL_ATTEMPTS) {
            val wheelWord = wheelCandidates.random(rng)
            val wheelCounts = counts(wheelWord)

            val candidates = pool.filter { w ->
                val wc = counts(w)
                wc.all { (c, n) -> n <= (wheelCounts[c] ?: 0) }
            }
            if (candidates.size < 6) return@repeat

            repeat(PLACEMENT_ATTEMPTS) {
                val rows = 11
                val cols = 11
                val grid = Array(rows) { CharArray(cols) { ' ' } }

                val mandatory = mutableListOf(wheelWord)
                if (rng.nextDouble() < 0.5) {
                    val longCandidates = candidates.filter { it.length in 6..8 && it != wheelWord }
                    if (longCandidates.isNotEmpty()) {
                        mandatory.add(longCandidates.take(10).random(rng))
                    }
                }

                val others = candidates.filter { it !in mandatory }.shuffled(rng)
                val wordsToTry = (mandatory + others).take(15)

                val placed = mutableListOf<String>()
                val succeeded = placeWords(grid, wordsToTry, placed, rows, cols, rng)
                if (succeeded && isAcceptableBoard(placed, mandatory)) {
                    return CrosswordData.fromString(gridToString(grid))
                }
            }
        }
        return null
    }

    /** Enough placed words, all mandatory ones present, and a usable letter wheel. */
    private fun isAcceptableBoard(placed: List<String>, mandatory: List<String>): Boolean {
        if (placed.size < MIN_PLACED_WORDS) return false
        if (!mandatory.all { it in placed }) return false
        return hasUsableWheel(wheelLetters(placed))
    }

    private fun hasUsableWheel(wheel: List<Char>): Boolean {
        if (wheel.size !in MIN_WHEEL_LETTERS..MAX_WHEEL_LETTERS) return false
        return wheel.toSet().size >= MIN_WHEEL_DISTINCT
    }

    private fun counts(word: String): Map<Char, Int> {
        val m = HashMap<Char, Int>()
        for (c in word) m[c] = (m[c] ?: 0) + 1
        return m
    }

    private fun wheelLetters(placedWords: List<String>): List<Char> {
        val maxCounts = HashMap<Char, Int>()
        for (word in placedWords) {
            for ((c, n) in counts(word)) {
                maxCounts[c] = maxOf(maxCounts[c] ?: 0, n)
            }
        }
        return maxCounts.flatMap { (c, n) -> List(n) { c } }
    }

    private fun placeWords(
        grid: Array<CharArray>,
        words: List<String>,
        placed: MutableList<String>,
        rows: Int,
        cols: Int,
        rng: Random
    ): Boolean {
        if (words.isEmpty()) return true
        val sorted = words.sortedByDescending { it.length }
        val first = sorted[0]
        if (!placeFirstWord(grid, first, rows, cols, rng)) return false
        placed.add(first)
        for (i in 1 until sorted.size) {
            if (tryPlaceIntersecting(grid, sorted[i], rows, cols, rng)) placed.add(sorted[i])
            if (placed.size >= 12) break
        }
        return placed.size >= 4
    }

    private fun placeFirstWord(grid: Array<CharArray>, word: String, rows: Int, cols: Int, rng: Random): Boolean {
        val horizontal = rng.nextDouble() < 0.5
        val r = (rows / 4) + (0..(rows / 4)).random(rng)
        val c = (cols / 4) + (0..(cols / 4)).random(rng)
        if (canPlace(grid, word, r, c, horizontal, rows, cols)) {
            doPlace(grid, word, r, c, horizontal)
            return true
        }
        return false
    }

    private fun tryPlaceIntersecting(grid: Array<CharArray>, word: String, rows: Int, cols: Int, rng: Random): Boolean {
        val placements = mutableListOf<Triple<Int, Int, Boolean>>()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (canPlace(grid, word, r, c, true, rows, cols) && intersects(grid, word, r, c, true)) {
                    placements.add(Triple(r, c, true))
                }
                if (canPlace(grid, word, r, c, false, rows, cols) && intersects(grid, word, r, c, false)) {
                    placements.add(Triple(r, c, false))
                }
            }
        }
        if (placements.isEmpty()) return false
        val (r, c, horizontal) = placements.random(rng)
        doPlace(grid, word, r, c, horizontal)
        return true
    }

    private fun canPlace(
        grid: Array<CharArray>,
        word: String,
        r: Int,
        c: Int,
        horizontal: Boolean,
        rows: Int,
        cols: Int
    ): Boolean {
        if (horizontal) {
            return canPlaceRun(
                word = word,
                length = cols,
                start = c,
                cellAt = { i -> grid[r][c + i] },
                neighborsClear = { i -> checkNeighbors(grid, r, c + i, true, rows, cols) },
                beforeBlocked = c > 0 && grid[r][c - 1] != ' ',
                afterBlocked = c + word.length < cols && grid[r][c + word.length] != ' ',
            )
        }
        return canPlaceRun(
            word = word,
            length = rows,
            start = r,
            cellAt = { i -> grid[r + i][c] },
            neighborsClear = { i -> checkNeighbors(grid, r + i, c, false, rows, cols) },
            beforeBlocked = r > 0 && grid[r - 1][c] != ' ',
            afterBlocked = r + word.length < rows && grid[r + word.length][c] != ' ',
        )
    }

    private fun canPlaceRun(
        word: String,
        length: Int,
        start: Int,
        cellAt: (Int) -> Char,
        neighborsClear: (Int) -> Boolean,
        beforeBlocked: Boolean,
        afterBlocked: Boolean,
    ): Boolean {
        if (start + word.length > length) return false
        for (i in word.indices) {
            val ch = cellAt(i)
            if (ch != ' ' && ch != word[i]) return false
            if (ch == ' ' && !neighborsClear(i)) return false
        }
        if (beforeBlocked || afterBlocked) return false
        return true
    }

    private fun checkNeighbors(
        grid: Array<CharArray>,
        r: Int,
        c: Int,
        horizontal: Boolean,
        rows: Int,
        cols: Int
    ): Boolean {
        if (horizontal) {
            if (r > 0 && grid[r - 1][c] != ' ') return false
            if (r < rows - 1 && grid[r + 1][c] != ' ') return false
        } else {
            if (c > 0 && grid[r][c - 1] != ' ') return false
            if (c < cols - 1 && grid[r][c + 1] != ' ') return false
        }
        return true
    }

    private fun intersects(grid: Array<CharArray>, word: String, r: Int, c: Int, horizontal: Boolean): Boolean {
        for (i in word.indices) {
            val cr = if (horizontal) r else r + i
            val cc = if (horizontal) c + i else c
            if (grid[cr][cc] == word[i]) return true
        }
        return false
    }

    private fun doPlace(grid: Array<CharArray>, word: String, r: Int, c: Int, horizontal: Boolean) {
        for (i in word.indices) {
            if (horizontal) grid[r][c + i] = word[i] else grid[r + i][c] = word[i]
        }
    }

    private fun gridToString(grid: Array<CharArray>): String {
        var minR = grid.size
        var maxR = -1
        var minC = grid[0].size
        var maxC = -1
        for (r in grid.indices) {
            for (c in grid[r].indices) {
                if (grid[r][c] != ' ') {
                    minR = minOf(minR, r)
                    maxR = maxOf(maxR, r)
                    minC = minOf(minC, c)
                    maxC = maxOf(maxC, c)
                }
            }
        }
        if (maxR == -1) return ""
        return (minR..maxR).joinToString("\n") { r ->
            String(grid[r], minC, maxC - minC + 1)
        }
    }

    /** Generation limits and asset loading for [CompetitiveLevelGenerator]. */
    companion object {
        private const val COMMON_LIMIT = 20000
        private const val WHEEL_ATTEMPTS = 60
        private const val PLACEMENT_ATTEMPTS = 120
        private const val MIN_PLACED_WORDS = 6
        private const val MIN_WHEEL_LETTERS = 5
        private const val MAX_WHEEL_LETTERS = 8
        private const val MIN_WHEEL_DISTINCT = 4

        /**
         * Loads and de-dupes the common word list shipped under assets/wordgen/, mirroring the
         * preprocessing in scripts/wordmaker/generate_levels.py (uppercase, alphabetic only,
         * frequency order preserved, bad words removed).
         */
        fun fromAssets(context: Context): CompetitiveLevelGenerator {
            val badExact = loadBadWords(context)
            val badSubstrings = substringBlocklist(badExact)
            val words = loadCommonWords(context) { !isBadWord(it, badExact, badSubstrings) }
            return CompetitiveLevelGenerator(words)
        }

        private fun loadBadWords(context: Context): Set<String> = runCatching {
            context.assets.open("wordgen/bad-words.txt").bufferedReader().useLines { lines ->
                lines.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toHashSet()
            }
        }.getOrDefault(hashSetOf())

        private fun substringBlocklist(badExact: Set<String>): List<String> = badExact.filter { word ->
            val alpha = word.all { c -> c in 'A'..'Z' }
            if (!alpha) return@filter false
            if (word in EXACT_ONLY_WORDS) return@filter false // exact-only
            when {
                word.length >= MIN_SUBSTRING_BLOCK_LENGTH -> true
                word.length == SHORT_WORD_LENGTH && word !in INNOCENT_SHORT_WORDS -> true
                else -> false
            }
        }

        private fun isBadWord(word: String, badExact: Set<String>, badSubstrings: List<String>): Boolean {
            if (word in badExact) return true
            return badSubstrings.any { it in word }
        }

        private fun loadCommonWords(context: Context, keep: (String) -> Boolean): List<String> {
            val seen = HashSet<String>()
            val words = ArrayList<String>()
            context.assets.open("wordgen/common_words_list.txt").bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val w = raw.trim().uppercase()
                    if (isKeepableWord(w, seen, keep)) {
                        seen.add(w)
                        words.add(w)
                    }
                }
            }
            return words
        }

        private fun isKeepableWord(
            w: String,
            seen: Set<String>,
            keep: (String) -> Boolean,
        ): Boolean {
            if (w.isEmpty() || w in seen || !keep(w)) return false
            return w.all { it in 'A'..'Z' }
        }

        // Good Christian family filtering:
        // - Exact matches always blocked (DAMN, HELL, FRIGGING, NIGGAH, ASS, etc)
        // - Substring blocked for severe slurs len>=4 (NIGGAH contains NIGGA) but NOT for
        //   swear-adjacent euphemisms where innocent embedding is common:
        //   FRIG in REFRIGERATOR, HELL in SHELL, CRAP in SCRAP, HECK in CHECK, etc.
        // - 3-letter innocent substrings ASS/TIT/SEX/CUM allowed as substring (CLASS, TITLE)
        // Must apply to vertical words as well as horizontal during generation.
        private val EXACT_ONLY_WORDS = setOf(
            "FRIGGING", "FRIGGIN", "FRIGGED", "FRIG", "FRIKKIN", "FRIKKING", "FRICKING", "FRICKIN",
            "FRICK", "FECKING", "FECKIN", "FECK", "FREAKING", "FREAKIN", "DAMN", "DAMNED", "DAMNING",
            "DAMNABLY", "DAMNDEST", "DAMMIT", "GODDAMN", "GODDAMNED", "GODDAM", "HELL", "HELLS",
            "HELLISH", "CRAP", "CRAPPY", "CRAPPER", "PISS", "PISSED", "PISSER", "PISSING", "PISSY",
            "DARN", "DARNED", "DARNDEST", "DANG", "DANGED", "HECK", "HECKLE", "HECKLING", "HECKLED",
            "HECKLER", "BLOODY", "BLOODILY",
        )
        private val INNOCENT_SHORT_WORDS = setOf("ASS", "TIT", "SEX", "CUM")
        private const val MIN_SUBSTRING_BLOCK_LENGTH = 4
        private const val SHORT_WORD_LENGTH = 3
    }
}
