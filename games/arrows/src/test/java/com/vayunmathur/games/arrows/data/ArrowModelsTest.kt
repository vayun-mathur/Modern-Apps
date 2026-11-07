package com.vayunmathur.games.arrows.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArrowModelsTest {

    @Test
    fun directionOppositesAndDeltas() {
        assertEquals(Direction.DOWN, Direction.UP.opposite)
        assertEquals(Direction.UP, Direction.DOWN.opposite)
        assertEquals(Direction.LEFT, Direction.RIGHT.opposite)
        assertEquals(Direction.RIGHT, Direction.LEFT.opposite)
        assertEquals(-1 to 0, Direction.UP.dRow to Direction.UP.dCol)
        assertEquals(1 to 0, Direction.DOWN.dRow to Direction.DOWN.dCol)
        assertEquals(0 to 1, Direction.RIGHT.dRow to Direction.RIGHT.dCol)
        assertEquals(0 to -1, Direction.LEFT.dRow to Direction.LEFT.dCol)
        for (d in Direction.entries) assertEquals(d, d.opposite.opposite)
    }

    @Test
    fun mirrorReflectTables() {
        // FORWARD (/): RIGHT<->UP, LEFT<->DOWN
        assertEquals(Direction.UP, Mirror.FORWARD.reflect(Direction.RIGHT))
        assertEquals(Direction.RIGHT, Mirror.FORWARD.reflect(Direction.UP))
        assertEquals(Direction.DOWN, Mirror.FORWARD.reflect(Direction.LEFT))
        assertEquals(Direction.LEFT, Mirror.FORWARD.reflect(Direction.DOWN))
        // BACK (\): RIGHT<->DOWN, LEFT<->UP
        assertEquals(Direction.DOWN, Mirror.BACK.reflect(Direction.RIGHT))
        assertEquals(Direction.RIGHT, Mirror.BACK.reflect(Direction.DOWN))
        assertEquals(Direction.UP, Mirror.BACK.reflect(Direction.LEFT))
        assertEquals(Direction.LEFT, Mirror.BACK.reflect(Direction.UP))
    }

    @Test
    fun arrowPieceHeadAndLength() {
        val p = ArrowPiece(7, listOf(3, 4, 9), Direction.RIGHT)
        assertEquals(9, p.head)
        assertEquals(3, p.length)
        assertEquals(7, p.id)
        assertEquals(p, ArrowPiece(7, listOf(3, 4, 9), Direction.RIGHT))
        assertTrue(p != ArrowPiece(7, listOf(3, 4, 9), Direction.LEFT))
    }

    @Test
    fun puzzleContainsAndCellCount() {
        val puzzle = ArrowsPuzzle(5, 6, emptyList())
        assertEquals(30, puzzle.cellCount)
        assertTrue(puzzle.contains(0, 0))
        assertTrue(puzzle.contains(5, 4))
        assertFalse(puzzle.contains(6, 0))
        assertFalse(puzzle.contains(0, 5))
        assertFalse(puzzle.contains(-1, 0))
        assertFalse(puzzle.contains(0, -1))
        assertEquals(emptyMap(), puzzle.mirrors)
    }

    private fun twoPiecePuzzle(): ArrowsPuzzle {
        // 5x5: piece0 cells 0,1 head1 RIGHT; piece1 cells 10,15 head15 DOWN
        return ArrowsPuzzle(
            5, 5,
            listOf(
                ArrowPiece(0, listOf(0, 1), Direction.RIGHT),
                ArrowPiece(1, listOf(10, 15), Direction.DOWN),
            ),
        )
    }

    @Test
    fun gameStateDerivedPropertiesFresh() {
        val s = ArrowsGameState(twoPiecePuzzle(), emptySet(), STARTING_HEARTS, 3, GameMode.CASUAL)
        assertEquals(2, s.remaining.size)
        assertFalse(s.isWon)
        assertFalse(s.isFailed)
        assertFalse(s.isOver)
        assertEquals(-1, s.blockedId)
        assertEquals(mapOf(0 to 0, 1 to 0, 10 to 1, 15 to 1), s.occupancy)
        assertEquals(0, s.pieceAt(1)?.id)
        assertEquals(1, s.pieceAt(10)?.id)
        assertNull(s.pieceAt(24))
    }

    @Test
    fun gameStateWonFailedAndRemoved() {
        val won = ArrowsGameState(twoPiecePuzzle(), setOf(0, 1), 0, 1, GameMode.DAILY)
        assertTrue(won.isWon)
        assertFalse(won.isFailed)
        assertTrue(won.isOver)
        assertTrue(won.remaining.isEmpty())
        assertTrue(won.occupancy.isEmpty())
        assertNull(won.pieceAt(0))

        val failed = ArrowsGameState(twoPiecePuzzle(), emptySet(), 0, 1, GameMode.CASUAL)
        assertTrue(failed.isFailed)
        assertTrue(failed.isOver)

        val partial = ArrowsGameState(twoPiecePuzzle(), setOf(0), 2, 1, GameMode.CASUAL, blockedId = 1)
        assertEquals(listOf(1), partial.remaining.map { it.id })
        assertEquals(1, partial.blockedId)
        assertFalse(partial.isOver)
    }

    @Test
    fun emptyPuzzleIsWon() {
        val s = ArrowsGameState(ArrowsPuzzle(3, 3, emptyList()), emptySet(), 3, 1, GameMode.CASUAL)
        assertTrue(s.isWon)
        assertTrue(s.isOver)
    }

    @Test
    fun boardSizeTiersAndCap() {
        assertEquals(5 to 6, boardSizeForLevel(1))
        assertEquals(5 to 6, boardSizeForLevel(3))
        assertEquals(5 to 7, boardSizeForLevel(4))
        assertEquals(5 to 7, boardSizeForLevel(8))
        assertEquals(6 to 8, boardSizeForLevel(9))
        assertEquals(6 to 9, boardSizeForLevel(21))
        assertEquals(7 to 9, boardSizeForLevel(22))
        assertEquals(8 to 11, boardSizeForLevel(60))
        assertEquals(MAX_BOARD, boardSizeForLevel(61))
        assertEquals(MAX_BOARD, boardSizeForLevel(1000))
        assertEquals(9 to 12, MAX_BOARD)
        assertEquals(61, BIGGEST_BOARD_LEVEL)
    }

    @Test
    fun arrowCountGrowsAndIsCapped() {
        assertTrue(arrowCountForLevel(1) >= 2)
        assertTrue(arrowCountForLevel(40) > arrowCountForLevel(1))
        for (level in listOf(1, 10, 30, 61, 100)) {
            val (c, r) = boardSizeForLevel(level)
            assertTrue(arrowCountForLevel(level) <= (c * r) / 4, "level $level over cap")
            assertTrue(arrowCountForLevel(level) > 0)
        }
    }

    @Test
    fun mirrorCountRamp() {
        assertEquals(0, mirrorCountForLevel(1))
        assertEquals(0, mirrorCountForLevel(MIRROR_FIRST_LEVEL - 1))
        assertEquals(1, mirrorCountForLevel(MIRROR_FIRST_LEVEL))
        assertEquals(1, mirrorCountForLevel(MIRROR_FIRST_LEVEL + 5))
        assertEquals(2, mirrorCountForLevel(MIRROR_FIRST_LEVEL + 6))
        assertEquals(4, mirrorCountForLevel(200))
        assertEquals(11, MIRROR_FIRST_LEVEL)
    }

    @Test
    fun availabilityCapRounding() {
        assertEquals(2, availabilityCapFor(1))
        assertEquals(2, availabilityCapFor(6))
        assertEquals(2, availabilityCapFor(8))
        assertEquals(3, availabilityCapFor(9))
        assertEquals(5, availabilityCapFor(19))
        assertEquals(5, availabilityCapFor(20))
    }

    @Test
    fun dailyAndStartingConstants() {
        assertEquals(3, STARTING_HEARTS)
        assertEquals(7 to 9, DAILY_BOARD)
        assertEquals(14, DAILY_ARROWS)
        assertEquals(2, DAILY_MIRRORS)
        assertEquals(listOf(GameMode.CASUAL, GameMode.DAILY), GameMode.entries.toList())
        assertEquals(listOf(TapOutcome.CLEARED, TapOutcome.BLOCKED, TapOutcome.IGNORED), TapOutcome.entries.toList())
    }
}
