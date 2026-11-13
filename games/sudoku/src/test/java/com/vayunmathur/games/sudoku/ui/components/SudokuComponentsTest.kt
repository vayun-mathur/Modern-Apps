package com.vayunmathur.games.sudoku.ui.components

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.sudoku.R
import com.vayunmathur.games.sudoku.data.BoardSize
import com.vayunmathur.games.sudoku.data.Difficulty
import com.vayunmathur.games.sudoku.data.SudokuGameState
import com.vayunmathur.games.sudoku.domain.SudokuGenerator
import com.vayunmathur.games.sudoku.platform.RejectedDigit
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SudokuComponentsTest {

    @get:Rule val rule = createComposeRule()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun game(
        size: BoardSize = BoardSize.SIX,
        notesMode: Boolean = false,
        answerMode: Boolean = false,
    ): SudokuGameState {
        val puzzle = SudokuGenerator.generate(size, Difficulty.EASY, Random(1))
        return SudokuGameState.from(puzzle, answerMode = answerMode).copy(notesMode = notesMode)
    }

    private fun cellDesc(game: SudokuGameState, index: Int): String {
        val side = game.size.side
        val spoken = if (game.valueAt(index) == 0) ctx.getString(R.string.cd_empty)
        else com.vayunmathur.games.sudoku.data.sudokuSymbol(game.valueAt(index))
        return ctx.getString(R.string.cd_cell, index / side + 1, index % side + 1, spoken)
    }

    @Test
    fun gridRendersCellsAndReportsSelection() {
        val game = game()
        val selected = mutableListOf<Int>()
        rule.setContent { SudokuGrid(game = game, onSelect = { selected += it }) }
        rule.onNodeWithContentDescription(cellDesc(game, 0)).assertExists().performClick()
        rule.onNodeWithContentDescription(cellDesc(game, game.size.side + 1)).performClick()
        assertEquals(listOf(0, game.size.side + 1), selected)
    }

    @Test
    fun gridRendersNotesSelectionAndSameValueStatesForAllSizes() {
        var clicks = 0
        var current by mutableStateOf(game())
        rule.setContent { SudokuGrid(game = current, onSelect = { clicks++ }) }
        for (size in BoardSize.entries) {
            val base = game(size)
            val blank = base.givens.indices.first { base.givens[it] == 0 }
            val mask = (1 shl 0) or (1 shl (size.side - 1))
            val notes = base.notes.toMutableList().also { it[blank] = mask }
            val entryIndex = base.givens.indices.last { base.givens[it] == 0 }
            val entries = base.entries.toMutableList().also { it[entryIndex] = base.solution[entryIndex] }
            val styled = base.copy(notes = notes, entries = entries, selected = entryIndex)
            current = styled
            clicks = 0
            rule.onNodeWithContentDescription(cellDesc(styled, blank)).assertExists().performClick()
            assertEquals(1, clicks, "size $size")
        }
    }

    @Test
    fun numberPadReportsDigitsForAllSizes() {
        val tapped = mutableListOf<Int>()
        var current by mutableStateOf(game())
        rule.setContent { NumberPad(game = current, onDigit = { tapped += it }) }
        for (size in BoardSize.entries) {
            tapped.clear()
            current = game(size)
            val label = com.vayunmathur.games.sudoku.data.sudokuSymbol(size.side)
            rule.onNodeWithContentDescription(ctx.getString(R.string.cd_digit_key, label))
                .assertExists().performClick()
            rule.onNodeWithContentDescription(ctx.getString(R.string.cd_digit_key, "1")).performClick()
            assertEquals(listOf(size.side, 1), tapped, "size $size")
        }
    }

    @Test
    fun numberPadNotesModeUsesNoteKeys() {
        val tapped = mutableListOf<Int>()
        rule.setContent { NumberPad(game = game(notesMode = true), onDigit = { tapped += it }) }
        rule.onNodeWithContentDescription(ctx.getString(R.string.cd_note_key, "3"))
            .assertExists().performClick()
        assertEquals(listOf(3), tapped)
    }

    @Test
    fun numberPadDisablesCompletedDigitOnlyInAnswerModeAndShowsRejection() {
        // Fill every copy of digit 1 correctly, so in answer mode its key is done.
        val base = game(answerMode = true)
        val entries = base.entries.toMutableList()
        for (index in base.solution.indices) {
            if (base.solution[index] == 1 && base.givens[index] == 0) entries[index] = 1
        }
        val completed = base.copy(entries = entries)
        assertTrue(completed.isDigitComplete(1))
        val tapped = mutableListOf<Int>()
        rule.setContent {
            NumberPad(game = completed, onDigit = { tapped += it }, rejected = RejectedDigit(2, 1L))
        }
        rule.onNodeWithContentDescription(ctx.getString(R.string.cd_digit_key, "1"))
            .assertIsNotEnabled()
        rule.onNodeWithContentDescription(ctx.getString(R.string.cd_digit_key, "2"))
            .assertExists().performClick()
        assertEquals(listOf(2), tapped, "only the enabled key reports a tap")

        // Same completed digit stays enabled in classic mode.
        rule.onNodeWithContentDescription(ctx.getString(R.string.cd_digit_key, "3")).performClick()
        assertEquals(listOf(2, 3), tapped)
    }

    @Test
    fun numberPadShowsRemainingCountBadges() {
        // Fill every copy of digit 1, so its badge reads 0; other badges read their own counts.
        val base = game()
        val side = base.size.side
        val entries = base.entries.toMutableList()
        for (index in base.solution.indices) {
            if (base.solution[index] == 1 && base.givens[index] == 0) entries[index] = 1
        }
        val game = base.copy(entries = entries)
        rule.setContent { NumberPad(game = game, onDigit = {}) }

        fun remaining(digit: Int) =
            side - (0 until game.size.cellCount).count { game.valueAt(it) == digit }
        assertEquals(0, remaining(1))
        // A "0" badge is unambiguous: no digit label is ever "0" (1-9, then letters).
        val full = (1..side).count { remaining(it) == 0 }
        assertTrue(full >= 1)
        rule.onAllNodesWithText("0", useUnmergedTree = true).assertCountEquals(full)

        // A digit still missing copies shows its own count, whatever the givens dealt.
        // Counted exactly: digit labels share the same text (a "3" badge reads like the 3 key),
        // so the assertion adds badges and labels together.
        val missing = (2..side).firstOrNull { remaining(it) > 0 }
        if (missing != null) {
            val want = remaining(missing).toString()
            val badges = (1..side).count { remaining(it).toString() == want }
            val labels = (1..side).count {
                com.vayunmathur.games.sudoku.data.sudokuSymbol(it) == want
            }
            rule.onAllNodesWithText(want, useUnmergedTree = true).assertCountEquals(badges + labels)
        }
    }

    @Test
    fun gridRendersWrongEntryWithHighlightMistakesOnAndOff() {
        // Compose assertions cannot read the drawn color, so this locks the wiring: a wrong entry
        // renders under both settings without crashing, and the model marks it wrong.
        val base = game()
        val blank = base.givens.indices.first { base.givens[it] == 0 }
        val wrong = base.solution[blank] % base.size.side + 1
        val entries = base.entries.toMutableList().also { it[blank] = wrong }
        val game = base.copy(entries = entries)
        assertTrue(game.isWrong(blank))
        var highlight by mutableStateOf(true)
        rule.setContent { SudokuGrid(game = game, onSelect = {}, highlightMistakes = highlight) }
        rule.onNodeWithContentDescription(cellDesc(game, blank)).assertExists()
        highlight = false
        rule.onNodeWithContentDescription(cellDesc(game, blank)).assertExists()
    }

    @Test
    fun cellsAnnounceSelectionGivenEnteredWrongConflictAndNotes() {
        // 9x9 so some blank surely has a wrong digit that duplicates nothing.
        val base = game(BoardSize.NINE)
        val side = base.size.side
        val blanks = base.givens.indices.filter { base.givens[it] == 0 }
        val givenCell = base.givens.indices.first { base.givens[it] != 0 }
        // A wrong entry that duplicates nothing in its row, column or box.
        val (wrongCell, wrongDigit) = blanks.firstNotNullOf { cell ->
            (1..side).firstOrNull { d ->
                d != base.solution[cell] && base.copy(
                    entries = base.entries.toMutableList().also { it[cell] = d }
                ).conflictingIndices().isEmpty()
            }?.let { cell to it }
        }
        val correctCell = blanks.first { it != wrongCell }
        val notesCell = blanks.first { it != wrongCell && it != correctCell }
        val entries = base.entries.toMutableList().also {
            it[wrongCell] = wrongDigit
            it[correctCell] = base.solution[correctCell]
        }
        val notes = base.notes.toMutableList().also { it[notesCell] = 0b101 }
        val game = base.copy(entries = entries, notes = notes, selected = correctCell)
        var highlight by mutableStateOf(true)
        rule.setContent { SudokuGrid(game = game, onSelect = {}, highlightMistakes = highlight) }

        fun state(index: Int) = rule.onNodeWithContentDescription(cellDesc(game, index))
            .fetchSemanticsNode().config.getOrElse(SemanticsProperties.StateDescription) { "" }

        assertEquals(ctx.getString(R.string.cd_given), state(givenCell))
        rule.onNodeWithContentDescription(cellDesc(game, correctCell)).assertIsSelected()
        rule.onNodeWithContentDescription(cellDesc(game, givenCell)).assertIsNotSelected()
        assertEquals(ctx.getString(R.string.cd_entered), state(correctCell))
        assertEquals(
            "${ctx.getString(R.string.cd_entered)}, ${ctx.getString(R.string.cd_wrong)}",
            state(wrongCell),
        )
        assertEquals(ctx.getString(R.string.cd_notes, "1 3"), state(notesCell))

        // With highlighting off the board hides the mistake, so TalkBack must too.
        highlight = false
        assertEquals(ctx.getString(R.string.cd_entered), state(wrongCell))
    }

    @Test
    fun duplicatedDigitIsAnnouncedAsAConflict() {
        val base = game()
        val side = base.size.side
        // Copy a given into a blank of the same row: a duplicate visible from the board alone.
        val (blank, given) = base.givens.indices.firstNotNullOf { cell ->
            if (base.givens[cell] != 0) return@firstNotNullOf null
            val row = cell / side
            (row * side until row * side + side).firstOrNull { base.givens[it] != 0 }
                ?.let { cell to it }
        }
        val entries = base.entries.toMutableList().also { it[blank] = base.givens[given] }
        val game = base.copy(entries = entries)
        rule.setContent { SudokuGrid(game = game, onSelect = {}) }
        val state = rule.onNodeWithContentDescription(cellDesc(game, blank))
            .fetchSemanticsNode().config.getOrElse(SemanticsProperties.StateDescription) { "" }
        assertTrue(ctx.getString(R.string.cd_conflict) in state, state)
    }

    @Test
    fun numberPadTwelveUsesLetterKeys() {
        val tapped = mutableListOf<Int>()
        rule.setContent { NumberPad(game = game(BoardSize.TWELVE), onDigit = { tapped += it }) }
        rule.onNodeWithContentDescription(ctx.getString(R.string.cd_digit_key, "A")).performClick()
        rule.onNodeWithContentDescription(ctx.getString(R.string.cd_digit_key, "C")).performClick()
        assertEquals(listOf(10, 12), tapped)
    }
}
