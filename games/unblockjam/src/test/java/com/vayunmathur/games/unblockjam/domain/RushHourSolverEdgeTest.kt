package com.vayunmathur.games.unblockjam.domain

import com.vayunmathur.games.unblockjam.data.Block
import com.vayunmathur.games.unblockjam.data.Coord
import com.vayunmathur.games.unblockjam.data.Dimension
import com.vayunmathur.games.unblockjam.data.LevelData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RushHourSolverEdgeTest {

    private fun level(
        blocks: List<Block>,
        dimension: Dimension = Dimension(6, 6),
        exit: Coord = Coord(6, 2),
    ) = LevelData("t", dimension, exit, blocks, 0)

    private fun hBlock(x: Int, y: Int, len: Int = 2, fixed: Boolean = false) =
        Block(Coord(x, y), Dimension(len, 1), fixed)

    private fun vBlock(x: Int, y: Int, len: Int = 2, fixed: Boolean = false) =
        Block(Coord(x, y), Dimension(1, len), fixed)

    @Test
    fun malformedBoardsReturnNull() {
        assertNull(RushHourSolver.explore(level(emptyList())))
        assertNull(RushHourSolver.optimalMoves(level(emptyList())))
        // vertical main block cannot drive out horizontally
        assertNull(RushHourSolver.explore(level(listOf(vBlock(0, 2)))))
        // main block off the exit row
        assertNull(RushHourSolver.explore(level(listOf(hBlock(0, 1)))))
        // too many blocks for bit packing
        val many = listOf(hBlock(0, 2)) + (1..16).map { vBlock(it % 6, (it * 2) % 5) }
        assertNull(RushHourSolver.explore(level(many)))
        // board extent too large
        assertNull(
            RushHourSolver.explore(
                level(listOf(hBlock(0, 2)), dimension = Dimension(17, 6))
            )
        )
        assertNull(
            RushHourSolver.explore(
                level(listOf(hBlock(0, 2)), dimension = Dimension(6, 17))
            )
        )
    }

    @Test
    fun openBoardWinsInOneAndExplorationRebuildsLevels() {
        val lvl = level(listOf(hBlock(0, 2), vBlock(4, 0)))
        assertEquals(1, RushHourSolver.optimalMoves(lvl))
        val exploration = assertNotNull(RushHourSolver.explore(lvl))
        assertEquals(0, exploration.startIndex)
        assertTrue(exploration.stateCount > 1)
        assertEquals(1, exploration.movesToWin(exploration.startIndex))
        // start state rebuilds the original layout
        assertEquals(lvl.blocks, exploration.levelAt(exploration.startIndex, lvl).blocks)
        // every state rebuilds a level that keeps ids and template blocks' shapes
        val other = exploration.levelAt(exploration.stateCount - 1, lvl)
        assertEquals("t", other.id)
        assertEquals(lvl.blocks.map { it.dimension }, other.blocks.map { it.dimension })
        assertEquals(lvl.blocks.map { it.fixed }, other.blocks.map { it.fixed })
        // a state where the blocker still blocks needs at least the winning move plus a move
        val distances = exploration.distanceToGoal
        assertTrue(distances.all { it >= 0 }, "open board: every state should reach a goal")
    }

    @Test
    fun blockedBoardRequiresMovingBlockerFirst() {
        // vertical blocker covering exit row at x=2; main cannot pass until it moves
        val lvl = level(listOf(hBlock(0, 2), vBlock(2, 1, len = 3)))
        assertEquals(2, RushHourSolver.optimalMoves(lvl))
    }

    @Test
    fun unsolvableBoardReturnsNullOptimalButExplores() {
        // horizontal block fixed on exit row ahead of main: can never be cleared
        val lvl = level(listOf(hBlock(0, 2), hBlock(3, 2, fixed = true)))
        val exploration = assertNotNull(RushHourSolver.explore(lvl))
        assertTrue(exploration.stateCount >= 1)
        assertEquals(-1, exploration.distanceToGoal[exploration.startIndex])
        assertNull(RushHourSolver.optimalMoves(lvl))
    }

    @Test
    fun fixedBlocksDoNotSlideButOthersDo() {
        // fixed vertical blocker can slide nowhere; an identical free blocker can move away
        val fixedBoard = level(listOf(hBlock(0, 2), vBlock(2, 2, fixed = true), vBlock(5, 0)))
        // blocker sits at (2,2)-(2,3): covers exit row, fixed, so only other moves exist;
        // main range is blocked, so winning requires... it cannot win: unsolvable
        assertNull(RushHourSolver.optimalMoves(fixedBoard))
        val freeBoard = level(listOf(hBlock(0, 2), vBlock(2, 2), vBlock(5, 0)))
        assertEquals(2, RushHourSolver.optimalMoves(freeBoard))
    }

    @Test
    fun maxStatesCapReturnsNull() {
        val lvl = level(listOf(hBlock(0, 2), vBlock(4, 0), vBlock(5, 3)))
        assertNull(RushHourSolver.explore(lvl, maxStates = 1))
        assertNull(RushHourSolver.optimalMoves(lvl, maxStates = 1))
        assertNotNull(RushHourSolver.explore(lvl, maxStates = 200_000))
    }

    @Test
    fun alreadyAtExitEdgeIsGoal() {
        val lvl = level(listOf(hBlock(4, 2)))
        assertEquals(1, RushHourSolver.optimalMoves(lvl))
    }

    @Test
    fun wideBlocksAndCrossLengthsAreRespected() {
        // 2x2 square block (crossLength 2) blocking exit row and row below
        val square = Block(Coord(3, 2), Dimension(2, 2), false)
        val lvl = level(listOf(hBlock(0, 2), square))
        // square can slide down/up/left/right; need move it off row 2 then drive out = 2
        assertEquals(2, RushHourSolver.optimalMoves(lvl))
    }
}
