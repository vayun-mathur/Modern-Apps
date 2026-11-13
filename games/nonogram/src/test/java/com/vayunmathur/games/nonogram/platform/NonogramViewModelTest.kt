package com.vayunmathur.games.nonogram.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.nonogram.data.GameMode
import com.vayunmathur.games.nonogram.data.MarkMode
import com.vayunmathur.games.nonogram.data.STARTING_HEARTS
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NonogramViewModelTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    @BeforeTest fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun await(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) { if (cond()) return; Thread.sleep(25) }
        assertTrue(cond(), "timed out: $desc")
    }
    private fun loadedVm(): NonogramViewModel {
        val vm = NonogramViewModel(app)
        runBlocking { vm.dataStore.saveLevel(1); vm.dataStore.setGameMode(GameMode.CASUAL); vm.dataStore.setMarkMode(MarkMode.FILL) }
        await("game loads") { vm.uiState.value.game != null && !vm.uiState.value.generating }
        return vm
    }

    @Test fun loadsCasualPuzzleAndDefaults() {
        val vm = loadedVm()
        val s = vm.uiState.value
        assertEquals(GameMode.CASUAL, s.mode); assertEquals(1, s.level); assertEquals(MarkMode.FILL, s.markMode)
        val g = assertNotNull(s.game); assertEquals(5, g.size); assertEquals(STARTING_HEARTS, g.hearts)
        assertFalse(g.isWon); assertNotNull(vm.achievementsManager)
        assertEquals(NonogramViewModel.DAILY_KEY_PREFIX, "nonogram_daily")
        vm.dismissAchievementNotification()
    }

    @Test fun tapCorrectFillsWrongCostsHeartCrossModeNotesFree() {
        val vm = loadedVm()
        val g = vm.uiState.value.game!!
        val correct = g.puzzle.solution.indices.first { g.puzzle.solution[it] }
        val wrong = g.puzzle.solution.indices.first { !g.puzzle.solution[it] }
        vm.tapCell(correct)
        await("fill lands") { vm.uiState.value.game?.filled?.contains(correct) == true }
        vm.tapCell(correct) // locked: no-op
        vm.tapCell(wrong)
        await("heart spent") { vm.uiState.value.game?.hearts == STARTING_HEARTS - 1 }
        assertTrue(vm.uiState.value.game!!.revealedBlanks.contains(wrong))
        // Cross mode: tap only notes, even on a picture cell, never costs.
        vm.setMarkMode(MarkMode.CROSS)
        await("cross mode") { vm.uiState.value.markMode == MarkMode.CROSS }
        val noteCell = vm.uiState.value.game!!.puzzle.solution.indices.first { vm.uiState.value.game!!.puzzle.solution[it] && it !in vm.uiState.value.game!!.filled }
        vm.tapCell(noteCell)
        await("note placed") { vm.uiState.value.game?.crossed?.contains(noteCell) == true }
        assertEquals(STARTING_HEARTS - 1, vm.uiState.value.game!!.hearts)
        vm.tapCell(noteCell)
        await("note removed") { vm.uiState.value.game?.crossed?.contains(noteCell) == false }
        // Long-press in cross mode performs the fill action.
        vm.crossCell(noteCell)
        await("long press fills") { vm.uiState.value.game?.filled?.contains(noteCell) == true }
    }

    @Test fun longPressInFillModeNotesAndRestartClears() {
        val vm = loadedVm()
        val g = vm.uiState.value.game!!
        val cell = g.puzzle.solution.indices.first { !g.puzzle.solution[it] }
        vm.crossCell(cell)
        await("long-press note") { vm.uiState.value.game?.crossed?.contains(cell) == true }
        assertEquals(STARTING_HEARTS, vm.uiState.value.game!!.hearts)
        vm.restartLevel()
        await("restart clears") { vm.uiState.value.game?.crossed?.isEmpty() == true }
    }

    private fun winBoard(vm: NonogramViewModel) {
        val g = vm.uiState.value.game!!
        for (i in g.puzzle.solution.indices.filter { g.puzzle.solution[it] }) vm.tapCell(i)
        await("won") { vm.uiState.value.game?.isWon == true }
    }

    @Test fun nextLevelIgnoresAnUnsolvedBoard() {
        val vm = loadedVm()
        vm.nextLevel()
        Thread.sleep(200)
        assertEquals(1, vm.uiState.value.level, "Next only exists on a solved board")
        assertEquals(1, runBlocking { vm.dataStore.currentLevel.first() })
    }

    @Test fun doubleTappingNextAdvancesExactlyOneLevel() {
        val vm = loadedVm()
        winBoard(vm)
        vm.nextLevel()
        // The first tap flags the load straight away, so the second is refused rather than
        // reading the already-raised level counter and writing level 3.
        assertTrue(vm.uiState.value.generating)
        vm.nextLevel()
        await("level 2 loaded") {
            vm.uiState.value.game?.level == 2 && !vm.uiState.value.generating
        }
        // A late tap that lands on the fresh, unsolved board is ignored too.
        vm.nextLevel()
        Thread.sleep(200)
        assertEquals(2, runBlocking { vm.dataStore.currentLevel.first() })
        assertEquals(2, vm.uiState.value.level)
    }

    @Test fun nextLevelAdvancesAndDailyModeLoads() {
        val vm = loadedVm()
        winBoard(vm)
        vm.nextLevel()
        await("level 2") { vm.uiState.value.level == 2 && vm.uiState.value.game != null && !vm.uiState.value.generating }
        vm.setGameMode(GameMode.DAILY)
        await("daily loads") { vm.uiState.value.mode == GameMode.DAILY && vm.uiState.value.game?.size == 10 }
        // nextLevel is a no-op in daily mode.
        vm.nextLevel()
        assertEquals(GameMode.DAILY, vm.uiState.value.mode)
        vm.setGameMode(GameMode.CASUAL)
        await("back to casual") { vm.uiState.value.mode == GameMode.CASUAL && vm.uiState.value.game != null }
    }

    @Test fun winningBoardRecordsCompletionOnce() {
        val vm = loadedVm()
        val before = runBlocking { vm.dataStore.puzzlesCompleted.first() }
        val g = vm.uiState.value.game!!
        for (i in g.puzzle.solution.indices.filter { g.puzzle.solution[it] }) vm.tapCell(i)
        await("won") { vm.uiState.value.game?.isWon == true }
        await("completion recorded") { runBlocking { vm.dataStore.puzzlesCompleted.first() } == before + 1 }
        // Taps after game over are ignored.
        val hearts = vm.uiState.value.game!!.hearts
        vm.tapCell(g.puzzle.solution.indices.first { !g.puzzle.solution[it] })
        assertEquals(hearts, vm.uiState.value.game!!.hearts)
    }

    @Test fun winningDailyRecordsDailyWin() {
        val vm = loadedVm()
        vm.setGameMode(GameMode.DAILY)
        await("daily ready") { vm.uiState.value.mode == GameMode.DAILY && vm.uiState.value.game != null && !vm.uiState.value.generating }
        val before = runBlocking { vm.dataStore.puzzlesCompleted.first() }
        val g = vm.uiState.value.game!!
        for (i in g.puzzle.solution.indices.filter { g.puzzle.solution[it] }) vm.tapCell(i)
        await("daily won") { vm.uiState.value.game?.isWon == true }
        await("daily completion recorded") { runBlocking { vm.dataStore.puzzlesCompleted.first() } > before }
    }

    @Test fun retryGenerationReloadsBoard() {
        val vm = loadedVm()
        val before = vm.uiState.value.game!!
        vm.retryGeneration()
        await("reload after retry") { !vm.uiState.value.generating && vm.uiState.value.game != null }
        assertFalse(vm.uiState.value.generationFailed)
        assertEquals(before.puzzle, vm.uiState.value.game!!.puzzle)
    }

    @Test fun reminderActionsUpdateState() {
        val vm = loadedVm()
        val enabledSeen = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val minutesSeen = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined)
        val j1 = scope.launch { vm.reminderEnabled.collect { enabledSeen += it } }
        val j2 = scope.launch { vm.reminderMinutesOfDay.collect { minutesSeen += it } }
        vm.setReminderEnabled(true)
        await("reminder on") { enabledSeen.contains(true) }
        vm.setReminderTime(7, 30)
        await("reminder time") { minutesSeen.contains(450L) }
        vm.setReminderEnabled(false)
        await("reminder off") { enabledSeen.lastOrNull() == false }
        j1.cancel(); j2.cancel()
    }

    @Test fun contractDefaultsAndNoop() {
        val s = NonogramUiState()
        assertEquals(null, s.game); assertEquals(GameMode.CASUAL, s.mode); assertEquals(1, s.level); assertFalse(s.generating)
        val ss = SettingsUiState(); assertFalse(ss.reminderEnabled); assertEquals(20, ss.reminderHour)
        NonogramGameActions.Noop.tapCell(0); NonogramGameActions.Noop.crossCell(0)
        NonogramGameActions.Noop.setMarkMode(MarkMode.CROSS); NonogramGameActions.Noop.nextLevel()
        NonogramGameActions.Noop.restartLevel(); NonogramGameActions.Noop.setGameMode(GameMode.DAILY)
        NonogramGameActions.Noop.retryGeneration()
        SettingsActions.Noop.setReminderEnabled(true); SettingsActions.Noop.setReminderTime(1, 2)
        assertEquals(listOf("settings", "datastore_default"), AppBackupAgent().datastoreNames)
    }

    @Test fun achievementsManagerBackfillDoesNotCrash() {
        val vm = loadedVm()
        vm.achievementsManager.checkExistingAchievements()
        assertTrue(vm.achievementsManager.achievements.any { it.id == "first_puzzle" })
        assertTrue(vm.achievementsManager.achievements.any { it.id == "daily_streak_30" })
    }
}
