package com.vayunmathur.games.nonogram.domain

import com.vayunmathur.games.nonogram.data.GameMode
import com.vayunmathur.games.nonogram.data.NonogramGameState
import com.vayunmathur.games.nonogram.data.NonogramPuzzle
import com.vayunmathur.games.nonogram.data.STARTING_HEARTS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class NonogramMovesTest {
    // 3x3 plus sign: picture cells 1, 3, 4, 5, 7.
    private val puzzle = NonogramPuzzle.from(3, listOf(false, true, false, true, true, true, false, true, false))
    private fun game(hearts: Int = STARTING_HEARTS) =
        NonogramGameState(puzzle, emptySet(), emptySet(), emptySet(), hearts, GameMode.CASUAL, 1)

    @Test fun guessFillsAPictureCellAndDropsItsNote() {
        val g = NonogramMoves.guess(NonogramMoves.setNote(game(), 1, true), 1)
        assertEquals(setOf(1), g.filled); assertEquals(emptySet(), g.crossed); assertEquals(STARTING_HEARTS, g.hearts)
    }

    @Test fun guessOnABlankRevealsItAndCostsOneHeart() {
        val g = NonogramMoves.guess(game(), 0)
        assertEquals(setOf(0), g.revealedBlanks); assertEquals(STARTING_HEARTS - 1, g.hearts)
        assertSame(g, NonogramMoves.guess(g, 0), "a revealed cell is settled and never charged twice")
    }

    @Test fun movesAreIgnoredOnceTheGameIsOver() {
        val failed = game(hearts = 0)
        assertSame(failed, NonogramMoves.guess(failed, 1))
        assertSame(failed, NonogramMoves.toggleNote(failed, 0))
    }

    @Test fun undoRestoresMarksButNeverRefundsAHeart() {
        val before = NonogramMoves.setNote(game(), 0, true)
        // A stroke that filled 1, then hit blank 2: revealed and charged.
        val after = NonogramMoves.guess(NonogramMoves.guess(before, 1), 2)
        val undone = NonogramMoves.undo(after, before)
        assertEquals(emptySet(), undone.filled)
        assertEquals(setOf(0), undone.crossed)
        assertEquals(setOf(2), undone.revealedBlanks, "a revealed mistake stays revealed")
        assertEquals(STARTING_HEARTS - 1, undone.hearts, "undo never refunds a heart")
    }

    @Test fun undoDoesNotRestoreANoteOnACellThatWasRevealed() {
        val before = NonogramMoves.setNote(game(), 2, true)
        val after = NonogramMoves.guess(before, 2)
        assertEquals(emptySet(), NonogramMoves.undo(after, before).crossed)
    }

    @Test fun toggleNoteFlipsAndNeverCosts() {
        val once = NonogramMoves.toggleNote(game(), 4)
        assertEquals(setOf(4), once.crossed)
        val twice = NonogramMoves.toggleNote(once, 4)
        assertEquals(emptySet(), twice.crossed); assertEquals(STARTING_HEARTS, twice.hearts)
    }
}
