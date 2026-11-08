package com.vayunmathur.games.sudoku.platform

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.sudoku.data.BoardSize
import com.vayunmathur.games.sudoku.data.Difficulty
import com.vayunmathur.games.sudoku.data.GameConfig
import com.vayunmathur.games.sudoku.data.SudokuDataStore
import com.vayunmathur.games.sudoku.data.SudokuStatsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SudokuViewModelTest {

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

    private fun startedVm(config: GameConfig = GameConfig(BoardSize.SIX, Difficulty.EASY)): SudokuViewModel {
        val vm = SudokuViewModel(app)
        vm.newGame(config)
        await("game dealt") { vm.uiState.value.game != null }
        return vm
    }

    private fun blank(vm: SudokuViewModel): Int {
        val game = vm.uiState.value.game!!
        return game.givens.indices.first { game.givens[it] == 0 }
    }

    private fun given(vm: SudokuViewModel): Int {
        val game = vm.uiState.value.game!!
        return game.givens.indices.first { game.givens[it] != 0 }
    }

    @Test
    fun actionsWithNoGameAreNoOps() {
        val vm = SudokuViewModel(app)
        assertNull(vm.uiState.value.game)
        assertFalse(vm.hasActiveGame())
        vm.selectCell(0); vm.enterDigit(1); vm.clearCell(); vm.toggleNotesMode()
        vm.hint(); vm.undo(); vm.restart(); vm.giveUp(); vm.incrementTimer()
        vm.dismissAchievementNotification()
        assertNull(vm.uiState.value.game)
        assertFalse(vm.uiState.value.canUndo)
    }

    @Test
    fun newGameDealsPlayablePuzzleAndRecordsPlayed() {
        val vm = startedVm()
        val state = vm.uiState.value
        val game = assertNotNull(state.game)
        assertFalse(state.generating)
        assertEquals(BoardSize.SIX, game.size)
        assertEquals(Difficulty.EASY, game.difficulty)
        assertEquals(game.solution.size, game.givens.size)
        assertTrue(game.givens.any { it == 0 })
        assertTrue(vm.hasActiveGame())
        assertEquals(1, stats.getStats(BoardSize.SIX, Difficulty.EASY).gamesPlayed)
        assertEquals(1, vm.getStats(GameConfig(BoardSize.SIX, Difficulty.EASY)).gamesPlayed)
        assertEquals(1, vm.getSizeStats(BoardSize.SIX).gamesPlayed)
    }

    @Test
    fun selectCellTogglesAndDigitPlacementEraseAndUndo() {
        val vm = startedVm()
        val cell = blank(vm)
        val game = vm.uiState.value.game!!
        val correct = game.solution[cell]

        vm.selectCell(cell)
        assertEquals(cell, vm.uiState.value.game!!.selected)
        vm.selectCell(cell)
        assertEquals(-1, vm.uiState.value.game!!.selected, "re-selecting deselects")
        vm.selectCell(cell)

        vm.enterDigit(correct)
        var after = vm.uiState.value.game!!
        assertEquals(correct, after.valueAt(cell))
        assertEquals(1, after.moveCount)
        assertTrue(vm.uiState.value.canUndo)

        // Re-tapping the shown digit erases it.
        vm.enterDigit(correct)
        after = vm.uiState.value.game!!
        assertEquals(0, after.valueAt(cell))
        assertEquals(2, after.moveCount)

        vm.enterDigit(correct)
        vm.undo()
        after = vm.uiState.value.game!!
        assertEquals(0, after.valueAt(cell), "undo restores the pre-placement board")
        assertTrue(after.usedUndo)
        assertTrue(vm.uiState.value.canUndo, "earlier snapshots remain")

        // Given cells cannot be selected for writing or cleared.
        val givenCell = given(vm)
        vm.selectCell(givenCell)
        val before = vm.uiState.value.game!!
        vm.enterDigit((before.solution[givenCell] % before.size.side) + 1)
        vm.clearCell()
        assertEquals(before.entries, vm.uiState.value.game!!.entries)
    }

    @Test
    fun notesModeTogglesPencilMarksAndClearCellClearsEntryAndNotes() {
        val vm = startedVm()
        val cell = blank(vm)
        val game = vm.uiState.value.game!!
        vm.selectCell(cell)
        vm.toggleNotesMode()
        assertTrue(vm.uiState.value.game!!.notesMode)
        vm.enterDigit(2)
        var after = vm.uiState.value.game!!
        assertEquals(1 shl 1, after.notes[cell])
        assertEquals(0, after.valueAt(cell))
        vm.enterDigit(2)
        assertEquals(0, vm.uiState.value.game!!.notes[cell], "second note tap toggles the mark off")
        vm.enterDigit(3)
        vm.toggleNotesMode()
        assertFalse(vm.uiState.value.game!!.notesMode)
        vm.clearCell()
        after = vm.uiState.value.game!!
        assertEquals(0, after.notes[cell])
        assertEquals(0, after.valueAt(cell))
        // Clearing with nothing selected is a no-op (selection is still cell, clear again harmless).
        vm.selectCell(cell)
        val snapshot = vm.uiState.value.game!!
        vm.clearCell()
        assertEquals(snapshot.moveCount, vm.uiState.value.game!!.moveCount)
    }

    @Test
    fun hintCorrectsWrongEntryFirstThenFillsSelectedBlank() {
        val vm = startedVm()
        val cell = blank(vm)
        val game = vm.uiState.value.game!!
        val wrong = (1..game.size.side).first { it != game.solution[cell] }
        vm.selectCell(cell)
        vm.enterDigit(wrong)
        assertTrue(vm.uiState.value.game!!.isWrong(cell))

        vm.hint()
        var after = vm.uiState.value.game!!
        assertEquals(game.solution[cell], after.valueAt(cell), "hint fixes the wrong cell first")
        assertEquals(1, after.hintsUsed)
        assertEquals(cell, after.selected)

        // Fill everything except one blank via hints targeted at selection, then final hint wins.
        var guard = 0
        while (!vm.uiState.value.game!!.isComplete && guard++ < 100) {
            val next = vm.uiState.value.game!!.blankIndices().firstOrNull() ?: break
            vm.selectCell(next)
            vm.hint()
        }
        after = vm.uiState.value.game!!
        assertTrue(after.isComplete)
        assertTrue(after.isWon)
        val hints = after.hintsUsed
        vm.hint()
        assertEquals(hints, vm.uiState.value.game!!.hintsUsed, "hint after win is a no-op")
    }

    @Test
    fun hintWithoutSelectionFillsFirstBlank() {
        val vm = startedVm()
        val firstBlank = blank(vm)
        vm.hint()
        val game = vm.uiState.value.game!!
        assertEquals(game.solution[firstBlank], game.valueAt(firstBlank))
        assertEquals(firstBlank, game.selected)
        assertEquals(1, game.hintsUsed)
    }

    @Test
    fun timerIncrementsAndRestartResetsProgressButKeepsPuzzle() {
        val vm = startedVm()
        val cell = blank(vm)
        val original = vm.uiState.value.game!!
        vm.selectCell(cell)
        vm.enterDigit(original.solution[cell])
        vm.incrementTimer(); vm.incrementTimer()
        assertEquals(2, vm.uiState.value.game!!.elapsedSeconds)

        vm.restart()
        val after = vm.uiState.value.game!!
        assertEquals(original.givens, after.givens)
        assertEquals(original.solution, after.solution)
        assertEquals(List(after.size.cellCount) { 0 }, after.entries)
        assertEquals(0, after.moveCount)
        assertEquals(0, after.elapsedSeconds)
        assertEquals(0, after.hintsUsed)
        assertEquals(-1, after.selected)
        assertFalse(after.isWon)
        assertFalse(vm.uiState.value.canUndo)
        assertTrue(vm.hasActiveGame())
    }

    @Test
    fun giveUpClearsGameAndBreaksStreak() {
        stats.recordGameWon(BoardSize.SIX, Difficulty.EASY, 60)
        assertEquals(1, stats.getStats(BoardSize.SIX, Difficulty.EASY).currentWinStreak)
        val vm = startedVm()
        vm.giveUp()
        assertNull(vm.uiState.value.game)
        assertFalse(vm.hasActiveGame())
        assertEquals(0, stats.getStats(BoardSize.SIX, Difficulty.EASY).currentWinStreak)
    }

    @Test
    fun completingBoardWinsRecordsStatsAndStopsTimer() {
        val vm = startedVm()
        vm.incrementTimer()
        var guard = 0
        while (guard++ < 100) {
            val game = vm.uiState.value.game!!
            val next = game.blankIndices().firstOrNull() ?: break
            vm.selectCell(next)
            vm.enterDigit(game.solution[next])
        }
        val won = vm.uiState.value.game!!
        assertTrue(won.isComplete)
        assertTrue(won.isWon)
        assertEquals(-1, won.selected)
        assertFalse(vm.hasActiveGame())
        assertEquals(1, stats.getStats(BoardSize.SIX, Difficulty.EASY).gamesWon)
        val elapsed = won.elapsedSeconds
        vm.incrementTimer()
        assertEquals(elapsed, vm.uiState.value.game!!.elapsedSeconds, "timer stops after win")
        // Further digits are ignored on a won game.
        vm.selectCell(0)
        val entries = vm.uiState.value.game!!.entries
        vm.enterDigit(1)
        assertEquals(entries, vm.uiState.value.game!!.entries)
    }

    @Test
    fun answerModeRejectsWrongDigitsCostsLivesAndEndsGame() {
        stats.recordGameWon(BoardSize.SIX, Difficulty.EASY, 60)
        val vm = startedVm(GameConfig(BoardSize.SIX, Difficulty.EASY, answerMode = true))
        val cell = blank(vm)
        val game = vm.uiState.value.game!!
        assertTrue(game.answerMode)
        assertEquals(3, game.livesRemaining)
        val wrong = (1..game.size.side).first { it != game.solution[cell] }
        vm.selectCell(cell)

        vm.enterDigit(wrong)
        var after = vm.uiState.value.game!!
        assertEquals(0, after.valueAt(cell), "rejected digit is never placed")
        assertEquals(2, after.livesRemaining)
        assertEquals(wrong, vm.uiState.value.rejectedDigit?.digit)
        assertEquals(1L, vm.uiState.value.rejectedDigit?.attempt)
        assertFalse(vm.uiState.value.canUndo, "rejection pushes no undo history")

        vm.enterDigit(wrong)
        assertEquals(2L, vm.uiState.value.rejectedDigit?.attempt, "repeat rejection gets a new attempt")
        vm.enterDigit(wrong)
        after = vm.uiState.value.game!!
        assertEquals(0, after.livesRemaining)
        assertTrue(after.isGameOver)
        assertEquals(0, stats.getStats(BoardSize.SIX, Difficulty.EASY).currentWinStreak,
            "losing the last life records a loss")
        vm.incrementTimer()
        assertEquals(0, after.elapsedSeconds)

        // Correct digits still place in answer mode (before game over this is the path); after
        // game over input is ignored, verified by unchanged entries.
        val entries = after.entries
        vm.enterDigit(after.solution[cell])
        assertEquals(entries, vm.uiState.value.game!!.entries)

        vm.restart()
        val restarted = vm.uiState.value.game!!
        assertEquals(3, restarted.livesRemaining, "restart refills lives")
        assertFalse(restarted.isGameOver)
    }

    @Test
    fun answerModeCorrectDigitPlacesWithoutCostingLife() {
        val vm = startedVm(GameConfig(BoardSize.SIX, Difficulty.EASY, answerMode = true))
        val cell = blank(vm)
        val correct = vm.uiState.value.game!!.solution[cell]
        vm.selectCell(cell)
        vm.enterDigit(correct)
        val after = vm.uiState.value.game!!
        assertEquals(correct, after.valueAt(cell))
        assertEquals(3, after.livesRemaining)
        assertNull(vm.uiState.value.rejectedDigit)
    }

    @Test
    fun setAnswerModePersistsAndFlowReflectsItWhenCollected() {
        val vm = SudokuViewModel(app)
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { vm.answerMode.collect {} }
        try {
            assertFalse(vm.answerMode.value)
            vm.setAnswerMode(true)
            await("answerMode flow true") { vm.answerMode.value }
            assertTrue(runBlocking { SudokuDataStore(app).answerMode.first() })
            vm.setAnswerMode(false)
            await("answerMode flow false") { !vm.answerMode.value }
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun setHighlightMistakesPersistsAndFlowReflectsItWhenCollected() {
        val vm = SudokuViewModel(app)
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { vm.highlightMistakes.collect {} }
        try {
            assertTrue(vm.highlightMistakes.value)
            vm.setHighlightMistakes(false)
            await("highlightMistakes flow false") { !vm.highlightMistakes.value }
            assertFalse(runBlocking { SudokuDataStore(app).highlightMistakes.first() })
            vm.setHighlightMistakes(true)
            await("highlightMistakes flow true") { vm.highlightMistakes.value }
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun achievementsManagerIsLoadedFromAssets() {
        val vm = SudokuViewModel(app)
        assertTrue(vm.achievementsManager.achievements.any { it.id == "first_win" })
        assertTrue(vm.achievementsManager.achievements.any { it.id == "wins_50" })
        vm.dismissAchievementNotification()
        assertNull(vm.achievementsManager.newAchievement.value)
    }
}
