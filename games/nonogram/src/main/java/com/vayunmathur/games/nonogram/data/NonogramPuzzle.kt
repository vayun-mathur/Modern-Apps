package com.vayunmathur.games.nonogram.data

/**
 * A generated puzzle.
 *
 * [solution] is row-major and `size * size` long. The clues are derived from it, so they are always
 * consistent; an empty clue list means the line holds no filled cells and is drawn as a single 0.
 */
data class NonogramPuzzle(
    val size: Int,
    val solution: List<Boolean>,
    val rowClues: List<List<Int>>,
    val colClues: List<List<Int>>,
) {
    val filledCount: Int get() = solution.count { it }

    /** Factories and clue derivation for [NonogramPuzzle]. */
    companion object {
        /** Run lengths of filled cells along [line], the clue a player reads. */
        fun cluesFor(line: List<Boolean>): List<Int> {
            val out = mutableListOf<Int>()
            var run = 0
            for (filled in line) {
                if (filled) {
                    run++
                } else if (run > 0) {
                    out.add(run)
                    run = 0
                }
            }
            if (run > 0) out.add(run)
            return out
        }

        /** Clues for every row and column of [solution]. */
        fun from(size: Int, solution: List<Boolean>): NonogramPuzzle = NonogramPuzzle(
            size = size,
            solution = solution,
            rowClues = (0 until size).map { row ->
                cluesFor((0 until size).map { solution[row * size + it] })
            },
            colClues = (0 until size).map { col ->
                cluesFor((0 until size).map { solution[it * size + col] })
            },
        )
    }
}
