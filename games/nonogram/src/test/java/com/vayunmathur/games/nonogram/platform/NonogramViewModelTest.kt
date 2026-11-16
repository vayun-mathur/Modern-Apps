package com.vayunmathur.games.nonogram.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.nonogram.data.GameMode
import com.vayunmathur.games.nonogram.data.MarkMode
import com.vayunmathur.games.nonogram.data.STARTING_HEARTS
import java.util.concurrent.locks.LockSupport
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
    private val vmStore = androidx.lifecycle.ViewModelStore()
    @BeforeTest fun setUp() {
        retrying { Dispatchers.setMain(Dispatchers.Unconfined) }
        // The reminder path (DailyPuzzleReminder rescheduling, reached
        // from the ViewModel and the achievements/daily machinery) calls
        // WorkManager.getInstance, which throws when uninitialized — and
        // that throw lands inside a DataStore write, killing the shared
        // store's write actor for every later test in this JVM (boards
        // then never load or persist: the rotating "game loads" /
        // "persisted" timeouts). Sibling suites initialize it up front.
        runCatching {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
    }

    /** Registers [vm] so tearDown can cancel its viewModelScope (see below). */
    private fun track(vm: NonogramViewModel): NonogramViewModel {
        vmStore.put("vm${System.identityHashCode(vm)}", vm)
        return vm
    }

    /**
     * Drains the store before Main is reset.
     *
     * The DataStore outlives each test, and runs an edit's transform in the caller's context: an edit
     * a ViewModel issued on Main that is still queued when Main goes back to the paused Robolectric
     * looper never runs, and every later edit queues behind it. A same-value write is a barrier —
     * edits are applied in order — and the second catches anything the first one's echo set off.
     */
    @AfterTest fun tearDown() {
        val store = com.vayunmathur.games.nonogram.data.NonogramDataStore(app)
        // Drain, then clear, then drain again. Uncleared ViewModels stay
        // subscribed to the shared store, so a later test's mode/level
        // write makes EVERY stale VM regenerate a (possibly daily-sized)
        // puzzle at once and the live VM's own generation queues behind
        // them — the "game loads" timeouts. But clearing while a board
        // or win write is still in flight can cancel that DataStore edit
        // mid-flight, so a barrier edit (which the store's actor applies
        // after everything already queued) goes first, and once more
        // after the clear for anything the cancellation shook loose.
        runCatching { blocking { store.setMarkMode(store.markMode.first()) } }
        vmStore.clear()
        repeat(2) {
            runCatching { blocking { store.setMarkMode(store.markMode.first()) } }
            Thread.sleep(50)
        }
        retrying { Dispatchers.resetMain() }
    }

    /** set/resetMain throw if a background coroutine happens to be using Main at that instant. */
    private fun retrying(action: () -> Unit) {
        var attempts = 0
        while (attempts++ < RETRIES && runCatching(action).isFailure) Thread.sleep(10)
        if (attempts > RETRIES) action()
    }

    private companion object {
        const val RETRIES = 20
        const val AWAIT_STEPS = 2000
        const val AWAIT_STEP_NANOS = 25_000_000L
    }

    /**
     * Polls [cond] for up to [AWAIT_STEPS] steps.
     *
     * Counted in steps rather than against System.currentTimeMillis(): Robolectric substitutes its
     * own clock for test code, which can run far ahead of real time, so a clock-based deadline could
     * give up within milliseconds while a background write was still landing.
     */
    @Volatile
    private var burnSink: Long = 0

    private fun stepSleep() {
        // Robolectric virtualises every clock, park, sleep, wait and
        // scheduled delay reachable from this suite (they all return in
        // ~0.2ms, advancing the substituted clock), so an await step
        // cannot literally wait: step budgets burned out in ~0.16s of
        // real time, before slow-but-healthy IO writes landed on a
        // loaded box — hence the rotating "timed out" failures. The
        // only real time available is CPU work, so a step burns a
        // fixed dependent-arithmetic loop (~10ms on a fast core; a
        // slower CI core just makes each step longer, which is the
        // safe direction). The volatile sink keeps the JIT honest.
        var x = burnSink + 0x9E3779B97F4A7C15uL.toLong()
        for (i in 0 until 20_000_000) {
            x = x * 31 + i
        }
        burnSink = x
    }



    private fun await(desc: String, maxSteps: Int = AWAIT_STEPS, cond: () -> Boolean) {
        // Assert on the observed value, not a fresh re-read: the old
        // shape evaluated cond() once to leave the loop and again in
        // assertTrue, and a store value that flipped between those two
        // reads (or a transiently failing second read) failed a wait
        // that had actually succeeded — "timed out after 1 steps".
        var steps = 0
        var ok = cond()
        while (!ok && steps++ < maxSteps) {
            stepSleep()
            ok = cond()
        }
        assertTrue(ok, "timed out: $desc after $steps steps")
    }

    /**
     * runBlocking for one-shot store reads. Deliberately NO withTimeout:
     * under Robolectric the timeout's delay runs on the substituted
     * clock and fires effectively instantly, so any read that needs an
     * async DataStore round-trip loses the race and the poll conditions
     * in [await] could never observe a landed write (the "undo
     * persisted" flake: every poll timed out before the emission
     * arrived). The store's write actor is kept alive by initializing
     * WorkManager in setUp, so reads complete; [await]'s step budget
     * bounds the total wait instead.
     */
    private fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }

    private fun loadedVm(level: Int = 1): NonogramViewModel {
        // Seed the store BEFORE creating the ViewModel: writing level,
        // mode and mark mode after construction makes the VM's session
        // combine emit (and collectLatest cancel) up to three generation
        // passes — each a solver-checked search — before the real one,
        // which on a loaded 4-core CI box starved the 20s load await.
        // Generation itself gets a larger step budget for the same reason:
        // it is genuine CPU work whose duration scales with the box.
        val store = com.vayunmathur.games.nonogram.data.NonogramDataStore(app)
        // No withTimeout here: cancelling a DataStore edit mid-flight
        // (blocking's 15s cap under load) can strand the store's actor
        // for the next collector. These three edits are tiny; let them land.
        runBlocking { store.saveLevel(level); store.setGameMode(GameMode.CASUAL); store.setMarkMode(MarkMode.FILL) }
        val vm = track(NonogramViewModel(app))
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

    @Test fun movesUpdateTheBoardBeforeAnyWriteLands() {
        val vm = loadedVm()
        val g = vm.uiState.value.game!!
        val correct = g.puzzle.solution.indices.first { g.puzzle.solution[it] }
        // No await: the board on screen changes in the same call as the tap.
        vm.tapCell(correct)
        assertTrue(correct in vm.uiState.value.game!!.filled, "fill must show before DataStore echoes it")
        vm.setMarkMode(MarkMode.CROSS)
        assertEquals(MarkMode.CROSS, vm.uiState.value.markMode, "mode switch applies at once")
        val note = g.puzzle.solution.indices.first { it != correct }
        vm.tapCell(note)
        assertTrue(note in vm.uiState.value.game!!.crossed)
        vm.tapCell(note)
        assertFalse(note in vm.uiState.value.game!!.crossed, "second quick tap must clear the X, not add it again")
        // And the store catches up to what is on screen.
        await("persisted") {
            blocking { vm.dataStore.filledCells.first() } == setOf(correct) &&
                blocking { vm.dataStore.crossedCells.first() }.isEmpty()
        }
        assertTrue(correct in vm.uiState.value.game!!.filled)
        assertFalse(note in vm.uiState.value.game!!.crossed)
    }

    @Test fun rapidMovesAllPersistInOrder() {
        val vm = loadedVm()
        val g = vm.uiState.value.game!!
        val picture = g.puzzle.solution.indices.filter { g.puzzle.solution[it] }
        val target = picture.take(picture.size - 1)
        for (i in target) vm.tapCell(i)
        assertEquals(target.toSet(), vm.uiState.value.game!!.filled)
        await("last snapshot wins") { blocking { vm.dataStore.filledCells.first() } == target.toSet() }
        // A fresh ViewModel (a relaunch) loads exactly what was played.
        val again = track(NonogramViewModel(app))
        await("reload") { again.uiState.value.game?.filled == target.toSet() }
    }

    @Test fun dragStrokeFillsALineAndChargesOneHeartForAMistake() {
        val vm = loadedVm()
        val g = vm.uiState.value.game!!
        val n = g.size
        // Pick a row with a blank somewhere after a picture cell, and drag across the whole row.
        val row = (0 until n).first { r ->
            val line = (0 until n).map { g.puzzle.solution[r * n + it] }
            line.indexOf(true).let { it >= 0 && line.drop(it).contains(false) }
        }
        val line = (0 until n).map { row * n + it }
        val firstPicture = line.first { g.puzzle.solution[it] }
        vm.beginStroke(firstPicture, alternate = false)
        for (i in line.filter { it > firstPicture }) vm.extendStroke(i)
        vm.endStroke()
        val after = vm.uiState.value.game!!
        val firstWrong = line.first { it > firstPicture && !g.puzzle.solution[it] }
        assertEquals(line.filter { it in firstPicture until firstWrong }.toSet(), after.filled)
        assertEquals(setOf(firstWrong), after.revealedBlanks)
        assertEquals(STARTING_HEARTS - 1, after.hearts, "a stroke costs at most one heart")
    }

    @Test fun longPressStrokeUsesTheOtherMark() {
        val vm = loadedVm()
        val n = vm.uiState.value.game!!.size
        vm.beginStroke(0, alternate = true)
        vm.extendStroke(n - 1)
        vm.endStroke()
        // Fill mode + long press = a cross stroke: the whole first row is crossed, for free.
        assertEquals((0 until n).toSet(), vm.uiState.value.game!!.crossed)
        assertEquals(STARTING_HEARTS, vm.uiState.value.game!!.hearts)
    }

    @Test fun undoRevertsTheLastStrokeWithoutRefundingHearts() {
        val vm = loadedVm()
        assertFalse(vm.uiState.value.canUndo)
        val g = vm.uiState.value.game!!
        val correct = g.puzzle.solution.indices.first { g.puzzle.solution[it] }
        val wrong = g.puzzle.solution.indices.first { !g.puzzle.solution[it] }
        vm.tapCell(correct)
        assertTrue(vm.uiState.value.canUndo)
        vm.tapCell(wrong) // only reveals: nothing for undo to give back, so not pushed
        assertEquals(STARTING_HEARTS - 1, vm.uiState.value.game!!.hearts)
        vm.undo()
        val s = vm.uiState.value.game!!
        assertFalse(correct in s.filled, "the fill is undone")
        assertTrue(wrong in s.revealedBlanks, "the revealed mistake stays")
        assertEquals(STARTING_HEARTS - 1, s.hearts, "undo never refunds a heart")
        assertFalse(vm.uiState.value.canUndo)
        vm.undo() // empty: no-op
        assertEquals(s, vm.uiState.value.game)
        // Undo is persisted like any move. A transient failure of the
        // read itself (the Robolectric environment occasionally
        // interrupts the polling runBlocking) must not abort the wait:
        // it just means "not landed yet". The final condition still
        // requires the store to actually read empty.
        await("undo persisted") {
            runCatching { blocking { vm.dataStore.filledCells.first() } }
                .getOrDefault(setOf(-1))
                .isEmpty()
        }
        // Restart wipes the history.
        vm.crossCell(correct)
        assertTrue(vm.uiState.value.canUndo)
        vm.restartLevel()
        assertFalse(vm.uiState.value.canUndo)
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
        assertEquals(1, blocking { vm.dataStore.currentLevel.first() })
    }

    @Test fun doubleTappingNextAdvancesExactlyOneLevel() {
        val vm = loadedVm()
        winBoard(vm)
        vm.nextLevel()
        // The first tap flags the load straight away, so the second is refused rather than
        // reading the already-raised level counter and writing level 3. (No assert on the
        // transient `generating` flag itself: the level-2 load it starts can legitimately
        // complete before this thread's next line runs, which made that assert a coin flip.
        // The refusal is what the level assertions below prove.)
        vm.nextLevel()
        await("level 2 loaded") {
            vm.uiState.value.game?.level == 2 && !vm.uiState.value.generating
        }
        // A late tap that lands on the fresh, unsolved board is ignored too.
        vm.nextLevel()
        Thread.sleep(200)
        assertEquals(2, blocking { vm.dataStore.currentLevel.first() })
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
        // A level no other test wins: win keys are banked in the store, which outlives each test,
        // so reusing level 1 would find this win already counted.
        val vm = loadedVm(level = 7)
        val before = blocking { vm.dataStore.puzzlesCompleted.first() }
        val g = vm.uiState.value.game!!
        for (i in g.puzzle.solution.indices.filter { g.puzzle.solution[it] }) vm.tapCell(i)
        await("won") { vm.uiState.value.game?.isWon == true }
        await("completion recorded") { blocking { vm.dataStore.puzzlesCompleted.first() } == before + 1 }
        // Taps after game over are ignored.
        val hearts = vm.uiState.value.game!!.hearts
        vm.tapCell(g.puzzle.solution.indices.first { !g.puzzle.solution[it] })
        assertEquals(hearts, vm.uiState.value.game!!.hearts)
    }

    @Test fun winningDailyRecordsDailyWin() {
        val vm = loadedVm()
        vm.setGameMode(GameMode.DAILY)
        await("daily ready") { vm.uiState.value.mode == GameMode.DAILY && vm.uiState.value.game != null && !vm.uiState.value.generating }
        val before = blocking { vm.dataStore.puzzlesCompleted.first() }
        val g = vm.uiState.value.game!!
        for (i in g.puzzle.solution.indices.filter { g.puzzle.solution[it] }) vm.tapCell(i)
        await("daily won") { vm.uiState.value.game?.isWon == true }
        await("daily completion recorded") { blocking { vm.dataStore.puzzlesCompleted.first() } > before }
        // Let the rest of the win land before the test ends: the streak write reloads every live
        // ViewModel, and a store edit still in flight when Dispatchers.Main is reset would wait on
        // the paused Robolectric main looper and wedge the store for the following tests.
        await("daily streak recorded") {
            vm.uiState.value.dailyDone && vm.uiState.value.dailyStreak >= 1 && !vm.uiState.value.generating
        }
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
        // checkExistingAchievements launches on Dispatchers.IO; asserting
        // its unlocks synchronously raced that work and lost under load.
        await("backfill unlocks land") {
            vm.achievementsManager.achievements.any { it.id == "first_puzzle" } &&
                vm.achievementsManager.achievements.any { it.id == "daily_streak_30" }
        }
        assertTrue(vm.achievementsManager.achievements.any { it.id == "first_puzzle" })
        assertTrue(vm.achievementsManager.achievements.any { it.id == "daily_streak_30" })
    }
}
