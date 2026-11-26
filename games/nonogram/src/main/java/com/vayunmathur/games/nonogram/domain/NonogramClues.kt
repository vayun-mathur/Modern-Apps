package com.vayunmathur.games.nonogram.domain

import com.vayunmathur.games.nonogram.data.CellMark
import com.vayunmathur.games.nonogram.data.NonogramGameState

/**
 * Which numbers of one line's clue are already accounted for by the fills at that end
 * of the line.
 *
 * [prefixMatched] and [suffixMatched] are counted independently from their own end,
 * so on a completely matched line they can overlap over the same middle clue(s);
 * [greyed] is the union and is what the UI renders. A clue is only ever greyed as
 * part of a matched prefix or suffix — a run in the middle of the line never greys
 * a clue on its own.
 */
data class ClueGreying(
    val prefixMatched: Int,
    val suffixMatched: Int,
    val greyed: List<Boolean>,
) {
    /** Whether clue [index] is greyed; out-of-range indices are not. */
    fun isGreyed(index: Int): Boolean = greyed.getOrElse(index) { false }
}

/**
 * Clue greying and auto-cross derivation: pure line logic over the marks already
 * placed, with no knowledge of the solution beyond the clues themselves.
 *
 * Greying rule (exact): walking in from an end of the line, skipping non-filled
 * cells, each maximal run of filled cells must have exactly the length of the
 * next unmet clue from that end. Because the run is maximal, it is terminated by
 * construction — the cell after it is crossed, blank, revealed or the line edge,
 * never another fill; a longer run is one oversized run, not a matched clue plus
 * extra fills, and stops the match at that end. The first run whose length
 * differs (too short or too long) ends the prefix/suffix: it and everything
 * beyond it stays un-greyed from that end.
 */
object NonogramClues {

    /** Greying for [clues] over a line of cell [marks]; only filled cells form runs. */
    fun greying(clues: List<Int>, marks: List<CellMark>): ClueGreying =
        greyingFromFilled(clues, marks.map { it == CellMark.FILLED })

    /**
     * Greying for [clues] over a line where [filled] says which cells are filled.
     *
     * An empty clue list has nothing to grey. The displayed `0` clue (used for an
     * empty line in the UI) greys exactly when the line holds no fill at all.
     */
    fun greyingFromFilled(clues: List<Int>, filled: List<Boolean>): ClueGreying {
        if (clues.isEmpty()) return ClueGreying(0, 0, emptyList())
        if (clues == listOf(0)) {
            val matched = filled.none { it }
            val count = if (matched) 1 else 0
            return ClueGreying(count, count, listOf(matched))
        }
        val prefix = countPrefix(clues, filled)
        val suffix = countSuffix(clues, filled)
        return ClueGreying(
            prefixMatched = prefix,
            suffixMatched = suffix,
            greyed = List(clues.size) { i -> i < prefix || i >= clues.size - suffix },
        )
    }

    /** How many leading clues the runs from the start of the line match exactly. */
    private fun countPrefix(clues: List<Int>, filled: List<Boolean>): Int {
        var clueIndex = 0
        var pos = 0
        while (clueIndex < clues.size) {
            while (pos < filled.size && !filled[pos]) pos++
            if (pos >= filled.size) break
            val start = pos
            while (pos < filled.size && filled[pos]) pos++
            if (pos - start != clues[clueIndex]) break
            clueIndex++
        }
        return clueIndex
    }

    /** How many trailing clues the runs from the end of the line match exactly. */
    private fun countSuffix(clues: List<Int>, filled: List<Boolean>): Int {
        var clueIndex = clues.lastIndex
        var pos = filled.lastIndex
        var matched = 0
        while (clueIndex >= 0) {
            while (pos >= 0 && !filled[pos]) pos--
            if (pos < 0) break
            val end = pos
            while (pos >= 0 && filled[pos]) pos--
            if (end - pos != clues[clueIndex]) break
            matched++
            clueIndex--
        }
        return matched
    }

    /** Greying for row [row] of [game], in clue order. */
    fun rowGreying(game: NonogramGameState, row: Int): ClueGreying {
        val n = game.size
        return greying(game.puzzle.rowClues[row], (0 until n).map { game.markAt(row * n + it) })
    }

    /** Greying for column [col] of [game], in clue order. */
    fun colGreying(game: NonogramGameState, col: Int): ClueGreying {
        val n = game.size
        return greying(game.puzzle.colClues[col], (0 until n).map { game.markAt(it * n + col) })
    }

    /**
     * Whether every fill a line's clue requires is placed in it.
     *
     * The clue sum is the requirement: an empty clue (or the displayed `0`)
     * requires no fills, so such a line is complete from the start.
     */
    fun isLineComplete(clues: List<Int>, filledCount: Int): Boolean = filledCount == clues.sum()

    /**
     * The cells that should currently carry an automatic cross on [game]: every
     * still-blank cell of every fill-complete row or column. Cells that are
     * filled, revealed, or carry the player's own cross are never included.
     */
    fun autoCrossedCells(game: NonogramGameState): Set<Int> {
        val n = game.size
        val rowComplete = (0 until n).map { row ->
            isLineComplete(
                game.puzzle.rowClues[row],
                (0 until n).count { (row * n + it) in game.filled },
            )
        }
        val colComplete = (0 until n).map { col ->
            isLineComplete(
                game.puzzle.colClues[col],
                (0 until n).count { (it * n + col) in game.filled },
            )
        }
        return (0 until n * n).filterTo(mutableSetOf()) { index ->
            val row = index / n
            val col = index % n
            (rowComplete[row] || colComplete[col]) &&
                index !in game.filled &&
                index !in game.revealedBlanks &&
                index !in game.crossed
        }
    }

    /**
     * [game] with its automatic crosses re-derived from scratch.
     *
     * Recomputing the whole set — rather than only adding new crosses — is what
     * removes an auto-cross when a fill that justified it is undone, while a
     * manual cross survives because it is never part of this set. The result is
     * idempotent: applying it to its own output returns the same instance.
     */
    fun withAutoCrosses(game: NonogramGameState): NonogramGameState {
        val desired = autoCrossedCells(game)
        return if (desired == game.autoCrossed) game else game.copy(autoCrossed = desired)
    }
}
