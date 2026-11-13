package com.vayunmathur.games.pipes.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.pipes.data.CellPos
import com.vayunmathur.games.pipes.data.LevelData
import com.vayunmathur.games.pipes.data.LevelPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ViewModel-level stroke behaviour added by the UX-audit fixes. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PipesStrokeTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        LevelPack.init(app)
        try {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        } catch (_: IllegalStateException) { /* already initialised in this sandbox */ }
        app.getSharedPreferences("pipes_progress", android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }

    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun vm(pack: Int = 0, level: Int = 0) = PipesViewModel(app).also { it.loadLevel(pack, level) }
    private val PipesViewModel.level: LevelData get() = uiState.value.levelData!!

    private fun LevelData.isEndpoint(c: CellPos) = endpoints.any { c in it.cells }
    private fun LevelData.freeNeighbour(of: CellPos, not: Set<CellPos> = emptySet()) =
        (adjacency[of] ?: emptyList()).first { !isEndpoint(it) && it !in not }

    private fun draw(vm: PipesViewModel, path: List<CellPos>) {
        vm.startDraw(path.first())
        for (c in path.drop(1)) vm.extendPath(c)
        vm.commitDraw()
    }

    @Test fun aSecondStartDuringAStrokeKeepsTheRealUndoSnapshot() {
        val vm = vm()
        val a = vm.level.endpoints[0]
        val b = vm.level.endpoints[1]
        val aNext = vm.level.freeNeighbour(a.cells[0])
        vm.startDraw(a.cells[0])
        vm.extendPath(aNext)
        // A second start arrives before the first stroke was released.
        vm.startDraw(b.cells[0])
        assertEquals(b.colorIndex, vm.uiState.value.activeColor)
        val bNext = vm.level.freeNeighbour(b.cells[0], setOf(aNext))
        vm.extendPath(bNext)
        vm.commitDraw()
        // The abandoned stroke left nothing behind, and one undo restores the empty board.
        assertNull(vm.uiState.value.gameState.paths[a.colorIndex])
        assertEquals(listOf(b.cells[0], bNext), vm.uiState.value.gameState.paths[b.colorIndex])
        assertEquals(1, vm.getCurrentMoves())
        vm.onUndo()
        assertTrue(vm.uiState.value.gameState.paths.isEmpty())
    }

    @Test fun skippedAndBacktrackedCellsAreHandledInOneEvent() {
        val vm = vm()
        val level = vm.level
        val ep = level.endpoints[0]
        val a = level.freeNeighbour(ep.cells[0])
        val b = level.freeNeighbour(a, setOf(ep.cells[0]))
        vm.startDraw(ep.cells[0])
        // The finger landed two cells away in one event: the cell in between is filled in.
        vm.extendPath(b)
        val stroke = vm.uiState.value.activePath
        assertEquals(3, stroke.size)
        assertEquals(b, stroke.last())
        assertTrue(stroke[1] in level.adjacency.getValue(ep.cells[0]) && b in level.adjacency.getValue(stroke[1]))
        // A fast swipe straight back to the dot cuts the whole stroke back at once.
        vm.extendPath(ep.cells[0])
        assertEquals(listOf(ep.cells[0]), vm.uiState.value.activePath)
        vm.commitDraw()
    }
}
