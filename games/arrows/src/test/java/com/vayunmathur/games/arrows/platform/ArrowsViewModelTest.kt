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
import kotlin.test.assertNull
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

    @Test
    fun casualFlowTapCommitRestartNextLevelAndSettings() {
        val vm = ArrowsViewModel(app)
        await("board loads") { vm.uiState.value.game != null && !vm.uiState.value.generating }
        val initial = vm.uiState.value
        val game = assertNotNull(initial.game)
        assertTrue(game.puzzle.pieces.isNotEmpty())
        assertEquals(initial.mode, game.mode)
        assertEquals(initial.level, game.level)
        assertNotNull(vm.achievementsManager)
        assertEquals("arrows_daily", ArrowsViewModel.DAILY_KEY_PREFIX)
        vm.dismissAchievementNotification()

        // Unknown tap and commit-without-move are no-ops.
        vm.tapArrow(999_999)
        assertNull(vm.uiState.value.move)
        vm.commitMove()
        assertNull(vm.uiState.value.move)

        // Tap a clearable arrow: move is set, taps lock out, commit removes it.
        val clearable = ArrowsRules.clearableNow(vm.uiState.value.game!!)
        assertTrue(clearable.isNotEmpty(), "fresh board should have a clearable arrow")
        val piece = clearable.first()
        vm.tapArrow(piece.id)
        assertNotNull(vm.uiState.value.move)
        assertEquals(piece.id, vm.uiState.value.move!!.pieceId)
        assertTrue(vm.uiState.value.move!!.clears)
        val other = vm.uiState.value.game!!.remaining.firstOrNull { it.id != piece.id }
        if (other != null) {
            vm.tapArrow(other.id)
            assertEquals(piece.id, vm.uiState.value.move!!.pieceId, "taps locked while moving")
        }
        vm.commitMove()
        assertNull(vm.uiState.value.move)
        assertTrue(piece.id in vm.uiState.value.game!!.removed)

        // Restart restores a full board with full hearts.
        vm.restartLevel()
        await("restart resets") {
            vm.uiState.value.game?.removed?.isEmpty() == true && vm.uiState.value.game?.hearts == 3
        }

        // Blocked tap costs a heart when a blocked arrow exists on this board.
        val fresh = vm.uiState.value.game!!
        val blocked = fresh.remaining.firstOrNull { ArrowsRules.isBlocked(fresh, it) }
        if (blocked != null) {
            vm.tapArrow(blocked.id)
            assertEquals(false, vm.uiState.value.move!!.clears)
            vm.commitMove()
            assertEquals(2, vm.uiState.value.game!!.hearts)
            assertEquals(blocked.id, vm.uiState.value.game!!.blockedId)
            assertTrue(blocked.id !in vm.uiState.value.game!!.removed)
            vm.restartLevel()
            await("restart after blocked") { vm.uiState.value.game?.hearts == 3 }
        }

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

        // Next level advances in casual mode; restore level 1 for any later class sharing the store.
        val levelBefore = vm.uiState.value.level
        vm.nextLevel()
        await("level advances") {
            vm.uiState.value.level == levelBefore + 1 && vm.uiState.value.game != null && !vm.uiState.value.generating
        }
        runBlocking { vm.dataStore.saveLevel(1) }
        await("back to level 1") { vm.uiState.value.level == 1 && vm.uiState.value.game != null }
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
