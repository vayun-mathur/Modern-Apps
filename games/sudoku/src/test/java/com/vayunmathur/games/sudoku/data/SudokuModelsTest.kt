package com.vayunmathur.games.sudoku.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SudokuModelsTest {

    @Test
    fun boardSizeGeometryAndBoxOf() {
        assertEquals(36, BoardSize.SIX.cellCount)
        assertEquals(81, BoardSize.NINE.cellCount)
        assertEquals(144, BoardSize.TWELVE.cellCount)
        // Corners and box boundaries for the non-square boxes.
        assertEquals(0, BoardSize.SIX.boxOf(0))
        assertEquals(0, BoardSize.SIX.boxOf(2))
        assertEquals(1, BoardSize.SIX.boxOf(3))
        assertEquals(1, BoardSize.SIX.boxOf(5))
        assertEquals(0, BoardSize.SIX.boxOf(6))
        assertEquals(5, BoardSize.SIX.boxOf(35))
        assertEquals(0, BoardSize.NINE.boxOf(0))
        assertEquals(4, BoardSize.NINE.boxOf(40))
        assertEquals(8, BoardSize.NINE.boxOf(80))
        assertEquals(0, BoardSize.TWELVE.boxOf(0))
        assertEquals(2, BoardSize.TWELVE.boxOf(11))
        assertEquals(11, BoardSize.TWELVE.boxOf(143))
    }

    @Test
    fun symbolsSwitchToLettersAfterNine() {
        assertEquals("1", sudokuSymbol(1))
        assertEquals("9", sudokuSymbol(9))
        assertEquals("A", sudokuSymbol(10))
        assertEquals("B", sudokuSymbol(11))
        assertEquals("C", sudokuSymbol(12))
    }

    @Test
    fun targetCluesUsesFractionWithSidePlusOneFloor() {
        assertEquals(48, Difficulty.EASY.targetClues(BoardSize.NINE))
        assertEquals(37, Difficulty.MEDIUM.targetClues(BoardSize.NINE))
        assertEquals(29, Difficulty.HARD.targetClues(BoardSize.NINE))
        assertEquals(22, Difficulty.EXPERT.targetClues(BoardSize.NINE))
        for (size in BoardSize.entries) for (difficulty in Difficulty.entries) {
            assertTrue(difficulty.targetClues(size) >= size.side + 1)
        }
        assertEquals(0.60, Difficulty.EASY.clueFraction)
        assertEquals(0.28, Difficulty.EXPERT.clueFraction)
    }

    @Test
    fun gameConfigDefaultsAndVariant() {
        val config = GameConfig()
        assertEquals(BoardSize.NINE, config.size)
        assertEquals(Difficulty.MEDIUM, config.difficulty)
        assertFalse(config.answerMode)
        assertEquals("NINE_MEDIUM", config.variant)
        assertEquals(
            "TWELVE_EXPERT",
            GameConfig(BoardSize.TWELVE, Difficulty.EXPERT, answerMode = true).variant,
        )
    }

    @Test
    fun fromPuzzleCreatesFreshState() {
        val puzzle = Puzzle(
            size = BoardSize.SIX,
            difficulty = Difficulty.HARD,
            givens = List(36) { if (it % 7 == 0) 3 else 0 },
            solution = List(36) { (it % 6) + 1 },
        )
        val state = SudokuGameState.from(puzzle, answerMode = true)
        assertEquals(BoardSize.SIX, state.size)
        assertEquals(Difficulty.HARD, state.difficulty)
        assertEquals(puzzle.givens, state.givens)
        assertEquals(puzzle.solution, state.solution)
        assertEquals(List(36) { 0 }, state.entries)
        assertEquals(List(36) { 0 }, state.notes)
        assertEquals(-1, state.selected)
        assertFalse(state.notesMode)
        assertEquals(0, state.moveCount)
        assertEquals(0, state.hintsUsed)
        assertFalse(state.isWon)
        assertFalse(state.usedUndo)
        assertTrue(state.answerMode)
        assertEquals(ANSWER_MODE_LIVES, state.livesRemaining)
        assertEquals("SIX_HARD", state.variant)
        assertFalse(state.isGameOver)
        assertFalse(state.isComplete)
    }

    @Test
    fun valueAtPrefersGivensAndDigitCompleteCountsAllCopies() {
        val givens = MutableList(36) { 0 }.also { it[0] = 5; it[1] = 5 }
        val state = SudokuGameState(
            size = BoardSize.SIX, difficulty = Difficulty.EASY,
            givens = givens, solution = List(36) { 1 },
            entries = List(36) { 0 }, notes = List(36) { 0 },
        )
        assertEquals(5, state.valueAt(0))
        assertEquals(0, state.valueAt(2))
        assertTrue(state.isGiven(0))
        assertFalse(state.isGiven(2))
        assertFalse(state.isDigitComplete(5))
        val entries = MutableList(36) { 0 }
        entries[2] = 5; entries[3] = 5; entries[4] = 5; entries[5] = 5
        assertTrue(state.copy(entries = entries).isDigitComplete(5))
    }

    @Test
    fun snapshotHoldsEntriesAndNotes() {
        val snapshot = SudokuSnapshot(entries = listOf(1, 0), notes = listOf(2, 4))
        assertEquals(listOf(1, 0), snapshot.entries)
        assertEquals(listOf(2, 4), snapshot.notes)
        assertEquals(snapshot, SudokuSnapshot(listOf(1, 0), listOf(2, 4)))
    }
}
