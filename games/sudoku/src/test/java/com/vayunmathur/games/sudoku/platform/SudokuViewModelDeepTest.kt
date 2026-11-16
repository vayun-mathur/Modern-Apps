package com.vayunmathur.games.sudoku.platform

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.sudoku.data.BoardSize
import com.vayunmathur.games.sudoku.data.Difficulty
import com.vayunmathur.games.sudoku.data.GameConfig
import com.vayunmathur.games.sudoku.data.SudokuDataStore
import com.vayunmathur.games.sudoku.data.SudokuStatsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Win-path and edge-path ViewModel behaviour the first suite did not reach:
 * wins on 9x9 and Expert (the size/difficulty achievement branches), a win that
 * completes every board size, undo-history capping, and input guards on ended
 * or unselected games.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SudokuViewModelDeepTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val stats: SudokuStatsRepository get() = SudokuStatsRepository(app)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        app.getSharedPreferences("sudoku_stats", Context.MODE_PRIVATE).edit().clear().commit()
        runBlocking { SudokuDataStore(app).setAnswerMode(false) }
        runBlocking { SudokuDataStore(app).setHighlightMistakes(true) }
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun await(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(25)
        }
        assertTrue(cond(), "timed out: $desc")
    }

    private fun startedVm(
        config: GameConfig = GameConfig(BoardSize.SIX, Difficulty.EASY),
    ): SudokuViewModel {
        val vm = SudokuViewModel(app)
        vm.newGame(config)
        await("game dealt") { vm.uiState.value.game != null }
        return vm
    }

    private fun solve(vm: SudokuViewModel) {
        var guard = 0
        while (guard++ < 200) {
            val game = vm.uiState.value.game!!
            val next = game.blankIndices().firstOrNull() ?: break
            vm.selectCell(next)
            vm.enterDigit(game.solution[next])
        }
        assertTrue(vm.uiState.value.game!!.isWon, "solving loop must win the game")
    }

    @Test
    fun winningNineByNineRecordsWinAndSizeStats() {
        val vm = startedVm(GameConfig(BoardSize.NINE, Difficulty.EASY))
        vm.incrementTimer()
        solve(vm)
        val won = vm.uiState.value.game!!
        assertEquals(BoardSize.NINE, won.size)
        assertTrue(won.isComplete)
        assertEquals(1, stats.getStats(BoardSize.NINE, Difficulty.EASY).gamesWon)
        assertEquals(1, stats.getSizeStats(BoardSize.NINE).gamesWon)
        assertEquals(1, vm.elapsedSeconds.value)
        vm.incrementTimer()
        assertEquals(1, vm.elapsedSeconds.value, "the clock stops on a 9x9 win too")
    }

    @Test
    fun winningExpertRecordsExpertVariantWin() {
        val vm = startedVm(GameConfig(BoardSize.SIX, Difficulty.EXPERT))
        solve(vm)
        assertEquals(1, stats.getStats(BoardSize.SIX, Difficulty.EXPERT).gamesWon)
        assertEquals(0, stats.getStats(BoardSize.SIX, Difficulty.EASY).gamesWon)
    }

    @Test
    fun winningNineAfterOtherSizesSeededCompletesEverySize() {
        // Wins already on the two other sizes: the 9x9 win is the one that
        // completes the set the every-size check looks at.
        stats.recordGameWon(BoardSize.SIX, Difficulty.EASY, 90)
        stats.recordGameWon(BoardSize.TWELVE, Difficulty.EASY, 900)
        val vm = startedVm(GameConfig(BoardSize.NINE, Difficulty.MEDIUM))
        solve(vm)
        assertTrue(BoardSize.entries.all { stats.getSizeStats(it).gamesWon > 0 })
        assertEquals(3, stats.getTotalGamesWon())
        assertEquals(1, stats.getStats(BoardSize.NINE, Difficulty.MEDIUM).currentWinStreak)
    }

    @Test
    fun winningWithHintsStillWinsButCountsThem() {
        val vm = startedVm(GameConfig(BoardSize.NINE, Difficulty.HARD))
        vm.hint()
        assertEquals(1, vm.uiState.value.game!!.hintsUsed)
        solve(vm)
        assertTrue(vm.uiState.value.game!!.hintsUsed >= 1)
        assertEquals(1, stats.getStats(BoardSize.NINE, Difficulty.HARD).gamesWon)
    }

    @Test
    fun giveUpAfterWinDoesNotRecordALoss() {
        val vm = startedVm()
        solve(vm)
        assertEquals(1, stats.getStats(BoardSize.SIX, Difficulty.EASY).currentWinStreak)
        vm.giveUp()
        assertNull(vm.uiState.value.game)
        assertEquals(
            1,
            stats.getStats(BoardSize.SIX, Difficulty.EASY).currentWinStreak,
            "giving up a finished game must not break the streak it just earned",
        )
        assertEquals(1, stats.getStats(BoardSize.SIX, Difficulty.EASY).gamesWon)
    }

    @Test
    fun inputWithNoSelectionAndOnGivenCellsChangesNothing() {
        val vm = startedVm(GameConfig(BoardSize.SIX, Difficulty.EASY, answerMode = true))
        val game = vm.uiState.value.game!!
        assertEquals(-1, game.selected)
        vm.enterDigit(1)
        assertEquals(0, vm.uiState.value.game!!.moveCount)
        assertEquals(3, vm.uiState.value.game!!.livesRemaining)

        val given = game.givens.indices.first { game.givens[it] != 0 }
        vm.selectCell(given)
        val wrong = (1..game.size.side).first { it != game.solution[given] }
        vm.enterDigit(wrong)
        val after = vm.uiState.value.game!!
        assertEquals(3, after.livesRemaining, "tapping a given costs no life in answer mode")
        assertNull(vm.uiState.value.rejectedDigit)
        assertEquals(0, after.moveCount)
        vm.clearCell()
        assertEquals(game.entries, vm.uiState.value.game!!.entries)
    }

    @Test
    fun undoWithEmptyHistoryAndHintOnEndedBoardsAreNoOps() {
        val vm = startedVm()
        val before = vm.uiState.value.game!!
        vm.undo()
        assertEquals(before.entries, vm.uiState.value.game!!.entries)
        assertFalse(vm.uiState.value.game!!.usedUndo)
        assertFalse(vm.uiState.value.canUndo)

        solve(vm)
        val won = vm.uiState.value.game!!
        vm.clearCell()
        assertEquals(won.entries, vm.uiState.value.game!!.entries, "clearing a won board does nothing")
    }

    @Test
    fun hintWithGivenOrFilledSelectionFillsFirstBlank() {
        val vm = startedVm()
        val game = vm.uiState.value.game!!
        val given = game.givens.indices.first { game.givens[it] != 0 }
        val firstBlank = game.blankIndices().first()
        vm.selectCell(given)
        vm.hint()
        var after = vm.uiState.value.game!!
        assertEquals(game.solution[firstBlank], after.valueAt(firstBlank))
        assertEquals(firstBlank, after.selected)
        assertEquals(1, after.hintsUsed)

        // Now select that filled cell: the next hint must move on to a new blank.
        val nextBlank = after.blankIndices().first()
        vm.hint()
        after = vm.uiState.value.game!!
        assertEquals(after.solution[nextBlank], after.valueAt(nextBlank))
        assertEquals(2, after.hintsUsed)
    }

    @Test
    fun undoHistoryIsCappedAndExhaustsCleanly() {
        val vm = startedVm()
        val cell = vm.uiState.value.game!!.blankIndices().first()
        val digit = vm.uiState.value.game!!.solution[cell]
        vm.selectCell(cell)
        // Toggling one cell is a real move every time (place, erase, place, ...).
        repeat(205) { vm.enterDigit(digit) }
        assertTrue(vm.uiState.value.canUndo)
        var undos = 0
        while (vm.uiState.value.canUndo && undos < 300) {
            vm.undo()
            undos++
        }
        assertEquals(200, undos, "history is capped, so only the newest moves can be undone")
        assertFalse(vm.uiState.value.canUndo)
        assertTrue(vm.uiState.value.game!!.usedUndo)
        val settled = vm.uiState.value.game!!
        vm.undo()
        assertEquals(settled.entries, vm.uiState.value.game!!.entries)
    }

    @Test
    fun restartAfterAnswerModeLossKeepsPuzzleAndAnswerMode() {
        val vm = startedVm(GameConfig(BoardSize.SIX, Difficulty.EASY, answerMode = true))
        val game = vm.uiState.value.game!!
        val cell = game.blankIndices().first()
        val wrong = (1..game.size.side).first { it != game.solution[cell] }
        vm.selectCell(cell)
        repeat(3) { vm.enterDigit(wrong) }
        assertTrue(vm.uiState.value.game!!.isGameOver)
        assertNotNull(vm.uiState.value.rejectedDigit)

        vm.restart()
        val after = vm.uiState.value.game!!
        assertEquals(game.givens, after.givens)
        assertEquals(game.solution, after.solution)
        assertTrue(after.answerMode, "restart keeps the rules the game started with")
        assertEquals(3, after.livesRemaining)
        assertNull(vm.uiState.value.rejectedDigit, "a fresh attempt clears the rejection flash state")
        assertFalse(vm.uiState.value.canUndo)
    }

    @Test
    fun newGameForTwelveRecordsThatVariantAndDealsFullSizeBoard() {
        val vm = startedVm(GameConfig(BoardSize.TWELVE, Difficulty.MEDIUM))
        val game = vm.uiState.value.game!!
        assertEquals(144, game.givens.size)
        assertEquals(144, game.solution.size)
        assertEquals(1, stats.getStats(BoardSize.TWELVE, Difficulty.MEDIUM).gamesPlayed)
        assertEquals(game.variant, "TWELVE_MEDIUM")
        assertTrue(vm.hasActiveGame())
    }

    @Test
    fun erasingViaRetapAlsoClearsNotesOnThatCell() {
        val vm = startedVm()
        val game = vm.uiState.value.game!!
        val cell = game.blankIndices().first()
        val digit = game.solution[cell]
        // Pencil a mark on the cell, then write the digit over it, then re-tap to erase.
        vm.selectCell(cell)
        vm.toggleNotesMode()
        vm.enterDigit(digit)
        assertTrue(vm.uiState.value.game!!.notes[cell] != 0)
        vm.toggleNotesMode()
        vm.enterDigit(digit)
        assertEquals(digit, vm.uiState.value.game!!.valueAt(cell))
        assertEquals(0, vm.uiState.value.game!!.notes[cell], "placing a digit clears its own marks")
        vm.enterDigit(digit)
        val after = vm.uiState.value.game!!
        assertEquals(0, after.valueAt(cell))
        assertEquals(0, after.notes[cell])
        assertTrue(after.moveCount >= 3)
    }
}
