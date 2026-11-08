package com.vayunmathur.games.sudoku.domain

import com.vayunmathur.games.sudoku.data.ANSWER_MODE_LIVES
import com.vayunmathur.games.sudoku.data.BoardSize
import com.vayunmathur.games.sudoku.data.Difficulty
import com.vayunmathur.games.sudoku.data.SudokuGameState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AnswerModeTest {

    private val puzzle = SudokuGenerator.generate(BoardSize.SIX, Difficulty.EASY, Random(1))
    private val fresh = SudokuGameState.from(puzzle, answerMode = true)
    private val classic = SudokuGameState.from(puzzle)

    /** A cell the player is allowed to fill in. */
    private val blank = puzzle.givens.indices.first { puzzle.givens[it] == 0 }

    /** Digits that do and do not belong in [blank]. */
    private val rightDigit = puzzle.solution[blank]
    private val wrongDigit = (1..puzzle.size.side).first { it != rightDigit }

    @Test
    fun aWrongDigitIsRejectedAndNeverPlaced() {
        val attempt = AnswerMode.decide(fresh.copy(selected = blank), wrongDigit)
        assertIs<DigitAttempt.Reject>(attempt)
        assertEquals(wrongDigit, attempt.digit, "the pad must know which key to flash")
    }

    @Test
    fun aCorrectDigitIsPlaced() {
        val attempt = AnswerMode.decide(fresh.copy(selected = blank), rightDigit)
        assertIs<DigitAttempt.Place>(attempt)
        assertEquals(blank, attempt.index)
    }

    @Test
    fun classicModeStillAcceptsWrongDigits() {
        // The silent-accept contract predates answer mode and must not change.
        val attempt = AnswerMode.decide(classic.copy(selected = blank), wrongDigit)
        assertIs<DigitAttempt.Place>(attempt)
    }

    @Test
    fun notesAreNeverJudged() {
        // Pencil marks are possibilities, not claims: even a wrong digit pencils in freely.
        val game = fresh.copy(selected = blank, notesMode = true)
        val attempt = AnswerMode.decide(game, wrongDigit)
        assertIs<DigitAttempt.Note>(attempt)
    }

    @Test
    fun notesOnAFilledCellAreIgnoredRatherThanErasingTheEntry() {
        // Pencilling must never delete work: a note tap on a cell that already holds a digit
        // is ignored, in both modes, whatever digit was tapped.
        val filled = classic.copy(
            selected = blank,
            notesMode = true,
            entries = classic.entries.toMutableList().also { it[blank] = rightDigit },
        )
        assertIs<DigitAttempt.Ignore>(AnswerMode.decide(filled, wrongDigit))
        assertIs<DigitAttempt.Ignore>(AnswerMode.decide(filled, rightDigit))
        assertIs<DigitAttempt.Ignore>(
            AnswerMode.decide(filled.copy(answerMode = true), wrongDigit),
        )
    }

    @Test
    fun retappingTheShownDigitErasesEvenInAnswerMode() {
        val game = fresh.copy(
            selected = blank,
            entries = fresh.entries.toMutableList().also { it[blank] = rightDigit },
        )
        assertIs<DigitAttempt.Erase>(AnswerMode.decide(game, rightDigit))
    }

    @Test
    fun givensUnselectedAndEndedGamesAreIgnored() {
        val given = puzzle.givens.indices.first { puzzle.givens[it] != 0 }
        assertIs<DigitAttempt.Ignore>(AnswerMode.decide(fresh.copy(selected = given), rightDigit))
        assertIs<DigitAttempt.Ignore>(AnswerMode.decide(fresh, wrongDigit))
        assertIs<DigitAttempt.Ignore>(
            AnswerMode.decide(fresh.copy(selected = blank, isWon = true), wrongDigit),
            "a won game takes no more input",
        )
        assertIs<DigitAttempt.Ignore>(
            AnswerMode.decide(fresh.copy(selected = blank, livesRemaining = 0), wrongDigit),
            "a game out of lives takes no more input",
        )
    }

    @Test
    fun rejectionSpendsOneLifeAndLeavesTheBoardUntouched() {
        val game = fresh.copy(selected = blank)
        val after = AnswerMode.applyRejection(game)
        assertEquals(ANSWER_MODE_LIVES - 1, after.livesRemaining)
        assertEquals(0, after.valueAt(blank), "the refused digit must not be on the board")
        assertEquals(game.entries, after.entries)
        assertEquals(game.notes, after.notes)
    }

    @Test
    fun rejectionFloorsAtZero() {
        val game = fresh.copy(livesRemaining = 0)
        assertEquals(0, AnswerMode.applyRejection(game).livesRemaining)
    }

    @Test
    fun zeroLivesIsGameOverButOnlyInAnswerMode() {
        assertTrue(fresh.copy(livesRemaining = 0).isGameOver)
        assertFalse(fresh.copy(livesRemaining = 1).isGameOver)
        // Classic games have no lives, so the counter must never end them.
        assertFalse(classic.copy(livesRemaining = 0).isGameOver)
    }

    @Test
    fun aSolvedBoardIsNeverGameOver() {
        val solved = fresh.copy(
            entries = List(puzzle.size.cellCount) {
                if (fresh.isGiven(it)) 0 else puzzle.solution[it]
            },
            livesRemaining = 0,
        )
        assertTrue(solved.isComplete)
        assertFalse(solved.isGameOver, "the winning tap lands before the life hits zero")
    }

    @Test
    fun completedDigitsAreDetected() {
        // Only blanks can be "still missing": a holder that is a given shows its digit already.
        val blanks = blanksHolding(rightDigit)
        val entries = fresh.entries.toMutableList()
        if (blanks.size >= 2) {
            blanks.dropLast(1).forEach { entries[it] = rightDigit }
            val almost = fresh.copy(entries = entries)
            assertFalse(almost.isDigitComplete(rightDigit), "one copy still missing")
        }
        blanks.forEach { entries[it] = rightDigit }
        assertTrue(fresh.copy(entries = entries).isDigitComplete(rightDigit))
    }

    @Test
    fun aFullyCluedDigitIsCompleteFromTheFirstFrame() {
        // A digit that needs no entry is complete with nothing placed: the pad must grey it out
        // without waiting for the player to do anything.
        val clued = (1..puzzle.size.side).firstOrNull { digit ->
            (0 until puzzle.size.cellCount).count {
                puzzle.givens[it] == digit
            } >= puzzle.size.side
        }
        if (clued == null) return // This seed leaves every digit short; nothing to assert.
        assertTrue(fresh.isDigitComplete(clued))
    }

    @Test
    fun anIncompleteDigitIsNotComplete() {
        val digit = (1..puzzle.size.side).first { !fresh.isDigitComplete(it) }
        assertFalse(fresh.isDigitComplete(digit))
    }

    /** Blank cells whose solution is [digit] — the only copies the player can still place. */
    private fun blanksHolding(digit: Int): List<Int> =
        (0 until puzzle.size.cellCount).filter { puzzle.solution[it] == digit && puzzle.givens[it] == 0 }
}
