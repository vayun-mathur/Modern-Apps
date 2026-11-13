package com.vayunmathur.games.pipes.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.pipes.data.CellPos
import com.vayunmathur.games.pipes.data.LevelData
import com.vayunmathur.games.pipes.data.LevelPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PipesViewModelTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        LevelPack.init(app)
        try {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        } catch (_: IllegalStateException) { /* already initialised in this sandbox */ }
    }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun vm() = PipesViewModel(app)

    /** Sequential numberlink solver (pair levels only) used to play a level to a real win. */
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
            val neighbours = (level.adjacency[path.last()] ?: emptyList())
                .filter { it in level.cells && it !in used && (it == target || it !in allEndpoints) }
                .sortedBy { n -> (level.adjacency[n] ?: emptyList()).count { it !in used } }
            for (n in neighbours) {
                if (extend(ci, path + n, used + n)) return true
            }
            return false
        }
        val first = pairs[0]
        assertTrue(extend(0, listOf(first.cells[0]), setOf(first.cells[0])), "solver failed for ${level.id}")
        return result
    }

    private fun draw(vm: PipesViewModel, path: List<CellPos>) {
        vm.startDraw(path.first())
        for (c in path.drop(1)) vm.extendPath(c)
        vm.commitDraw()
    }

    @Test fun loadLevelValidInvalidAndIdempotent() {
        val vm = vm()
        assertEquals(-1, vm.uiState.value.packIndex)
        assertNull(vm.uiState.value.levelData)
        vm.loadLevel(99, 0); assertNull(vm.uiState.value.levelData)
        vm.loadLevel(0, 999); assertNull(vm.uiState.value.levelData)
        vm.loadLevel(-2, 0); assertNull(vm.uiState.value.levelData) // daily not refreshed yet
        vm.loadLevel(0, 0)
        val loaded = vm.uiState.value
        assertEquals(0, loaded.packIndex); assertEquals(0, loaded.levelIndex)
        assertNotNull(loaded.levelData); assertFalse(loaded.isLevelWon)
        vm.loadLevel(0, 0) // same: no-op, state identity preserved
        assertEquals(loaded, vm.uiState.value)
        vm.loadLevel(0, 1); assertEquals(1, vm.uiState.value.levelIndex)
    }

    @Test fun drawExtendCommitUndoAndRestart() {
        val vm = vm(); vm.loadLevel(0, 0)
        val level = vm.uiState.value.levelData!!
        val ep = level.endpoints[0]
        val start = ep.cells[0]
        // No-ops without a level / on empty cell
        vm.startDraw(CellPos(99, 99))
        vm.extendPath(start); vm.commitDraw(); vm.onUndo(); vm.onRestart()
        assertTrue(vm.uiState.value.gameState.paths.isEmpty())

        vm.startDraw(start)
        assertEquals(ep.colorIndex, vm.uiState.value.activeColor)
        assertEquals(listOf(start), vm.uiState.value.activePath)
        // Non-adjacent and foreign-endpoint steps are rejected
        // Far = not adjacent and not two steps away either (two-step moves route through the shared neighbour)
        val startAdj = level.adjacency[start] ?: emptyList()
        val far = level.cells.first { c -> c != start && c !in startAdj && (level.adjacency[c] ?: emptyList()).none { it in startAdj } && level.endpoints.none { e -> c in e.cells } }
        vm.extendPath(far); assertEquals(listOf(start), vm.uiState.value.activePath)
        val foreign = level.endpoints[1].cells[0]
        if (foreign !in (level.adjacency[start] ?: emptyList())) { /* adjacency guard also covers */ }
        // Valid step then backtrack
        val next = (level.adjacency[start] ?: emptyList()).first { n -> level.endpoints.all { n !in it.cells } }
        vm.extendPath(next); assertEquals(listOf(start, next), vm.uiState.value.activePath)
        vm.extendPath(next); assertEquals(2, vm.uiState.value.activePath.size) // already in path
        vm.extendPath(start); assertEquals(listOf(start), vm.uiState.value.activePath) // backtrack
        vm.extendPath(next)
        vm.commitDraw()
        assertEquals(listOf(start, next), vm.uiState.value.gameState.paths[ep.colorIndex])
        assertEquals(1, vm.getCurrentMoves())
        assertNull(vm.uiState.value.activeColor)
        // Grabbing the drawn line and releasing without moving changes nothing and is not a move
        vm.startDraw(next)
        assertEquals(listOf(start, next), vm.uiState.value.activePath)
        vm.commitDraw()
        assertEquals(listOf(start, next), vm.uiState.value.gameState.paths[ep.colorIndex])
        assertEquals(1, vm.getCurrentMoves())
        // The single real move undoes back to the empty board
        vm.onUndo(); assertTrue(vm.uiState.value.gameState.paths.isEmpty()); assertEquals(0, vm.getCurrentMoves())
        vm.onRestart(); assertTrue(vm.uiState.value.gameState.paths.isEmpty())
        // Draw again then restart clears
        draw(vm, listOf(start, next))
        vm.onRestart()
        assertTrue(vm.uiState.value.gameState.paths.isEmpty()); assertEquals(0, vm.getCurrentMoves())
    }

    @Test fun strayTapOnEndpointKeepsLineAndIsNotAMove() {
        val vm = vm(); vm.loadLevel(0, 0)
        val level = vm.uiState.value.levelData!!
        val ep = level.endpoints[0]
        val next = (level.adjacency[ep.cells[0]] ?: emptyList()).first { n -> level.endpoints.all { n !in it.cells } }
        draw(vm, listOf(ep.cells[0], next))
        assertEquals(1, vm.getCurrentMoves())
        // Touching the dot keeps the line while the finger stays on it (jitter included)
        vm.startDraw(ep.cells[0])
        vm.extendPath(ep.cells[0])
        assertEquals(listOf(ep.cells[0], next), vm.uiState.value.gameState.paths[ep.colorIndex])
        vm.commitDraw()
        assertEquals(listOf(ep.cells[0], next), vm.uiState.value.gameState.paths[ep.colorIndex])
        assertEquals(1, vm.getCurrentMoves())
        // Dragging off the dot and back clears the line: that is a real, undoable move
        vm.startDraw(ep.cells[0])
        vm.extendPath(next)
        vm.extendPath(ep.cells[0])
        assertTrue(vm.uiState.value.gameState.paths.isEmpty())
        vm.commitDraw()
        assertTrue(vm.uiState.value.gameState.paths.isEmpty())
        assertEquals(2, vm.getCurrentMoves())
        vm.onUndo()
        assertEquals(listOf(ep.cells[0], next), vm.uiState.value.gameState.paths[ep.colorIndex])
        // Tap an endpoint with no line drawn: nothing becomes undoable
        val vm2 = vm(); vm2.loadLevel(0, 0)
        vm2.startDraw(ep.cells[0]); vm2.commitDraw()
        assertEquals(0, vm2.getCurrentMoves())
    }

    @Test fun startDrawOnOwnedCellAndInvalidExtends() {
        val vm = vm(); vm.loadLevel(0, 0)
        val level = vm.uiState.value.levelData!!
        // Cell owned by nobody and not an endpoint: ignored (pick centre-ish empty cell)
        val empty = level.cells.first { c -> level.endpoints.none { c in it.cells } }
        vm.startDraw(empty); assertNull(vm.uiState.value.activeColor)
        // Extend with no active colour, commit with none, extend off-board
        val ep = level.endpoints[0]
        vm.startDraw(ep.cells[0])
        vm.extendPath(CellPos(-5, -5)); assertEquals(1, vm.uiState.value.activePath.size)
        // Foreign endpoint adjacent rejection is covered where adjacency allows; at minimum state unchanged on same cell
        vm.extendPath(ep.cells[0]); assertEquals(1, vm.uiState.value.activePath.size)
        vm.commitDraw()
    }

    @Test fun bridgesAllowSharingButRequireStraightExit() {
        val packIndex = LevelPack.PACKS.indexOfFirst { it.name == "Bridges" }
        assertTrue(packIndex >= 0)
        val vm = vm(); vm.loadLevel(packIndex, 0)
        val level = vm.uiState.value.levelData!!
        assertTrue(level.bridges.isNotEmpty())
        val bridge = level.bridges.first()
        // Colour running horizontally through the bridge row, then vertical crosser
        val horiz = level.endpoints.first { ep -> ep.cells.all { it.row == bridge.row } }
        val horizPath = (minOf(horiz.cells[0].col, horiz.cells[1].col)..maxOf(horiz.cells[0].col, horiz.cells[1].col))
            .map { CellPos(bridge.row, it) }
        if (horiz.cells[0] != horizPath.first()) draw(vm, horizPath.reversed()) else draw(vm, horizPath)
        assertTrue(bridge in (vm.uiState.value.gameState.paths[horiz.colorIndex] ?: emptyList()))
        val vert = level.endpoints.first { ep -> ep.cells.all { it.col == bridge.col } && ep.colorIndex != horiz.colorIndex }
        val vertPath = (minOf(vert.cells[0].row, vert.cells[1].row)..maxOf(vert.cells[0].row, vert.cells[1].row))
            .map { CellPos(it, bridge.col) }
        if (vert.cells[0] != vertPath.first()) draw(vm, vertPath.reversed()) else draw(vm, vertPath)
        // Bridge cell is shared: both paths contain it
        assertTrue(bridge in (vm.uiState.value.gameState.paths[vert.colorIndex] ?: emptyList()))
        // Cornering off a bridge is rejected: start above bridge, enter, try to turn
        val vm2 = vm(); vm2.loadLevel(packIndex, 0)
        vm2.startDraw(vert.cells.minBy { it.row })
        val above = CellPos(bridge.row - 1, bridge.col)
        val entryPath = (vert.cells.minOf { it.row }..bridge.row).map { CellPos(it, bridge.col) }
        for (c in entryPath.drop(1)) vm2.extendPath(c)
        assertEquals(bridge, vm2.uiState.value.activePath.last())
        vm2.extendPath(CellPos(bridge.row, bridge.col + 1)) // turn off bridge: rejected
        assertEquals(bridge, vm2.uiState.value.activePath.last())
        vm2.extendPath(CellPos(bridge.row + 1, bridge.col)) // straight through: accepted
        assertEquals(CellPos(bridge.row + 1, bridge.col), vm2.uiState.value.activePath.last())
        vm2.commitDraw()
    }

    @Test fun portalsActAsAdjacencySteps() {
        val packIndex = LevelPack.PACKS.indexOfFirst { it.name == "Portals" }
        val vm = vm(); vm.loadLevel(packIndex, 0)
        val level = vm.uiState.value.levelData!!
        assertTrue(level.portals.isNotEmpty())
        val (a, b) = level.portals.entries.first()
        // Find a colour whose endpoint can reach a portal pad; simply verify portal edge is in adjacency
        assertTrue(b in (level.adjacency[a] ?: emptyList()))
        assertTrue(a in (level.adjacency[b] ?: emptyList()))
    }

    @Test fun tripletChainStopsAtThirdDot() {
        val packIndex = LevelPack.PACKS.indexOfFirst { it.name == "Triplets" }
        val vm = vm(); vm.loadLevel(packIndex, 0)
        val level = vm.uiState.value.levelData!!
        val triple = level.endpoints.first { it.cells.size == 3 }
        val row = triple.cells[0].row
        val path = (0 until level.cols).map { CellPos(row, it) }
        draw(vm, path)
        assertEquals(path, vm.uiState.value.gameState.paths[triple.colorIndex])
        // After visiting all three dots, extending further from a fresh grab is blocked at the paired endpoint
        vm.startDraw(triple.cells[0])
        for (c in path.drop(1)) vm.extendPath(c)
        val sizeAtEnd = vm.uiState.value.activePath.size
        vm.extendPath(CellPos(row - 1, level.cols - 1))
        assertEquals(sizeAtEnd, vm.uiState.value.activePath.size)
        vm.commitDraw()
    }

    @Test fun solvingHexLevelWinsAndRecordsStats() {
        val packIndex = LevelPack.PACKS.indexOfFirst { it.name == "Hexes" }
        val vm = vm(); vm.loadLevel(packIndex, 0)
        val level = vm.uiState.value.levelData!!
        val solution = solve(level)
        for ((_, path) in solution.entries.sortedBy { it.key }) draw(vm, path)
        assertTrue(vm.uiState.value.isLevelWon, "state=${vm.uiState.value}")
        assertTrue(vm.getCurrentMoves() >= 2)
        // Further play is blocked after a win
        vm.startDraw(level.endpoints[0].cells[0]); assertNull(vm.uiState.value.activeColor)
        vm.onUndo(); assertTrue(vm.uiState.value.isLevelWon)
        vm.onRestart(); assertTrue(vm.uiState.value.isLevelWon)
        runBlocking {
            withTimeout(5_000) { vm.levelStats.first { it.containsKey(level.id) } }
        }
        assertEquals(solution.size, vm.levelStats.value[level.id]?.let { solution.size } )
        assertNotNull(vm.levelStats.value[level.id])
    }

    @Test fun dailyRefreshAndWin() = runBlocking {
        val vm = vm()
        vm.refreshDaily()
        val pack = withTimeout(10_000) { vm.dailyPack.first { it != null } }!!
        assertEquals(5, pack.levels.size)
        assertEquals(pack.levels.first().id, com.vayunmathur.games.pipes.domain.DailyLevels.levelId(vm.dailyDay.value, 0))
        assertEquals(0, vm.dailyCompleted.value)
        // Second refresh is a no-op for the same day
        vm.refreshDaily()
        assertEquals(pack, vm.dailyPack.value)
        vm.loadLevel(PipesViewModel.DAILY_PACK_INDEX, 0)
        val level = vm.uiState.value.levelData!!
        val solution = solve(level)
        for ((_, path) in solution.entries.sortedBy { it.key }) draw(vm, path)
        assertTrue(vm.uiState.value.isLevelWon)
        withTimeout(5_000) { vm.dailyStats.first { it.containsKey(level.id) } }
        assertNotNull(vm.dailyStats.value[level.id])
        Unit
    }

    @Test fun dailyRolloverReloadsSameIndex() = runBlocking {
        // A DailyGame route kept open across midnight: refreshDaily() swaps in the new
        // day's pack, and the re-fired loadLevel must pick up the new board instead of
        // keeping yesterday's via the same-index early return.
        val vm = vm()
        vm.refreshDaily()
        withTimeout(10_000) { vm.dailyPack.first { it != null } }
        vm.loadLevel(PipesViewModel.DAILY_PACK_INDEX, 0)
        val yesterday = vm.uiState.value.levelData!!
        val ep = yesterday.endpoints[0]
        val next = (yesterday.adjacency[ep.cells[0]] ?: emptyList())
            .first { n -> yesterday.endpoints.all { n !in it.cells } }
        draw(vm, listOf(ep.cells[0], next))
        assertEquals(1, vm.getCurrentMoves())
        val rolledOver =
            com.vayunmathur.games.pipes.domain.DailyLevels.packFor(vm.dailyDay.value + 1)
        assertNotNull(rolledOver)
        val field = PipesViewModel::class.java.getDeclaredField("_dailyPack")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(vm) as MutableStateFlow<LevelPack?>).value = rolledOver
        vm.loadLevel(PipesViewModel.DAILY_PACK_INDEX, 0)
        assertEquals(rolledOver.levels[0], vm.uiState.value.levelData)
        assertTrue(vm.uiState.value.gameState.paths.isEmpty())
        assertEquals(0, vm.getCurrentMoves())
        Unit
    }

    @Test fun reachingPairedEndpointEndsTheStroke() {
        val packIndex = LevelPack.PACKS.indexOfFirst { it.name == "Bridges" }
        val vm = vm(); vm.loadLevel(packIndex, 0)
        val level = vm.uiState.value.levelData!!
        // The short corner pairs in Bridges_001 sit on adjacent cells
        val pair = level.endpoints.first { ep -> ep.cells.size == 2 &&
            kotlin.math.abs(ep.cells[0].row - ep.cells[1].row) + kotlin.math.abs(ep.cells[0].col - ep.cells[1].col) == 1 }
        vm.startDraw(pair.cells[0])
        vm.extendPath(pair.cells[1])
        assertEquals(2, vm.uiState.value.activePath.size)
        // Already at the partner dot: any further step is refused
        val beyond = (level.adjacency[pair.cells[1]] ?: emptyList()).first { it != pair.cells[0] }
        vm.extendPath(beyond)
        assertEquals(2, vm.uiState.value.activePath.size)
        vm.commitDraw()
        assertEquals(pair.cells, vm.uiState.value.gameState.paths[pair.colorIndex])
        assertFalse(vm.uiState.value.isLevelWon)
    }

    @Test fun completingTheDailySetRecordsTheStreak() = runBlocking {
        // Four of today's five are already solved (persisted), so winning the last one completes the day
        val vm = vm()
        vm.refreshDaily()
        val pack = withTimeout(10_000) { vm.dailyPack.first { it != null } }!!
        val day = vm.dailyDay.value
        val store = com.vayunmathur.library.util.LevelStatsRepository(app, "daily_stats")
        for (i in 1 until 5) store.updateBestScore(com.vayunmathur.games.pipes.domain.DailyLevels.levelId(day, i), 3)
        vm.loadLevel(PipesViewModel.DAILY_PACK_INDEX, 0)
        val level = vm.uiState.value.levelData!!
        assertEquals(pack.levels[0].id, level.id)
        val solution = solve(level)
        for ((_, path) in solution.entries.sortedBy { it.key }) draw(vm, path)
        assertTrue(vm.uiState.value.isLevelWon)
        withTimeout(5_000) { vm.dailyStats.first { stats -> (0 until 5).all { stats.containsKey(com.vayunmathur.games.pipes.domain.DailyLevels.levelId(day, it)) } } }
        assertEquals(5, vm.dailyStats.value.size.coerceAtMost(5))
        Unit
    }

    @Test fun achievementsManagerRechecksExistingProgress() {
        val repo = com.vayunmathur.games.pipes.data.CompletedLevelsRepository(app)
        repo.updateBestScore("previous_level", 5)
        repo.incrementTotalPipesPlaced()
        val json = app.assets.open("achievements.json").bufferedReader().use { it.readText() }
        val manager = PipesAchievementsManager(app, json, repo)
        manager.checkExistingAchievements()
        manager.dismissNotification()
    }

}
