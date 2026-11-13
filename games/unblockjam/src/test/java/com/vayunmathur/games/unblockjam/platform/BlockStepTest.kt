package com.vayunmathur.games.unblockjam.platform

import com.vayunmathur.games.unblockjam.data.Block
import com.vayunmathur.games.unblockjam.data.Coord
import com.vayunmathur.games.unblockjam.data.Dimension
import com.vayunmathur.games.unblockjam.data.LevelData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BlockStepTest {
    private fun level(vararg blocks: Block) = LevelData(
        id = "step", dimension = Dimension(6, 6), exit = Coord(6, 2),
        blocks = blocks.toList(), optimalMoves = 1,
    )

    private val target = Block(Coord(2, 2), Dimension(2, 1), false)
    private val vertical = Block(Coord(5, 0), Dimension(1, 2), false)
    private val wall = Block(Coord(0, 0), Dimension(1, 1), true)

    @Test
    fun stepsAlongTheAxisAndRecordsTheMovedBlock() {
        val moved = assertIs<StepResult.Moved>(stepBlock(level(target, vertical), 0, -1, 0))
        assertEquals(Coord(1, 2), moved.level.blocks[0].position)
        assertEquals(0, moved.level.lastMovedBlockIndex)
        val down = assertIs<StepResult.Moved>(stepBlock(level(target, vertical), 1, 0, 1))
        assertEquals(Coord(5, 1), down.level.blocks[1].position)
    }

    @Test
    fun refusesOffAxisWallsCollisionsAndEdges() {
        assertEquals(StepResult.Blocked, stepBlock(level(target), 0, 0, 1)) // off axis
        assertEquals(StepResult.Blocked, stepBlock(level(target, wall), 1, 1, 0)) // fixed
        assertEquals(StepResult.Blocked, stepBlock(level(target, vertical), 1, 0, -1)) // board edge
        val blocker = Block(Coord(4, 1), Dimension(1, 2), false)
        assertEquals(StepResult.Blocked, stepBlock(level(target, blocker), 0, 1, 0)) // collision
        assertEquals(StepResult.Blocked, stepBlock(level(target), 7, 1, 0)) // no such block
    }

    @Test
    fun targetAtTheEdgeOfTheExitRowWinsByStepping() {
        val atEdge = target.copy(position = Coord(4, 2))
        assertEquals(StepResult.Won, stepBlock(level(atEdge), 0, 1, 0))
        // An ordinary block at the edge just stops.
        val other = Block(Coord(4, 4), Dimension(2, 1), false)
        assertEquals(StepResult.Blocked, stepBlock(level(target, other), 1, 1, 0))
    }
}
