package com.vayunmathur.games.wordmaker.data
import android.content.Context

/** A crossword board: solution words, chooser letters, grid, and word placements. */
data class CrosswordData(
    val solutionWords: Set<String>,
    val lettersInChooser: List<Char>,
    val gridStructure: List<String>,
    val letterPositions: Map<String, List<List<Pair<Int, Int>>>>) {

    fun getWordAt(row: Int, col: Int, foundWords: Set<String>): String? {
        val cell = row to col
        fun findWord(predicate: (List<Pair<Int, Int>>) -> Boolean = { true }) =
            letterPositions.entries.firstOrNull { (word, occurrences) ->
                word in foundWords && occurrences.any { cell in it && predicate(it) }
            }?.key
        return findWord { it.first().second == it.last().second } ?: findWord()
    }

    fun winsWith(foundWords: Set<String>): Boolean {
        val foundPositions = foundWords.flatMapTo(mutableSetOf()) {
            letterPositions[it]?.flatten().orEmpty()
        }
        return (solutionWords - foundWords).all { word ->
            letterPositions[word]?.all { foundPositions.containsAll(it) } ?: true
        }
    }

    /**
     * Solution words that are not explicitly found yet but whose letters are already entirely
     * filled in by other found words (e.g. a vertical word completed as a side effect of its
     * crossing words). These should be counted without the player retracing them.
     */
    fun incidentalWords(foundWords: Set<String>): Set<String> {
        val foundPositions = foundWords.flatMapTo(mutableSetOf()) {
            letterPositions[it]?.flatten().orEmpty()
        }
        return (solutionWords - foundWords).filterTo(mutableSetOf()) { word ->
            letterPositions[word]?.any { occ -> foundPositions.containsAll(occ) } == true
        }
    }

    /** Parsers that build [CrosswordData] from asset grid text. */
    companion object {
        fun fromAsset(context: Context, fileName: String): CrosswordData? = try {
            fromString(context.assets.open(fileName).bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            // Missing or malformed asset: the caller shows a parse error for a null board.
            null
        }

        fun fromString(content: String): CrosswordData? = try {
            val lines = content.lines().dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
            if (lines.isEmpty()) return null
            val maxLength = lines.maxOf { it.length }
            val grid = lines.map {
                val padded = it.padEnd(maxLength)
                if (padded.isBlank()) ".".repeat(maxLength) else padded.replace(' ', '.')
            }
            val (words, positions) = extractWordsAndPositions(grid)
            val chooserLetters = words
                .map { word -> word.groupingBy { it }.eachCount() }
                .fold(mutableMapOf<Char, Int>()) { acc, wordMap ->
                    wordMap.forEach { (char, count) ->
                        acc[char] = maxOf(acc.getOrDefault(char, 0), count)
                    }
                    acc
                }
                .flatMap { (char, count) -> List(count) { char } }
                .sorted()

            CrosswordData(
                solutionWords = words.toSet(),
                lettersInChooser = chooserLetters,
                gridStructure = grid,
                letterPositions = positions
            )
        } catch (_: Exception) {
            // Malformed grid text: the caller shows a parse error for a null board.
            null
        }

        private fun extractWordsAndPositions(
            grid: List<String>,
        ): Pair<List<String>, Map<String, List<List<Pair<Int, Int>>>>> {
            val words = mutableListOf<String>()
            val positions = mutableMapOf<String, MutableList<List<Pair<Int, Int>>>>()
            val numRows = grid.size
            if (numRows == 0) return emptyList<String>() to emptyMap()
            val numCols = grid[0].length

            fun addWord(word: String, posList: List<Pair<Int, Int>>) {
                words.add(word)
                positions.getOrPut(word) { mutableListOf() }.add(posList)
            }

            fun scanLine(length: Int, charAt: (Int) -> Char, posAt: (Int, Int) -> Pair<Int, Int>) {
                var currentWord = ""
                var start = -1
                for (i in 0 until length) {
                    val char = charAt(i)
                    if (char != '.') {
                        if (currentWord.isEmpty()) start = i
                        currentWord += char
                    } else {
                        if (currentWord.length > 1) addWord(currentWord, List(currentWord.length) { posAt(start, it) })
                        currentWord = ""
                    }
                }
                if (currentWord.length > 1) addWord(currentWord, List(currentWord.length) { posAt(start, it) })
            }

            for (r in 0 until numRows) scanLine(numCols, { grid[r][it] }, { s, i -> r to (s + i) })
            for (c in 0 until numCols) scanLine(numRows, { grid[it][c] }, { s, i -> (s + i) to c })

            return words to positions
        }
    }
}
