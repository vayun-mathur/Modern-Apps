package com.vayunmathur.games.nonogram.data

import com.vayunmathur.games.nonogram.domain.puzzle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NonogramGameStateTest {

    private val cross = puzzle(
        """
        .#.
        ###
        .#.
        """
    )

    private fun state(
        filled: Set<Int> = emptySet(),
        crossed: Set<Int> = emptySet(),
        revealedBlanks: Set<Int> = emptySet(),
        hearts: Int = STARTING_HEARTS,
    ) = NonogramGameState(
        puzzle = cross,
        filled = filled,
        crossed = crossed,
        revealedBlanks = revealedBlanks,
        hearts = hearts,
        mode = GameMode.CASUAL,
        level = 1,
    )

    /** Indices of the cells that make up the picture. */
    private val answer = cross.solution.indices.filter { cross.solution[it] }.toSet()

    /** A cell that is not part of the picture. */
    private val blankCell = cross.solution.indices.first { !cross.solution[it] }

    @Test
    fun aFreshBoardIsNotWonAndHasEveryHeart() {
        val s = state()
        assertFalse(s.isWon)
        assertFalse(s.isFailed)
        assertEquals(STARTING_HEARTS, s.hearts)
    }

    @Test
    fun fillingTheWholePictureWins() {
        assertTrue(state(filled = answer).isWon)
    }

    @Test
    fun crossesDoNotAffectTheWinCheck() {
        val blanks = cross.solution.indices.filterNot { cross.solution[it] }.toSet()
        assertTrue(state(filled = answer, crossed = blanks).isWon)
        assertTrue(state(filled = answer).isWon)
    }

    @Test
    fun aMissingCellIsNotAWin() {
        assertFalse(state(filled = answer - answer.first()).isWon)
    }

    @Test
    fun runningOutOfHeartsFailsTheBoard() {
        val s = state(hearts = 0)
        assertTrue(s.isFailed)
        assertTrue(s.isOver)
        assertFalse(s.isWon)
    }

    @Test
    fun finishingOnTheLastHeartIsAWinNotAFailure() {
        // An invariant guard rather than a reachable position: winning must always beat failing when
        // both conditions hold at once.
        val s = state(filled = answer, hearts = 0)
        assertTrue(s.isWon)
        assertFalse(s.isFailed)
    }

    @Test
    fun anExtraFilledCellIsNotAWin() {
        // Fills should never land outside the picture, but a board restored from older progress could
        // carry one, and the size comparison alone would wrongly call that finished.
        assertFalse(state(filled = answer + blankCell).isWon)
    }

    @Test
    fun aRevealedBlankIsDistinctFromANoteAndLocked() {
        val s = state(revealedBlanks = setOf(blankCell))
        assertEquals(CellMark.REVEALED, s.markAt(blankCell), "a mistake must not look like the player's own X")
        assertTrue(s.isLocked(blankCell), "a cell the game revealed cannot be charged for twice")
    }

    @Test
    fun aPlayerNoteIsNotLocked() {
        // Notes stay reversible; only what the game has settled is locked.
        val s = state(crossed = setOf(blankCell))
        assertEquals(CellMark.CROSSED, s.markAt(blankCell))
        assertFalse(s.isLocked(blankCell))
    }

    @Test
    fun aCorrectFillIsLocked() {
        val filledCell = answer.first()
        assertTrue(state(filled = setOf(filledCell)).isLocked(filledCell))
    }

    @Test
    fun belongsToPictureIdentifiesWhichTapsAreFree() {
        // This is what the ViewModel judges a tap against, so it decides where hearts are spent.
        for (index in answer) assertTrue(state().belongsToPicture(index), "cell $index")
        assertFalse(state().belongsToPicture(blankCell))
    }

    @Test
    fun markAtReportsWhatIsStored() {
        val s = state(filled = setOf(1), crossed = setOf(0))
        assertEquals(CellMark.FILLED, s.markAt(1))
        assertEquals(CellMark.CROSSED, s.markAt(0))
        assertEquals(CellMark.BLANK, s.markAt(2))
    }

    @Test
    fun levelSizeRampCoversTheThreeBoardSizes() {
        assertEquals(5, sizeForLevel(1))
        assertEquals(5, sizeForLevel(8))
        assertEquals(10, sizeForLevel(9))
        assertEquals(10, sizeForLevel(20))
        assertEquals(15, sizeForLevel(21))
        assertEquals(15, sizeForLevel(9999))
    }

    @Test
    fun theRampNeverShrinksAsLevelsGoUp() {
        val sizes = (1..60).map { sizeForLevel(it) }
        assertEquals(sizes, sizes.sorted(), "board size should only ever grow")
    }
}
