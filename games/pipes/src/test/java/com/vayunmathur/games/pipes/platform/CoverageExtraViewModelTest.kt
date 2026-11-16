package com.vayunmathur.games.pipes.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.pipes.data.CellPos
import com.vayunmathur.games.pipes.data.CompletedLevelsRepository
import com.vayunmathur.games.pipes.data.LevelData
import com.vayunmathur.games.pipes.data.LevelPack
import com.vayunmathur.library.util.DailyChallengeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CoverageExtraViewModelTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        LevelPack.init(app)
        try {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        } catch (_: IllegalStateException) { /* already initialised */ }
    }

    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun solve(level: LevelData): Map<Int, List<CellPos>> {
        val result = mutableMapOf<Int, List<CellPos>>()
        val pairs = level.endpoints.filter { it.cells.size == 2 }
        val allEndpoints = level.endpoints.flatMap { it.cells }.toSet()
        fun extend(ci: Int, path: List<CellPos>, used: Set<CellPos>): Boolean {
            val target = pairs[ci].cells[1]
            if (path.last() == target) {
                if (ci == pairs.lastIndex) {
                    if (used.size != level.cells.size) return false
                    result[pairs[ci].colorIndex] = path; return true
                }
                result[pairs[ci].colorIndex] = path
                val next = pairs[ci + 1]
                if (extend(ci + 1, listOf(next.cells[0]), used + next.cells[0])) return true
                result.remove(pairs[ci].colorIndex); return false
            }
            for (n in (level.adjacency[path.last()] ?: emptyList())
                .filter { it in level.cells && it !in used && (it == target || it !in allEndpoints) }
                .sortedBy { m -> (level.adjacency[m] ?: emptyList()).count { it !in used } }) {
                if (extend(ci, path + n, used + n)) return true
            }
            return false
        }
        val first = pairs[0]
        assertTrue(extend(0, listOf(first.cells[0]), setOf(first.cells[0])), "unsolved ${level.id}")
        return result
    }

    private fun draw(vm: PipesViewModel, path: List<CellPos>) {
        vm.startDraw(path.first())
        for (c in path.drop(1)) vm.extendPath(c)
        vm.commitDraw()
    }

    @Test fun noLevelAndWonLevelAreNoOps() {
        val vm = PipesViewModel(app)
        // Nothing loaded: every board callback must quietly do nothing.
        vm.startDraw(CellPos(0, 0)); vm.extendPath(CellPos(0, 1)); vm.commitDraw()
        vm.onUndo(); vm.onRestart()
        assertEquals(-1, vm.uiState.value.packIndex)
        assertTrue(vm.uiState.value.gameState.paths.isEmpty())
        assertEquals(0, vm.getCurrentMoves())

        vm.loadLevel(0, 0)
        val level = vm.uiState.value.levelData!!
        for ((_, path) in solve(level).entries.sortedBy { it.key }) draw(vm, path)
        assertTrue(vm.uiState.value.isLevelWon)
        val wonState = vm.uiState.value.gameState
        val wonHistory = vm.uiState.value.history
        // A won board refuses further strokes, undo and restart.
        vm.startDraw(level.endpoints[0].cells[0]); vm.extendPath(level.endpoints[0].cells[1]); vm.commitDraw()
        vm.onUndo(); vm.onRestart()
        // Let the win's stats coroutine finish before tearDown resets Main.
        val deadline = System.currentTimeMillis() + 10_000
        while (!vm.levelStats.value.containsKey(level.id) && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertTrue(vm.levelStats.value.containsKey(level.id))
        assertTrue(vm.uiState.value.isLevelWon)
        assertEquals(wonState, vm.uiState.value.gameState)
        assertEquals(wonHistory, vm.uiState.value.history)
    }

    @Test fun completingWholeFirstPackUnlocksPackAchievement() = runBlocking {
        val vm = PipesViewModel(app)
        val pack = LevelPack.PACKS[0]
        // Every other level in the pack is already recorded; solving the first completes it.
        for (level in pack.levels.drop(1)) vm.repository.updateBestScore(level.id, level.optimalMoves)
        vm.loadLevel(0, 0)
        val level = vm.uiState.value.levelData!!
        for ((_, path) in solve(level).entries.sortedBy { it.key }) draw(vm, path)
        assertTrue(vm.uiState.value.isLevelWon)
        withTimeout(10_000) { vm.levelStats.first { stats -> pack.levels.all { stats.containsKey(it.id) } } }
        assertEquals(pack.levels.size, pack.levels.count { vm.levelStats.value.containsKey(it.id) })
        assertFalse(vm.levelStats.value.getValue(level.id).bestScore < 0)
        Unit
    }

    @Test fun singleDailyWinRecordsScoreWithoutCompletingDay() = runBlocking {
        val vm = PipesViewModel(app)
        vm.refreshDaily()
        val pack = withTimeout(10_000) { vm.dailyPack.first { it != null } }!!
        vm.loadLevel(PipesViewModel.DAILY_PACK_INDEX, 1)
        val level = vm.uiState.value.levelData!!
        assertEquals(pack.levels[1].id, level.id)
        val completedCount = async {
            withTimeout(10_000) { vm.dailyCompleted.first { it == 1 } }
        }
        for ((_, path) in solve(level).entries.sortedBy { it.key }) draw(vm, path)
        assertTrue(vm.uiState.value.isLevelWon)
        withTimeout(10_000) { vm.dailyStats.first { it.containsKey(level.id) } }
        // Only this one of the five is done, so no streak is recorded for the day.
        assertEquals(1, completedCount.await())
        assertTrue(vm.dailyStats.value.containsKey(level.id))
        Unit
    }

    @Test fun achievementsRecheckSeesDailyStreak() = runBlocking {
        val store = DailyChallengeStore(app, "pipes_daily")
        store.recordDayCompleted(store.todayEpochDay())
        val repo = CompletedLevelsRepository(app)
        repo.updateBestScore("seen_level", 3)
        val json = app.assets.open("achievements.json").bufferedReader().use { it.readText() }
        val manager = PipesAchievementsManager(app, json, repo)
        manager.checkExistingAchievements()
        // The streak check runs on IO; give it time to deliver its progress update.
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (manager.newAchievement.value != null) break
            Thread.sleep(25)
        }
        manager.dismissNotification()
        assertTrue(store.bestStreak() > 0)
        Unit
    }
}
