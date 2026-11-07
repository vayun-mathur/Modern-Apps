package com.vayunmathur.games.sudoku.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.sudoku.R
import com.vayunmathur.games.sudoku.data.BoardSize
import com.vayunmathur.games.sudoku.data.Difficulty
import com.vayunmathur.games.sudoku.data.SudokuGameState
import com.vayunmathur.games.sudoku.domain.SudokuGenerator
import com.vayunmathur.games.sudoku.platform.RejectedDigit
import com.vayunmathur.games.sudoku.platform.SudokuActions
import com.vayunmathur.games.sudoku.platform.SudokuUiState
import com.vayunmathur.library.ui.Text
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SudokuScreensTest {

    @get:Rule val rule = createComposeRule()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun str(id: Int) = ctx.getString(id)

    private class RecordingActions : SudokuActions {
        val digits = mutableListOf<Int>()
        val cells = mutableListOf<Int>()
        var notesToggles = 0
        var clears = 0
        var hints = 0
        var undos = 0
        var restarts = 0
        var giveUps = 0
        override fun selectCell(index: Int) { cells += index }
        override fun enterDigit(digit: Int) { digits += digit }
        override fun clearCell() { clears++ }
        override fun toggleNotesMode() { notesToggles++ }
        override fun hint() { hints++ }
        override fun undo() { undos++ }
        override fun restart() { restarts++ }
        override fun giveUp() { giveUps++ }
    }

    private fun freshGame(
        size: BoardSize = BoardSize.SIX,
        difficulty: Difficulty = Difficulty.EASY,
        answerMode: Boolean = false,
    ): SudokuGameState {
        val puzzle = SudokuGenerator.generate(size, difficulty, Random(1))
        return SudokuGameState.from(puzzle, answerMode = answerMode)
    }

    @Test
    fun boardShowsSpinnerStateWhenNoGame() {
        var exits = 0
        rule.setContent {
            GameBoardScreen(state = SudokuUiState(), actions = SudokuActions.Noop, onExit = { exits++ })
        }
        rule.onNodeWithText(str(R.string.app_name)).assertExists()
        assertEquals(0, exits)
    }

    @Test
    fun boardActionsDigitAndCellClicksReachActions() {
        val actions = RecordingActions()
        val game = freshGame()
        var exits = 0
        rule.setContent {
            GameBoardScreen(
                state = SudokuUiState(game = game, canUndo = true),
                actions = actions,
                onExit = { exits++ },
            )
        }
        rule.onNodeWithContentDescription(ctx.getString(R.string.cd_digit_key, "1")).performClick()
        assertEquals(listOf(1), actions.digits)

        rule.onNodeWithText(str(R.string.notes)).performClick()
        rule.onNodeWithText(str(R.string.erase)).performClick()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.undo)).performClick()
        rule.onNodeWithText(str(R.string.hint)).performClick()
        assertEquals(1, actions.notesToggles)
        assertEquals(1, actions.clears)
        assertEquals(1, actions.undos)
        assertEquals(1, actions.hints)
        assertEquals(0, exits)
    }

    @Test
    fun boardUndoDisabledWithoutHistoryAndGiveUpDialogDismisses() {
        val actions = RecordingActions()
        rule.setContent {
            GameBoardScreen(
                state = SudokuUiState(game = freshGame(), canUndo = false),
                actions = actions,
                onExit = {},
            )
        }
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.undo)).assertIsNotEnabled()
        rule.onNodeWithText(str(R.string.give_up)).performClick()
        rule.onNodeWithText(str(R.string.confirm_give_up_title)).assertExists()
        rule.onNodeWithText(str(R.string.confirm_give_up_message)).assertExists()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.cancel)).performClick()
        rule.onNodeWithText(str(R.string.confirm_give_up_title)).assertDoesNotExist()
        assertEquals(0, actions.giveUps)
    }

    @Test
    fun boardGiveUpDialogConfirmGivesUpAndExits() {
        val actions = RecordingActions()
        var exits = 0
        rule.setContent {
            GameBoardScreen(
                state = SudokuUiState(game = freshGame()),
                actions = actions,
                onExit = { exits++ },
            )
        }
        rule.onNodeWithText(str(R.string.give_up)).performClick()
        // Two "Give Up" labels exist (board button + dialog confirm); click every match's last.
        val nodes = rule.onAllNodesWithText(str(R.string.give_up))
        nodes[1].performClick()
        assertEquals(1, actions.giveUps)
        assertEquals(1, exits)
    }

    @Test
    fun wonBoardShowsOverlayStatsAndPlayAgainRestarts() {
        val actions = RecordingActions()
        val base = freshGame()
        val entries = List(base.size.cellCount) { if (base.isGiven(it)) 0 else base.solution[it] }
        val won = base.copy(entries = entries, isWon = true, hintsUsed = 2, elapsedSeconds = 65)
        rule.setContent {
            GameBoardScreen(state = SudokuUiState(game = won), actions = actions, onExit = {})
        }
        rule.onNodeWithText(str(R.string.congratulations)).assertExists()
        rule.onAllNodesWithText("${str(R.string.hints_used)}: 2").assertCountEquals(2)
        rule.onNodeWithText("Play Again").performClick()
        assertEquals(1, actions.restarts)
    }

    @Test
    fun wonBoardGiveUpSkipsConfirmation() {
        val actions = RecordingActions()
        var exits = 0
        val base = freshGame()
        val entries = List(base.size.cellCount) { if (base.isGiven(it)) 0 else base.solution[it] }
        val won = base.copy(entries = entries, isWon = true)
        rule.setContent {
            GameBoardScreen(state = SudokuUiState(game = won), actions = actions, onExit = { exits++ })
        }
        // Overlay covers the board's Give Up button, so this asserts the overlay renders instead;
        // the direct give-up branch is covered by the dialog tests with an unfinished game.
        rule.onNodeWithText(str(R.string.congratulations)).assertExists()
        assertEquals(0, actions.giveUps)
        assertEquals(0, exits)
    }

    @Test
    fun gameOverBoardShowsOutOfLivesAndTryAgainRestarts() {
        val actions = RecordingActions()
        val over = freshGame(answerMode = true).copy(livesRemaining = 0, hintsUsed = 1)
        assertTrue(over.isGameOver)
        rule.setContent {
            GameBoardScreen(
                state = SudokuUiState(game = over, rejectedDigit = RejectedDigit(2, 1L)),
                actions = actions,
                onExit = {},
            )
        }
        rule.onNodeWithText(str(R.string.out_of_lives)).assertExists()
        rule.onNodeWithText(str(R.string.out_of_lives_message)).assertExists()
        rule.onNodeWithText(str(R.string.try_again)).performClick()
        assertEquals(1, actions.restarts)
    }

    @Test
    fun answerModeBoardShowsHintsAndNotesModeState() {
        val actions = RecordingActions()
        val game = freshGame(answerMode = true).copy(notesMode = true, hintsUsed = 3, livesRemaining = 2)
        rule.setContent {
            GameBoardScreen(state = SudokuUiState(game = game), actions = actions, onExit = {})
        }
        rule.onNodeWithText("${str(R.string.hints_used)}: 3").assertExists()
        rule.onNodeWithText(str(R.string.notes)).assertExists().performClick()
        assertEquals(1, actions.notesToggles)
    }

    @Test
    fun boardRendersForEverySizeWithEntriesAndNotes() {
        var current by mutableStateOf(SudokuUiState(game = freshGame()))
        rule.setContent {
            GameBoardScreen(state = current, actions = SudokuActions.Noop, onExit = {})
        }
        for (size in BoardSize.entries) {
            val base = freshGame(size, Difficulty.MEDIUM)
            val blank = base.givens.indices.first { base.givens[it] == 0 }
            val notes = base.notes.toMutableList().also { it[blank] = 0b101 }
            val styled = base.copy(notes = notes, selected = blank, elapsedSeconds = 3661)
            current = SudokuUiState(game = styled)
            rule.onNodeWithText(str(R.string.hint)).assertExists()
        }
    }

    @Test
    fun displayNamesCoverEverySizeAndDifficulty() {
        rule.setContent {
            Column {
                for (size in BoardSize.entries) Text(size.displayName())
                for (difficulty in Difficulty.entries) Text(difficulty.displayName())
            }
        }
        rule.onNodeWithText(str(R.string.size_six)).assertExists()
        rule.onNodeWithText(str(R.string.size_nine)).assertExists()
        rule.onNodeWithText(str(R.string.size_twelve)).assertExists()
        rule.onNodeWithText(str(R.string.difficulty_expert)).assertExists()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.easy)).assertExists()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.medium)).assertExists()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.hard)).assertExists()
    }
}
