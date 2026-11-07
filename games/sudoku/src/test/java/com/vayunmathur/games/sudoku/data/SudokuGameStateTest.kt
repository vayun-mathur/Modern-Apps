package com.vayunmathur.games.sudoku.data

import com.vayunmathur.games.sudoku.domain.SudokuGenerator
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SudokuGameStateTest {

    private val puzzle = SudokuGenerator.generate(BoardSize.SIX, Difficulty.EASY, Random(1))
    private val fresh = SudokuGameState.from(puzzle)

    /** A cell the player is allowed to fill in. */
    private val blank = puzzle.givens.indices.first { puzzle.givens[it] == 0 }

    /** A digit that does not belong in [blank]. */
    private val wrongDigit = (1..puzzle.size.side).first { it != puzzle.solution[blank] }

    private fun SudokuGameState.withEntry(index: Int, digit: Int) =
        copy(entries = entries.toMutableList().also { it[index] = digit })

    @Test
    fun aWrongDigitIsRecognisedButNotBlocked() {
        // Wrong digits go in silently; nothing stops them, and nothing draws attention to them.
        val s = fresh.withEntry(blank, wrongDigit)
        assertTrue(s.isWrong(blank))
        assertEquals(wrongDigit, s.valueAt(blank), "the wrong digit should still be on the board")
    }

    @Test
    fun aCorrectDigitIsNotWrong() {
        assertFalse(fresh.withEntry(blank, puzzle.solution[blank]).isWrong(blank))
    }

    @Test
    fun anEmptyCellIsNotWrong() {
        assertFalse(fresh.isWrong(blank))
    }

    @Test
    fun givensAreNeverWrong() {
        val given = puzzle.givens.indices.first { puzzle.givens[it] != 0 }
        assertFalse(fresh.isWrong(given))
    }

    @Test
    fun aFullBoardWithAMistakeDoesNotComplete() {
        // This is the only signal the player gets that something is off.
        val entries = List(puzzle.size.cellCount) {
            if (fresh.isGiven(it)) 0 else puzzle.solution[it]
        }
        val solved = fresh.copy(entries = entries)
        assertTrue(solved.isComplete)

        val spoiled = solved.withEntry(blank, wrongDigit)
        assertFalse(spoiled.isComplete, "a full grid with a wrong digit must not count as complete")
        assertTrue(spoiled.blankIndices().isEmpty(), "and it really is full")
    }

    @Test
    fun wrongIndicesFindEveryMistake() {
        val second = puzzle.givens.indices.last { puzzle.givens[it] == 0 }
        val s = fresh.withEntry(blank, wrongDigit).withEntry(second, wrongDigitFor(second))
        assertEquals(listOf(blank, second).sorted(), s.wrongIndices())
    }

    @Test
    fun duplicatedDigitsInAUnitConflict() {
        // A second blank in the same row as [blank], entered with the same digit.
        val side = puzzle.size.side
        val peer = puzzle.givens.indices.first {
            it != blank && it / side == blank / side && puzzle.givens[it] == 0
        }
        val s = fresh.withEntry(blank, 1).withEntry(peer, 1)
        val conflicts = s.conflictingIndices()
        assertTrue(blank in conflicts && peer in conflicts, "both copies are marked: $conflicts")
        assertTrue(conflicts.all { s.valueAt(it) == 1 }, "only the duplicated digit conflicts")
    }

    @Test
    fun anEntryDuplicatingAGivenConflictsWithIt() {
        val given = puzzle.givens.indices.first { puzzle.givens[it] != 0 }
        // The blank in the given's row that entering the given's digit would duplicate.
        val side = puzzle.size.side
        val cell = puzzle.givens.indices.first {
            puzzle.givens[it] == 0 && it / side == given / side
        }
        val s = fresh.withEntry(cell, puzzle.givens[given])
        val conflicts = s.conflictingIndices()
        assertTrue(cell in conflicts && given in conflicts, "entry and given are marked: $conflicts")
        assertTrue(conflicts.all { s.valueAt(it) == puzzle.givens[given] })
    }

    @Test
    fun correctEntriesAndEmptyBoardsHaveNoConflicts() {
        assertTrue(fresh.conflictingIndices().isEmpty())
        assertTrue(fresh.withEntry(blank, puzzle.solution[blank]).conflictingIndices().isEmpty())
    }

    @Test
    fun wrongIndicesIsEmptyOnACleanBoard() {
        assertTrue(fresh.wrongIndices().isEmpty())
        assertTrue(fresh.withEntry(blank, puzzle.solution[blank]).wrongIndices().isEmpty())
    }

    @Test
    fun blankIndicesAreExactlyTheEmptyCells() {
        assertEquals(
            puzzle.givens.indices.filter { puzzle.givens[it] == 0 },
            fresh.blankIndices(),
        )
        assertFalse(blank in fresh.withEntry(blank, puzzle.solution[blank]).blankIndices())
    }

    @Test
    fun aWrongEntryCountsAsFilledNotBlank() {
        // A hint has to treat it as an error to fix, not as an empty cell to fill.
        val s = fresh.withEntry(blank, wrongDigit)
        assertFalse(blank in s.blankIndices())
        assertTrue(blank in s.wrongIndices())
    }

    @Test
    fun clueFractionsOnlyEverGetHarder() {
        val targets = Difficulty.entries.map { it.targetClues(BoardSize.NINE) }
        assertEquals(targets, targets.sortedDescending(), "clue targets should fall: $targets")
    }

    @Test
    fun boxGeometryCoversEveryCellExactlyOnce() {
        for (size in BoardSize.entries) {
            val counts = IntArray(size.side)
            for (index in 0 until size.cellCount) counts[size.boxOf(index)]++
            // Every box must hold exactly as many cells as a row does, or the box constraint would be
            // weaker or stronger than the row constraint.
            assertTrue(
                counts.all { it == size.side },
                "${size.name} box sizes were ${counts.toList()}",
            )
        }
    }

    @Test
    fun symbolsAreOneCharacterOnEveryBoard() {
        // A 12x12 pencil-mark grid has twelve candidates in one cell; two-character labels are unusable.
        for (size in BoardSize.entries) {
            for (digit in 1..size.side) {
                assertEquals(1, sudokuSymbol(digit).length, "${size.name} digit $digit")
            }
        }
        assertEquals("9", sudokuSymbol(9))
        assertEquals("A", sudokuSymbol(10))
        assertEquals("C", sudokuSymbol(12))
    }

    private fun wrongDigitFor(index: Int) =
        (1..puzzle.size.side).first { it != puzzle.solution[index] }
}
