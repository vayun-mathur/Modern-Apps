package com.vayunmathur.games.arrows.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.games.arrows.data.GameMode
import com.vayunmathur.games.arrows.domain.ArrowsRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A single-VM flow test: several live ViewModels would all share the one DataStore and react to
 * each other's writes, so the scenarios run in sequence on one instance instead of one VM per test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ArrowsViewModelTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun await(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(25)
        }
        assertTrue(cond(), "timed out: $desc")
    }

    private fun loadedViewModel(): ArrowsViewModel {
        val vm = ArrowsViewModel(app)
        await("board loads") { vm.uiState.value.game != null && !vm.uiState.value.generating }
        return vm
    }

    @Test
    fun casualTapsApplyAtOnceAndRunConcurrently() {
        val vm = loadedViewModel()
        val initial = vm.uiState.value
        val game = assertNotNull(initial.game)
        assertTrue(game.puzzle.pieces.isNotEmpty())
        assertEquals(initial.mode, game.mode)
        assertEquals(initial.level, game.level)
        assertNotNull(vm.achievementsManager)
        assertEquals("arrows_daily", ArrowsViewModel.DAILY_KEY_PREFIX)
        vm.dismissAchievementNotification()

        // Unknown tap and finishing an unknown move are no-ops.
        vm.tapArrow(999_999)
        vm.finishMove(42L)
        assertTrue(vm.uiState.value.moves.isEmpty())

        // A clearable arrow leaves the board the moment it is tapped and animates as a move.
        val piece = ArrowsRules.clearableNow(game).first()
        vm.tapArrow(piece.id)
        val afterFirst = vm.uiState.value
        assertTrue(piece.id in afterFirst.game!!.removed, "tap applies at once")
        val firstMove = afterFirst.moves.single()
        assertEquals(piece.id, firstMove.pieceId)
        assertTrue(firstMove.clears)

        // A second arrow can set off while the first is still in the air, judged against the
        // board without the first; tapping the one in flight again does nothing.
        val second = ArrowsRules.clearableNow(afterFirst.game!!).firstOrNull()
        if (second != null) {
            vm.tapArrow(second.id)
            assertEquals(listOf(piece.id, second.id), vm.uiState.value.moves.map { it.pieceId })
            assertTrue(second.id in vm.uiState.value.game!!.removed)
        }
        val movesBefore = vm.uiState.value.moves
        vm.tapArrow(piece.id)
        assertEquals(movesBefore, vm.uiState.value.moves)

        // Finishing an animation only retires the move; the board keeps the cleared arrows,
        // including once the store has caught up with both writes.
        vm.finishMove(firstMove.id)
        assertTrue(vm.uiState.value.moves.none { it.id == firstMove.id })
        val expected = vm.uiState.value.game!!.removed
        await("store catches up") { runBlocking { vm.dataStore.removedArrows.first() } == expected }
        Thread.sleep(200)
        assertEquals(expected, vm.uiState.value.game!!.removed)

        vm.restartLevel()
        await("restart persisted") { runBlocking { vm.dataStore.removedArrows.first() }.isEmpty() }
    }

    @Test
    fun blockedTapCostsOneHeartAtOnceAndRestartIsImmediate() {
        val vm = loadedViewModel()
        // Some progress first, so the restart below has something to undo.
        ArrowsRules.clearableNow(vm.uiState.value.game!!).firstOrNull()?.let { vm.tapArrow(it.id) }
        vm.restartLevel()
        assertTrue(vm.uiState.value.game!!.removed.isEmpty(), "restart applies at once")
        assertTrue(vm.uiState.value.moves.isEmpty())
        val fresh = vm.uiState.value.game!!
        val blocked = fresh.remaining.firstOrNull { ArrowsRules.isBlocked(fresh, it) }
        if (blocked != null) {
            vm.tapArrow(blocked.id)
            assertEquals(false, vm.uiState.value.moves.single().clears)
            assertEquals(2, vm.uiState.value.game!!.hearts)
            assertEquals(blocked.id, vm.uiState.value.game!!.blockedId)
            assertTrue(blocked.id !in vm.uiState.value.game!!.removed)
            vm.tapArrow(blocked.id)
            assertEquals(2, vm.uiState.value.game!!.hearts, "double tap costs one heart")
        }
        vm.restartLevel()
        await("restart persisted") { runBlocking { vm.dataStore.hearts.first() } == 3 }
    }

    @Test
    fun firstBlockedTapEverExplainsTheLostHeartOnce() {
        val vm = loadedViewModel()
        val game = vm.uiState.value.game!!
        val blocked = game.remaining.firstOrNull { ArrowsRules.isBlocked(game, it) } ?: return
        // The store may already be past its first blocked tap if another test got there first.
        val firstEver = !runBlocking { vm.dataStore.heartHintSeen.first() }
        vm.tapArrow(blocked.id)
        assertEquals(firstEver, vm.uiState.value.showHeartHint)
        await("hint marked seen") { runBlocking { vm.dataStore.heartHintSeen.first() } }

        // Restart hides it, and a later blocked tap never explains it again.
        vm.restartLevel()
        assertEquals(false, vm.uiState.value.showHeartHint)
        vm.tapArrow(blocked.id)
        assertEquals(false, vm.uiState.value.showHeartHint)
        vm.restartLevel()
        await("restart persisted") { runBlocking { vm.dataStore.hearts.first() } == 3 }
    }

    @Test
    fun dismissingTheRedirectorHintSticks() {
        val vm = loadedViewModel()
        vm.dismissRedirectorHint()
        assertTrue(vm.uiState.value.redirectorHintSeen)
        await("persisted") { runBlocking { vm.dataStore.redirectorHintSeen.first() } }
    }

    @Test
    fun casualRestartWinNextLevelAndSettings() {
        val vm = loadedViewModel()

        // Show-routes setting round-trips through the store into uiState.
        vm.setShowRoutes(false)
        await("showRoutes false") { !vm.uiState.value.showRoutes }
        vm.setShowRoutes(true)
        await("showRoutes true") { vm.uiState.value.showRoutes }

        // Winning the board (via persisted progress for every arrow) records the clear.
        val clearedBefore = runBlocking { vm.dataStore.boardsCleared.first() }
        val allIds = vm.uiState.value.game!!.puzzle.pieces.map { it.id }.toSet()
        runBlocking { vm.dataStore.saveProgress(allIds, 3, daily = false) }
        await("board won") { vm.uiState.value.game?.isWon == true }
        await("clear recorded") {
            runBlocking { vm.dataStore.boardsCleared.first() } > clearedBefore
        }

        // Next level advances in casual mode, exactly once however fast it is tapped twice;
        // restore level 1 for any later class sharing the store.
        val levelBefore = vm.uiState.value.level
        vm.nextLevel()
        vm.nextLevel()
        await("level advances") {
            vm.uiState.value.level == levelBefore + 1 && vm.uiState.value.game != null && !vm.uiState.value.generating
        }
        vm.nextLevel() // a fresh, unsolved board: ignored
        Thread.sleep(500)
        assertEquals(levelBefore + 1, vm.uiState.value.level, "double tap must not skip a level")
        assertEquals(levelBefore + 1, runBlocking { vm.dataStore.currentLevel.first() })
        runBlocking { vm.dataStore.saveLevel(1) }
        await("back to level 1") { vm.uiState.value.level == 1 && vm.uiState.value.game != null }
    }

    @Test
    fun relaunchOnWonBoardDoesNotRecountTheClear() {
        // A won board is persisted, but the comment on onCleared's guard admits the guard is only
        // in-memory: after process death the progress collector replays onto an already-won board
        // and counts it again. Each relaunch below must leave the lifetime counter alone.
        val vm = ArrowsViewModel(app)
        await("board loads") { vm.uiState.value.game != null && !vm.uiState.value.generating }
        val allIds = vm.uiState.value.game!!.puzzle.pieces.map { it.id }.toSet()
        runBlocking { vm.dataStore.saveProgress(allIds, 3, daily = false) }
        await("board won") { vm.uiState.value.game?.isWon == true }
        // A second ViewModel over the same store is a fresh process for guard purposes: its
        // in-memory clear guard starts empty while the won board is already persisted.
        val first = ArrowsViewModel(app)
        await("relaunch loads won board") { first.uiState.value.game?.isWon == true }
        Thread.sleep(2_000)
        val afterFirst = runBlocking { first.dataStore.boardsCleared.first() }

        val second = ArrowsViewModel(app)
        await("second relaunch loads won board") { second.uiState.value.game?.isWon == true }
        Thread.sleep(2_000)
        val afterSecond = runBlocking { second.dataStore.boardsCleared.first() }

        assertEquals(
            afterFirst,
            afterSecond,
            "reloading an already-won board must not count it again",
        )
        runBlocking { second.dataStore.resetBoard(daily = false) }
    }

    @Test
    fun dailyFlowAndReminderSettings() {
        runCatching {
            WorkManager.initialize(app, Configuration.Builder().build())
        }
        val vm = ArrowsViewModel(app)
        await("casual loads first") { vm.uiState.value.game != null && !vm.uiState.value.generating }

        // Keep the WhileSubscribed reminder flows subscribed while we change them.
        val collectors = CoroutineScope(Dispatchers.Unconfined + Job())
        val jobs = listOf(
            collectors.launch { vm.reminderEnabled.collect {} },
            collectors.launch { vm.reminderMinutesOfDay.collect {} },
        )
        try {
            vm.setGameMode(GameMode.DAILY)
            await("daily loads") {
                vm.uiState.value.mode == GameMode.DAILY && vm.uiState.value.game != null && !vm.uiState.value.generating
            }
            val dailyGame = vm.uiState.value.game!!
            assertEquals(GameMode.DAILY, dailyGame.mode)
            assertEquals(7, dailyGame.puzzle.cols)
            assertEquals(9, dailyGame.puzzle.rows)

            // Winning the daily board records the daily clear as well.
            val dailyIds = dailyGame.puzzle.pieces.map { it.id }.toSet()
            runBlocking { vm.dataStore.saveProgress(dailyIds, 3, daily = true) }
            await("daily won") { vm.uiState.value.game?.isWon == true }
            await("daily marked done") { vm.uiState.value.dailyDone }

            val levelBefore = vm.uiState.value.level
            vm.nextLevel()
            Thread.sleep(200)
            assertEquals(GameMode.DAILY, vm.uiState.value.mode)
            assertEquals(levelBefore, vm.uiState.value.level, "nextLevel must be a no-op in daily")

            vm.setReminderTime(8, 30)
            await("reminder time") { vm.reminderMinutesOfDay.value == 8L * 60 + 30 }
            vm.setReminderEnabled(true)
            await("reminder enabled") { vm.reminderEnabled.value }
            vm.setReminderEnabled(false)
            await("reminder disabled") { !vm.reminderEnabled.value }
            vm.setReminderTime(20, 0)
            await("reminder reset") { vm.reminderMinutesOfDay.value == 20L * 60 }
        } finally {
            jobs.forEach { it.cancel() }
            vm.setGameMode(GameMode.CASUAL)
            await("back to casual") {
                vm.uiState.value.mode == GameMode.CASUAL && vm.uiState.value.game != null
            }
        }
    }
}
