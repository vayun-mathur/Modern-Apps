package com.vayunmathur.games.nonogram.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.nonogram.data.GameMode
import com.vayunmathur.games.nonogram.data.MarkMode
import com.vayunmathur.games.nonogram.data.STARTING_HEARTS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import android.os.Looper
import com.vayunmathur.testsupport.idleMainLooper
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
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
        // No Dispatchers.setMain(Unconfined) here (see awaitState):
        // this suite used to substitute an unconfined Main, which
        // races the sandbox looper and loses load wakeups under CPU
        // contention. The ViewModel runs on the real Robolectric Main
        // looper like every sibling VM suite, and the waits pump it.
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
     * Drains the store before the ViewModel store is cleared.
     *
     * The DataStore outlives each test, and runs an edit's transform in the caller's context: an edit
     * a ViewModel issued on Main that is still queued on the paused Robolectric
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
            // Each barrier edit is a blocking DataStore round-trip that
            // returns only once applied, so no sleep is needed between them.
            runCatching { blocking { store.setMarkMode(store.markMode.first()) } }
        }
    }


    private companion object {
        const val RETRIES = 20
        const val PUMP_STEP_MS = 100L
        const val PARK_MS = 50L
        const val MAX_PUMP_STEPS = 6_000
    }
    /**
     * Step-bounded pumped wait on a terminal [condition]: every step
     * advances the paused main looper (running the ViewModel/DataStore
     * work queued there, including delayed tasks), then parks briefly
     * so Dispatchers.IO/Default producers get a CPU slice on a
     * contended box. The bound is pumping steps, never wall-clock
     * seconds that CI contention can burn before any work runs, and
     * the failure is raised only after [MAX_PUMP_STEPS] pumps.
     */
    private fun pumpUntil(description: String, condition: () -> Boolean) {
        var steps = 0
        while (true) {
            // Pump first, then check: the loops this replaces always
            // idled at least once before judging the condition, and a
            // condition that is already true at entry can still need
            // that one pump for the work behind it to settle.
            //
            // Idle WITHOUT advancing the looper clock on most steps.
            // Advancing it every step is what awaitFlow (below) proved
            // keeps retriggering the session combine while a generation
            // runs on Dispatchers.Default: each clock-advanced idle lets
            // a delayed re-emission land, collectLatest cancels the
            // in-flight generation, and under CI CPU contention the
            // replacement never finishes either — generating=true
            // forever (fd5ebb27d: rapidMovesAllPersistInOrder burned
            // both 6,000-step budgets, ~603s, with every worker idle
            // between cancellations). Delayed posts still get their
            // chance: the clock advances every 20th step, as in
            // awaitFlow.
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (steps % 20 == 19) {
                Shadows.shadowOf(Looper.getMainLooper())
                    .idleFor(PUMP_STEP_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            if (condition()) break
            java.util.concurrent.locks.LockSupport.parkNanos(
                java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(PARK_MS),
            )
            if (++steps >= MAX_PUMP_STEPS) {
                throw AssertionError("timed out (step-bounded): $description after $MAX_PUMP_STEPS pumps")
            }
        }
    }



    /**
     * Waits for a terminal [NonogramUiState] value: a pumped-looper
     * condition wait on `uiState.value` (see below), never a sleep poll
     * and never a main-thread runBlocking that parks the thread its own
     * producers are queued behind.
     */
    private fun awaitState(desc: String, vm: NonogramViewModel, cond: (NonogramUiState) -> Boolean) {
        // Pumped looper wait on the StateFlow's terminal value, never
        // runBlocking on this thread: the ViewModel's collectors and
        // DataStore mirrors run on viewModelScope (the paused main
        // looper), so parking main until an emission arrives waits
        // forever for the parked thread itself — this wedged for 9+
        // minutes under a 5-CPU cap in doubleTappingNextAdvancesExactlyOneLevel,
        // while passing locally. Idling the looper on every check lets
        // the queued producers run until the same terminal state lands.
        try {
            pumpUntil(desc) { cond(vm.uiState.value) }
        } catch (firstTimeout: Throwable) {
            // One barrier re-drive before giving up: a stalled load is a
            // generation cancelled by collectLatest whose replacement
            // waits on a session re-emission that never comes (every
            // worker idle, generating=true forever — seen under a 5-CPU
            // cap in roughly one method per full-suite run). A
            // same-value DataStore edit is applied by the store's actor
            // after everything queued (the tearDown barrier mechanism),
            // re-emits the session, and restarts the load; the terminal
            // condition asserted afterwards is unchanged.
            try {
                blocking { vm.dataStore.setMarkMode(vm.dataStore.markMode.first()) }
                pumpUntil(desc) { cond(vm.uiState.value) }
            } catch (e: Throwable) {
                throw AssertionError("timed out: $desc; last state=${vm.uiState.value}", firstTimeout)
            }
        }
    }

    /**
     * Collects a [flow] off-main until [predicate] holds. This thread
     * only runs due main-looper tasks (idle, no clock advance) and
     * parks in real-time slices: advancing the looper clock while a
     * puzzle generation runs on Dispatchers.Default kept retriggering
     * the session combine and the generation never landed (the two
     * winning* failures under a 5-CPU cap), while a bare parked
     * runBlocking could wait on looper-queued producers forever.
     * Subscribing also starts WhileSubscribed upstreams, which a
     * `.value` check alone never would.
     */
    private fun <T> awaitFlow(desc: String, flow: kotlinx.coroutines.flow.StateFlow<T>, predicate: (T) -> Boolean) {
        val done = CompletableDeferred<Unit>()
        val job = CoroutineScope(Dispatchers.Default).launch {
            try { flow.first(predicate); done.complete(Unit) }
            catch (e: Throwable) { if (!done.isCompleted) done.completeExceptionally(e) }
        }
        try {
            var steps = 0
            while (true) {
                idleMainLooper()
                // Delayed main-looper posts (resumptions the Android
                // dispatcher scheduled ahead, WhileSubscribed windows)
                // never run on idle() alone — a load stalled exactly
                // there, every worker idle — but advancing the clock on
                // every step also kept retriggering the session
                // combine and cancelling generations. Advance it only
                // every 20th slice: delayed posts eventually run,
                // generations on Default are left alone in between.
                if (steps % 20 == 19) {
                    Shadows.shadowOf(Looper.getMainLooper())
                        .idleFor(250, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
                if (done.isCompleted) break
                java.util.concurrent.locks.LockSupport.parkNanos(
                    java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(50),
                )
                if (++steps >= MAX_PUMP_STEPS) {
                    throw AssertionError("timed out (step-bounded): $desc after $MAX_PUMP_STEPS waits")
                }
            }
            done.getCompletionExceptionOrNull()?.let { throw it }
        } finally {
            job.cancel()
        }
    }

    /** Waits for a terminal DataStore state on the store's own flow. */
    private fun awaitStore(desc: String, cond: suspend () -> Unit) {
        try {
            blocking { cond() }
        } catch (e: Throwable) {
            throw AssertionError("timed out: $desc", e)
        }
    }
    // (awaitStore rides on the pumped [blocking] below, so the flow
    // collection runs off-main while this thread idles the looper for
    // the ViewModel writers queued on it.)

    /**
     * One-shot store reads / barrier edits, run on Dispatchers.IO while
     * this thread pumps the paused main looper until they land.
     * Deliberately NO withTimeout (see the original note: under
     * Robolectric a timeout's delay runs on the substituted clock and
     * fires effectively instantly) and NO bare runBlocking either:
     * parking the main thread can wait forever on ViewModel/DataStore
     * work queued in that same looper (the 5-CPU wedge above). The
     * store's write actor is kept alive by initializing WorkManager in
     * setUp, so reads complete once the looper is pumped.
     */
    private fun <T> blocking(block: suspend () -> T): T {
        val done = CompletableDeferred<Unit>()
        val result = java.util.concurrent.atomic.AtomicReference<T?>()
        CoroutineScope(Dispatchers.IO).launch {
            try { result.set(block()); done.complete(Unit) }
            catch (e: Throwable) { done.completeExceptionally(e) }
        }
        pumpUntil("store read") { done.isCompleted }
        done.getCompletionExceptionOrNull()?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result.get() as T
    }

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
        blocking { store.saveLevel(level); store.setGameMode(GameMode.CASUAL); store.setMarkMode(MarkMode.FILL) }
        val vm = track(NonogramViewModel(app))
        awaitState("game loads", vm) { it.game != null && !it.generating }
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
        awaitState("fill lands", vm) { it.game?.filled?.contains(correct) == true }
        vm.tapCell(correct) // locked: no-op
        vm.tapCell(wrong)
        awaitState("heart spent", vm) { it.game?.hearts == STARTING_HEARTS - 1 }
        assertTrue(vm.uiState.value.game!!.revealedBlanks.contains(wrong))
        // Cross mode: tap only notes, even on a picture cell, never costs.
        vm.setMarkMode(MarkMode.CROSS)
        awaitState("cross mode", vm) { it.markMode == MarkMode.CROSS }
        val noteCell = vm.uiState.value.game!!.puzzle.solution.indices.first { vm.uiState.value.game!!.puzzle.solution[it] && it !in vm.uiState.value.game!!.filled }
        vm.tapCell(noteCell)
        awaitState("note placed", vm) { it.game?.crossed?.contains(noteCell) == true }
        assertEquals(STARTING_HEARTS - 1, vm.uiState.value.game!!.hearts)
        vm.tapCell(noteCell)
        awaitState("note removed", vm) { it.game?.crossed?.contains(noteCell) == false }
        // Long-press in cross mode performs the fill action.
        vm.crossCell(noteCell)
        awaitState("long press fills", vm) { it.game?.filled?.contains(noteCell) == true }
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
        awaitStore("persisted") {
            combine(vm.dataStore.filledCells, vm.dataStore.crossedCells) { f, c -> f to c }
                .first { (f, c) -> f == setOf(correct) && c.isEmpty() }
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
        awaitStore("last snapshot wins") { vm.dataStore.filledCells.first { it == target.toSet() } }
        // A fresh ViewModel (a relaunch) loads exactly what was played.
        val again = track(NonogramViewModel(app))
        awaitState("reload", again) { it.game?.filled == target.toSet() }
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
        // Undo is persisted like any move: suspend on the store flow
        // until the empty board is the emitted terminal state.
awaitStore("undo persisted") { vm.dataStore.filledCells.first { it.isEmpty() } }
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
        awaitState("long-press note", vm) { it.game?.crossed?.contains(cell) == true }
        assertEquals(STARTING_HEARTS, vm.uiState.value.game!!.hearts)
        vm.restartLevel()
        awaitState("restart clears", vm) { it.game?.crossed?.isEmpty() == true }
    }

    private fun winBoard(vm: NonogramViewModel) {
        val g = vm.uiState.value.game!!
        for (i in g.puzzle.solution.indices.filter { g.puzzle.solution[it] }) vm.tapCell(i)
        awaitState("won", vm) { it.game?.isWon == true }
    }

    @Test fun nextLevelIgnoresAnUnsolvedBoard() {
        val vm = loadedVm()
        vm.nextLevel()
        // No wait: nextLevel on an unsolved board returns synchronously
        // without launching any write (the isWon guard), so the level is
        // already settled on the next line.
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
        awaitState("level 2 loaded", vm) {
            it.game?.level == 2 && !it.generating
        }
        // A late tap that lands on the fresh, unsolved board is ignored too.
        vm.nextLevel()
        // As above, the late tap is a synchronous no-op on the unsolved
        // board, so the stored level is already terminal.
        assertEquals(2, blocking { vm.dataStore.currentLevel.first() })
        assertEquals(2, vm.uiState.value.level)
    }

    @Test fun nextLevelAdvancesAndDailyModeLoads() {
        val vm = loadedVm()
        winBoard(vm)
        vm.nextLevel()
        awaitState("level 2", vm) { it.level == 2 && it.game != null && !it.generating }
        vm.setGameMode(GameMode.DAILY)
        awaitState("daily loads", vm) { it.mode == GameMode.DAILY && it.game?.size == 10 }
        // nextLevel is a no-op in daily mode.
        vm.nextLevel()
        assertEquals(GameMode.DAILY, vm.uiState.value.mode)
        vm.setGameMode(GameMode.CASUAL)
        awaitState("back to casual", vm) { it.mode == GameMode.CASUAL && it.game != null }
    }

    @Test fun winningBoardRecordsCompletionOnce() {
        // A level no other test wins: win keys are banked in the store, which outlives each test,
        // so reusing level 1 would find this win already counted.
        val vm = loadedVm(level = 7)
        val before = blocking { vm.dataStore.puzzlesCompleted.first() }
        val g = vm.uiState.value.game!!
        for (i in g.puzzle.solution.indices.filter { g.puzzle.solution[it] }) vm.tapCell(i)
        awaitState("won", vm) { it.game?.isWon == true }
        awaitStore("completion recorded") { vm.dataStore.puzzlesCompleted.first { it == before + 1 } }
        // Taps after game over are ignored.
        val hearts = vm.uiState.value.game!!.hearts
        vm.tapCell(g.puzzle.solution.indices.first { !g.puzzle.solution[it] })
        assertEquals(hearts, vm.uiState.value.game!!.hearts)
    }

    @Test fun winningDailyRecordsDailyWin() {
        val vm = loadedVm()
        vm.setGameMode(GameMode.DAILY)
        awaitState("daily ready", vm) { it.mode == GameMode.DAILY && it.game != null && !it.generating }
        val before = blocking { vm.dataStore.puzzlesCompleted.first() }
        val g = vm.uiState.value.game!!
        for (i in g.puzzle.solution.indices.filter { g.puzzle.solution[it] }) vm.tapCell(i)
        awaitState("daily won", vm) { it.game?.isWon == true }
        awaitStore("daily completion recorded") { vm.dataStore.puzzlesCompleted.first { it > before } }
        // Let the rest of the win land before the test ends: the streak write reloads every live
        // ViewModel, and a store edit still in flight when the ViewModel store is cleared would wait on
        // the paused Robolectric main looper and wedge the store for the following tests.
        awaitState("daily streak recorded", vm) {
            it.dailyDone && it.dailyStreak >= 1 && !it.generating
        }
    }

    @Test fun completingALineAutoCrossesAndPersistsThenUndoRemoves() {
        val vm = loadedVm()
        val g = vm.uiState.value.game!!
        val n = g.size
        // Fill every picture cell of the first row, one tap (stroke) each.
        val rowCells = (0 until n).map { it }
        val pictureInRow = rowCells.filter { g.puzzle.solution[it] }
        assertTrue(pictureInRow.isNotEmpty())
        for (i in pictureInRow) vm.tapCell(i)
        val after = vm.uiState.value.game!!
        val rowBlanks = rowCells.filterNot { g.puzzle.solution[it] }
        assertTrue(rowBlanks.all { it in after.autoCrossed },
            "blank cells of a completed row are auto-crossed: blanks=$rowBlanks auto=${after.autoCrossed}")
        assertTrue(rowBlanks.none { it in after.crossed }, "auto crosses are not manual crosses")
        // Persisted as real cell state under their own key.
        awaitStore("auto crosses persisted") {
            vm.dataStore.autoCrossedCells.first { it == after.autoCrossed }
        }
        // A fresh ViewModel (a relaunch) shows the same crosses on load.
        val reloaded = track(NonogramViewModel(app))
        awaitState("reload shows auto crosses", reloaded) {
            it.game?.autoCrossed == after.autoCrossed && it.game?.filled == after.filled
        }
        // Undoing the last fill breaks the row again, and its auto crosses go.
        vm.undo()
        val undone = vm.uiState.value.game!!
        assertTrue(pictureInRow.last() !in undone.filled)
        assertEquals(
            com.vayunmathur.games.nonogram.domain.NonogramClues.autoCrossedCells(undone),
            undone.autoCrossed,
            "auto crosses left are exactly the ones the remaining fills justify",
        )
    }

    @Test fun retryGenerationReloadsBoard() {
        val vm = loadedVm()
        val before = vm.uiState.value.game!!
        vm.retryGeneration()
        awaitState("reload after retry", vm) { !it.generating && it.game != null }
        assertFalse(vm.uiState.value.generationFailed)
        assertEquals(before.puzzle, vm.uiState.value.game!!.puzzle)
    }

    @Test fun reminderActionsUpdateState() {
        val vm = loadedVm()
        // Wait on the reminder StateFlows themselves: the collector
        // below subscribes (starting the WhileSubscribed upstream) and
        // completes on the terminal emission, while this thread pumps
        // the looper — subscribing from a parked runBlocking on main
        // was the wedge this replaces.
        vm.setReminderEnabled(true)
        awaitFlow("reminder enabled", vm.reminderEnabled) { it }
        vm.setReminderTime(7, 30)
        awaitFlow("reminder time", vm.reminderMinutesOfDay) { it == 450L }
        vm.setReminderEnabled(false)
        awaitFlow("reminder disabled", vm.reminderEnabled) { !it }
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
        // checkExistingAchievements launches on Dispatchers.IO; wait for
        // its terminal effect on the statuses flow (first_puzzle unlocked
        // from the stored completion count) instead of asserting — or
        // polling — the static achievement catalogue.
        awaitStore("backfill unlocks land") {
            vm.achievementsManager.getAchievementStatuses().first { statuses ->
                statuses.any { it.achievement.id == "first_puzzle" && it.isUnlocked }
            }
        }
        assertTrue(vm.achievementsManager.achievements.any { it.id == "first_puzzle" })
        assertTrue(vm.achievementsManager.achievements.any { it.id == "daily_streak_30" })
    }
}
