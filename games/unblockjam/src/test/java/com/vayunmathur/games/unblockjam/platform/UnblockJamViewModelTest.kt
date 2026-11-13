package com.vayunmathur.games.unblockjam.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.unblockjam.data.Block
import com.vayunmathur.games.unblockjam.data.Coord
import com.vayunmathur.games.unblockjam.data.Dimension
import com.vayunmathur.games.unblockjam.data.LevelPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
class UnblockJamViewModelTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        if (LevelPack.PACKS.isEmpty()) LevelPack.init(app)
    }

    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun vm() = UnblockJamViewModel(app)

    @Test
    fun packsLoadAndInitialStateIsEmpty() {
        assertEquals(7, LevelPack.PACKS.size)
        assertTrue(LevelPack.PACKS.all { it.levels.isNotEmpty() })
        val vm = vm()
        val s = vm.uiState.value
        assertEquals(-1, s.packIndex)
        assertEquals(-1, s.levelIndex)
        assertNull(s.currentLevelData)
        assertTrue(s.history.isEmpty())
        assertFalse(s.isLevelWon)
        assertEquals(0, vm.getCurrentMoves())
        assertEquals(UnblockJamUiState(), s)
        assertEquals("unblockjam_daily", UnblockJamViewModel.DAILY_KEY_PREFIX)
        assertEquals(-2, UnblockJamViewModel.DAILY_PACK_INDEX)
    }

    @Test
    fun loadLevelInvalidIndicesAreNoOps() {
        val vm = vm()
        vm.loadLevel(99, 0)
        assertNull(vm.uiState.value.currentLevelData)
        vm.loadLevel(0, 9999)
        assertNull(vm.uiState.value.currentLevelData)
        vm.loadLevel(-5, 0)
        assertNull(vm.uiState.value.currentLevelData)
        vm.loadLevel(UnblockJamViewModel.DAILY_PACK_INDEX, 0) // daily pack not generated yet
        assertNull(vm.uiState.value.currentLevelData)
    }

    @Test
    fun loadLevelLoadsAndReloadingSameIsNoOpAfterMove() {
        val vm = vm()
        vm.loadLevel(0, 0)
        val loaded = assertNotNull(vm.uiState.value.currentLevelData)
        assertEquals(LevelPack.PACKS[0].levels[0], loaded)
        assertEquals(0, vm.uiState.value.packIndex)
        assertEquals(0, vm.uiState.value.levelIndex)

        // move a block (different index) so history grows, then reloading same must keep state
        val moved = movedCopy(loaded, blockIndex = lastBlock(loaded), dx = 0, dy = 0)
        vm.onBlockMoved(moved)
        val afterMove = vm.uiState.value
        vm.loadLevel(0, 0)
        assertEquals(afterMove, vm.uiState.value)

        // loading a different level resets
        vm.loadLevel(0, 1)
        assertEquals(1, vm.uiState.value.levelIndex)
        assertEquals(LevelPack.PACKS[0].levels[1], vm.uiState.value.currentLevelData)
        assertTrue(vm.uiState.value.history.isEmpty())
        assertFalse(vm.uiState.value.isLevelWon)
    }

    /** A real one-cell move on [level], along some block's own axis, as a drag would make. */
    private fun legalMove(level: com.vayunmathur.games.unblockjam.data.LevelData) =
        level.blocks.indices.firstNotNullOf { i ->
            listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1).firstNotNullOfOrNull { (dx, dy) ->
                (stepBlock(level, i, dx, dy) as? StepResult.Moved)?.level
            }
        }

    private fun lastBlock(level: com.vayunmathur.games.unblockjam.data.LevelData): Int =
        level.blocks.indices.last { !level.blocks[it].fixed }

    /** Returns a copy with block [blockIndex] shifted by one cell if possible, marked as moved. */
    private fun movedCopy(
        level: com.vayunmathur.games.unblockjam.data.LevelData,
        blockIndex: Int,
        dx: Int,
        dy: Int,
    ): com.vayunmathur.games.unblockjam.data.LevelData {
        val block = level.blocks[blockIndex]
        val shifted = block.copy(
            position = Coord(block.position.x + dx, block.position.y + dy)
        )
        // find a genuinely different, non-overlapping position by trying shifts
        val candidates = listOf(shifted, block.copy(position = Coord(block.position.x + 1, block.position.y)),
            block.copy(position = Coord(block.position.x, block.position.y + 1)),
            block.copy(position = Coord(block.position.x - 1, block.position.y)),
            block.copy(position = Coord(block.position.x, block.position.y - 1)))
        val chosen = candidates.firstOrNull { c ->
            c.position != block.position && isMoveValid(c, level.blocks - block, level.dimension)
        } ?: block
        val blocks = level.blocks.toMutableList().also { it[blockIndex] = chosen }
        return level.copy(blocks = blocks, lastMovedBlockIndex = blockIndex)
    }

    @Test
    fun boardLeftPartWaySurvivesALevelSwitchAndProcessDeath() {
        val vm = vm()
        vm.loadLevel(0, 0)
        val start = vm.uiState.value.currentLevelData!!
        val moved = legalMove(start)
        vm.onBlockMoved(moved)

        // Off to another puzzle and back.
        vm.loadLevel(0, 1)
        vm.loadLevel(0, 0)
        assertEquals(moved.blocks, vm.uiState.value.currentLevelData!!.blocks)
        assertEquals(1, vm.getCurrentMoves())

        // A fresh ViewModel stands in for the process being killed; undo history comes back too.
        val revived = vm()
        revived.loadLevel(0, 0)
        assertEquals(moved.blocks, revived.uiState.value.currentLevelData!!.blocks)
        assertEquals(listOf(start.blocks), revived.uiState.value.history.map { it.blocks })
        revived.onUndo()
        assertEquals(start.blocks, revived.uiState.value.currentLevelData!!.blocks)

        // Undone back to the start: nothing left to restore.
        val again = vm()
        again.loadLevel(0, 0)
        assertEquals(start, again.uiState.value.currentLevelData)
    }

    @Test
    fun restartAndWinForgetTheSavedBoard() {
        val vm = vm()
        vm.loadLevel(0, 0)
        val start = vm.uiState.value.currentLevelData!!
        val moved = legalMove(start)
        vm.onBlockMoved(moved)
        vm.onRestart()
        vm().also { it.loadLevel(0, 0) }.let { assertEquals(start, it.uiState.value.currentLevelData) }

        vm.onBlockMoved(moved)
        vm.onLevelWon()
        vm().also { it.loadLevel(0, 0) }.let {
            assertEquals(start, it.uiState.value.currentLevelData)
            assertTrue(it.uiState.value.history.isEmpty())
        }
    }

    @Test
    fun onBlockMovedWithoutLevelIsNoOp() {
        val vm = vm()
        val fake = LevelPack.PACKS[0].levels[0].copy(lastMovedBlockIndex = 0)
        vm.onBlockMoved(fake)
        assertEquals(UnblockJamUiState(), vm.uiState.value)
    }

    @Test
    fun movesCollapseSameBlockAndHistoryTracksDifferentBlocks() {
        val vm = vm()
        vm.loadLevel(0, 0)
        val start = vm.uiState.value.currentLevelData!!
        val idx = lastBlock(start)

        val first = movedCopy(start, idx, 1, 0)
        vm.onBlockMoved(first)
        assertEquals(1, vm.uiState.value.history.size)
        assertEquals(first, vm.uiState.value.currentLevelData)
        assertEquals(1, vm.getCurrentMoves())

        // continuing to drag same block: current updates, history does not grow
        val second = movedCopy(first, idx, 1, 0)
        if (second.blocks != first.blocks) {
            vm.onBlockMoved(second)
            assertEquals(1, vm.uiState.value.history.size)
            assertEquals(second, vm.uiState.value.currentLevelData)
            assertEquals(1, vm.getCurrentMoves())
        }

        // moving back to the start layout collapses the history entry
        vm.onBlockMoved(start.copy(lastMovedBlockIndex = idx))
        // collapse triggers when new blocks equal history.last().blocks (the start)
        assertEquals(start.blocks, vm.uiState.value.currentLevelData!!.blocks)
    }

    @Test
    fun undoAndRestartRestoreHistory() {
        val vm = vm()
        vm.loadLevel(0, 0)
        val start = vm.uiState.value.currentLevelData!!
        // undo/restart with empty history are no-ops
        vm.onUndo(); vm.onRestart()
        assertEquals(start, vm.uiState.value.currentLevelData)

        val idx = lastBlock(start)
        val moved = movedCopy(start, idx, 1, 0)
        vm.onBlockMoved(moved)
        assertEquals(1, vm.uiState.value.history.size)

        vm.onRestart()
        assertEquals(start, vm.uiState.value.currentLevelData)
        assertTrue(vm.uiState.value.history.isEmpty())

        vm.onBlockMoved(moved)
        vm.onUndo()
        assertEquals(start.blocks, vm.uiState.value.currentLevelData!!.blocks)
        assertTrue(vm.uiState.value.history.isEmpty())
        assertEquals(0, vm.getCurrentMoves())
    }

    @Test
    fun levelWonBlocksFurtherMovesUndoAndRestart() {
        val vm = vm()
        vm.onLevelWon() // no pack loaded: no-op
        assertFalse(vm.uiState.value.isLevelWon)

        vm.loadLevel(0, 0)
        val start = vm.uiState.value.currentLevelData!!
        val idx = lastBlock(start)
        val moved = movedCopy(start, idx, 1, 0)
        vm.onBlockMoved(moved)
        assertEquals(1, vm.getCurrentMoves())

        vm.onLevelWon()
        assertTrue(vm.uiState.value.isLevelWon)
        // winning move increment: last moved was not main block, so +1
        assertEquals(2, vm.getCurrentMoves())
        // the win persists a best score for the level asynchronously
        val wonId = start.id
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(30_000) { vm.levelStats.first { it.containsKey(wonId) } }
        }
        assertTrue(vm.levelStats.value.getValue(wonId).bestScore <= 2)

        vm.onLevelWon() // second call no-op
        vm.onUndo()
        vm.onRestart()
        // undo/restart are blocked once won, so history and win flag survive them
        assertTrue(vm.uiState.value.isLevelWon)
        assertEquals(1, vm.uiState.value.history.size)
        assertEquals(2, vm.getCurrentMoves())
    }

    @Test
    fun loadLevelReloadsSameLevelPristineAfterWin() {
        val vm = vm()
        vm.loadLevel(0, 0)
        val start = vm.uiState.value.currentLevelData!!
        val idx = lastBlock(start)
        vm.onBlockMoved(movedCopy(start, idx, 1, 0))
        vm.onLevelWon()
        assertTrue(vm.uiState.value.isLevelWon)

        // re-entering the won level (back out, tap it again) must restart it
        // instead of showing the frozen won board
        vm.loadLevel(0, 0)
        val reloaded = vm.uiState.value
        assertFalse(reloaded.isLevelWon)
        assertTrue(reloaded.history.isEmpty())
        assertEquals(LevelPack.PACKS[0].levels[0], reloaded.currentLevelData)
        assertEquals(0, vm.getCurrentMoves())
    }

    @Test
    fun levelWonAfterMovingMainBlockHasNoExtraIncrement() {
        val vm = vm()
        vm.loadLevel(0, 0)
        val start = vm.uiState.value.currentLevelData!!
        // simulate main-block as last moved (index 0), no actual geometry change needed:
        // a same-index update only replaces current data
        vm.onBlockMoved(start.copy(lastMovedBlockIndex = 0))
        // changing the last-moved index counts as a move even without geometry change
        assertEquals(1, vm.uiState.value.history.size)
        vm.onLevelWon()
        assertTrue(vm.uiState.value.isLevelWon)
        // last moved is the main block, so no extra winning increment beyond history
        assertEquals(1, vm.getCurrentMoves())
    }

    @Test
    fun repositoryCountersAndDismissNotification() {
        val vm = vm()
        val beforeMoves = vm.repository.getTotalMoves()
        vm.repository.incrementTotalMoves()
        assertEquals(beforeMoves + 1, vm.repository.getTotalMoves())
        val beforeUndo = vm.repository.getUndoCount()
        vm.repository.incrementUndoCount()
        assertEquals(beforeUndo + 1, vm.repository.getUndoCount())
        vm.dismissAchievementNotification()
        assertNull(vm.achievementsManager.newAchievement.value)
        assertNotNull(vm.levelStats.value)
        assertNotNull(vm.dailyStats.value)
        assertTrue(vm.dailyDay.value > 0)
    }

    @Test
    fun reminderSettersDoNotCrashAndStateFlowsExist() {
        val vm = vm()
        vm.setReminderEnabled(false)
        vm.setReminderTime(9, 30)
        // flows have initial values from stateIn defaults / store
        assertEquals(false, vm.reminderEnabled.value)
        assertTrue(vm.reminderMinutesOfDay.value in 0..1439)
        assertTrue(vm.dailyCompleted.value >= 0)
        assertTrue(vm.dailyStreak.value >= 0)
    }

    @Test
    fun dailyPackGenerationAndDailyWin() {
        val vm = vm()
        vm.refreshDaily()
        val pack = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(120_000) {
                vm.dailyPack.first { it != null }
            }
        }!!
        assertEquals(com.vayunmathur.games.unblockjam.data.DailyLevelGenerator.LEVELS_PER_DAY, pack.levels.size)
        // second refresh is a no-op once today's pack exists
        vm.refreshDaily()

        vm.loadLevel(UnblockJamViewModel.DAILY_PACK_INDEX, 0)
        assertEquals(UnblockJamViewModel.DAILY_PACK_INDEX, vm.uiState.value.packIndex)
        assertEquals(pack.levels[0], vm.uiState.value.currentLevelData)

        vm.onLevelWon()
        assertTrue(vm.uiState.value.isLevelWon)
        val wonId = pack.levels[0].id
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(30_000) {
                vm.dailyStats.first { it.containsKey(wonId) }
            }
        }
        assertTrue(vm.dailyStats.value.containsKey(wonId))
    }

    @Test
    fun uiStateDataClassCopy() {
        val state = UnblockJamUiState(packIndex = 1, levelIndex = 2, isLevelWon = true)
        assertEquals(1, state.packIndex)
        assertEquals(state, state.copy())
        assertFalse(state == state.copy(levelIndex = 3))
    }
}
