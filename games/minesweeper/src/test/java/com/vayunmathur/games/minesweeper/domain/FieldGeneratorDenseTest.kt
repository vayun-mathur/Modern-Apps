package com.vayunmathur.games.minesweeper.domain

import com.vayunmathur.games.minesweeper.data.Difficulty
import com.vayunmathur.games.minesweeper.data.BoardSize
import com.vayunmathur.games.minesweeper.data.GameOutcome
import com.vayunmathur.games.minesweeper.data.MinesweeperGameState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Dense-field fallbacks in the generator and no-op guards in the rules. */
class FieldGeneratorDenseTest {

    @Test
    fun safeZoneShrinksToTheTappedCellWhenTheFieldIsTooDense() {
        // 3x3 with eight mines: the full 3x3 safe zone leaves no room for the mines,
        // so only the tapped centre is guaranteed clear and the tap still cannot lose.
        val blank = MinesweeperGameState.of(cols = 3, rows = 3, mineCount = 8)
        val laid = FieldGenerator.lay(blank, safeIndex = 4, rng = Random(7))
        assertEquals(8, laid.mines.count { it })
        assertFalse(laid.mines[4])
        assertTrue(laid.started)
        val opened = MinesweeperRules.reveal(laid, 4)
        assertTrue(opened.outcome != GameOutcome.LOST)
        assertTrue(opened.revealed[4])
    }

    @Test
    fun cornerTapKeepsItsWholeNeighbourhoodClear() {
        val blank = MinesweeperGameState.of(cols = 4, rows = 4, mineCount = 3)
        repeat(25) { seed ->
            val laid = FieldGenerator.lay(blank, safeIndex = 0, rng = Random(seed))
            assertFalse(laid.mines[0], "seed $seed")
            for (n in laid.neighbours(0)) assertFalse(laid.mines[n], "seed $seed neighbour $n")
            assertEquals(3, laid.mines.count { it }, "seed $seed")
        }
    }

    @Test
    fun mineCountsForEveryPickerCombination() {
        assertEquals(8, Difficulty.EASY.mineCount(BoardSize.SMALL))
        assertEquals(12, Difficulty.MEDIUM.mineCount(BoardSize.SMALL))
        assertEquals(15, Difficulty.HARD.mineCount(BoardSize.SMALL))
        assertEquals(18, Difficulty.EXPERT.mineCount(BoardSize.SMALL))
        assertEquals(14, Difficulty.EASY.mineCount(BoardSize.MEDIUM))
        assertEquals(21, Difficulty.MEDIUM.mineCount(BoardSize.MEDIUM))
        assertEquals(27, Difficulty.HARD.mineCount(BoardSize.MEDIUM))
        assertEquals(32, Difficulty.EXPERT.mineCount(BoardSize.MEDIUM))
        assertEquals(22, Difficulty.EASY.mineCount(BoardSize.LARGE))
        assertEquals(32, Difficulty.MEDIUM.mineCount(BoardSize.LARGE))
        assertEquals(41, Difficulty.HARD.mineCount(BoardSize.LARGE))
        assertEquals(50, Difficulty.EXPERT.mineCount(BoardSize.LARGE))
    }

    @Test
    fun revealAndChordGuardsReturnTheSameState() {
        // Mine at centre (4) of a 3x3.
        val mines = List(9) { it == 4 }
        val base = MinesweeperGameState.of(3, 3, 1)
        val counts = List(9) { i -> if (mines[i]) 0 else base.neighbours(i).count { mines[it] } }
        val state = base.copy(mines = mines, neighbourCounts = counts, started = true)

        val flaggedMine = MinesweeperRules.toggleFlag(state, 4)
        assertSame(flaggedMine, MinesweeperRules.reveal(flaggedMine, 4), "a flagged mine cannot be dug")
        assertTrue(flaggedMine.outcome == GameOutcome.PLAYING)

        val opened = MinesweeperRules.reveal(state, 0)
        assertSame(opened, MinesweeperRules.reveal(opened, 0), "re-digging an open cell is a no-op")
        // Cell 0 is a 1 with no flags around it: chording it must open nothing.
        val chordedNoFlags = MinesweeperRules.chord(opened, 0)
        assertEquals(opened.revealed, chordedNoFlags.revealed)
        assertEquals(GameOutcome.PLAYING, chordedNoFlags.outcome)

        // Over-flagging a number also blocks the chord: the count must match exactly.
        val revealedOne = MinesweeperRules.reveal(state, 1)
        val overFlagged = MinesweeperRules.toggleFlag(
            MinesweeperRules.toggleFlag(revealedOne, 0), 2,
        )
        val chorded = MinesweeperRules.chord(overFlagged, 1)
        assertEquals(overFlagged.revealed, chorded.revealed, "two flags around a 1 cannot chord")
        assertEquals(GameOutcome.PLAYING, chorded.outcome)
    }

    @Test
    fun unstartedBlankFieldIsNotClearedAndHasNoProgress() {
        val blank = MinesweeperGameState.of(cols = 5, rows = 4, mineCount = 3)
        assertEquals(20, blank.cellCount)
        assertFalse(blank.isCleared)
        assertFalse(blank.isOver)
        assertEquals(3, blank.minesRemaining)
        assertEquals(-1, blank.explodedAt)
        assertTrue(blank.mines.none { it })
        assertTrue(blank.neighbourCounts.all { it == 0 })
        // A field whose only cells are mines would be trivially cleared; the real
        // layouts always pair mines with covered safe cells, so check the inverse:
        val allSafeRevealed = blank.copy(revealed = List(20) { true })
        assertTrue(allSafeRevealed.isCleared)
    }
}
