package com.vayunmathur.games.unblockjam.platform

import com.vayunmathur.games.unblockjam.data.Block
import com.vayunmathur.games.unblockjam.data.Coord
import com.vayunmathur.games.unblockjam.data.Dimension
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockDragGesturesTest {
    private val board = Dimension(6, 6)
    private fun h(x: Int, y: Int, len: Int = 2) = Block(Coord(x, y), Dimension(len, 1), false)
    private fun v(x: Int, y: Int, len: Int = 2) = Block(Coord(x, y), Dimension(1, len), false)

    @Test
    fun validMovesOnEmptyBoard() {
        assertTrue(isMoveValid(h(0, 0), emptyList(), board))
        assertTrue(isMoveValid(h(4, 5), emptyList(), board))
        assertTrue(isMoveValid(v(5, 4), emptyList(), board))
        assertTrue(isMoveValid(Block(Coord(0, 0), Dimension(6, 6), false), emptyList(), board))
    }

    @Test
    fun outOfBoundsMovesRejected() {
        assertFalse(isMoveValid(h(-1, 0), emptyList(), board))
        assertFalse(isMoveValid(h(0, -1), emptyList(), board))
        assertFalse(isMoveValid(h(5, 0), emptyList(), board)) // 5+2 > 6
        assertFalse(isMoveValid(v(0, 5), emptyList(), board))
        assertFalse(isMoveValid(h(0, 6), emptyList(), board))
    }

    @Test
    fun overlappingMovesRejectedButTouchingAllowed() {
        val other = h(2, 2)
        assertFalse(isMoveValid(h(1, 2), listOf(other), board)) // overlaps one cell
        assertFalse(isMoveValid(h(2, 2), listOf(other), board)) // identical
        assertFalse(isMoveValid(v(3, 1), listOf(other), board)) // crosses at (3,2)
        assertTrue(isMoveValid(h(0, 2), listOf(other), board)) // touches left edge
        assertTrue(isMoveValid(h(4, 2), listOf(other), board)) // touches right edge
        assertTrue(isMoveValid(h(2, 1), listOf(other), board)) // row above
        assertTrue(isMoveValid(h(2, 3), listOf(other), board)) // row below
    }

    @Test
    fun smallerBoardDimensionRespected() {
        val small = Dimension(4, 4)
        assertTrue(isMoveValid(h(2, 3), emptyList(), small))
        assertFalse(isMoveValid(h(3, 3), emptyList(), small))
        assertFalse(isMoveValid(v(3, 3), emptyList(), small))
    }
}
