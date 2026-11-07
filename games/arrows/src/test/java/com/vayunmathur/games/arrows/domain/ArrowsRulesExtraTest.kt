package com.vayunmathur.games.arrows.domain

import com.vayunmathur.games.arrows.data.ArrowPiece
import com.vayunmathur.games.arrows.data.ArrowsGameState
import com.vayunmathur.games.arrows.data.ArrowsPuzzle
import com.vayunmathur.games.arrows.data.Direction
import com.vayunmathur.games.arrows.data.GameMode
import com.vayunmathur.games.arrows.data.Mirror
import com.vayunmathur.games.arrows.data.STARTING_HEARTS
import com.vayunmathur.games.arrows.data.TapOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArrowsRulesExtraTest {
    private val cols = 5
    private fun at(r: Int, c: Int) = r * cols + c
    private fun puzzle(pieces: List<ArrowPiece>, mirrors: Map<Int, Mirror> = emptyMap()) =
        ArrowsPuzzle(cols, 5, pieces, mirrors)
    private fun state(
        p: ArrowsPuzzle,
        removed: Set<Int> = emptySet(),
        hearts: Int = STARTING_HEARTS,
        blockedId: Int = -1,
    ) = ArrowsGameState(p, removed, hearts, 1, GameMode.CASUAL, blockedId)
    private fun arrow(id: Int, row: Int, col: Int, dir: Direction) =
        ArrowPiece(id, listOf(at(row - dir.dRow, col - dir.dCol), at(row, col)), dir)

    @Test
    fun travelClearsWithFullRoute() {
        val piece = arrow(0, 2, 2, Direction.RIGHT)
        val t = ArrowsRules.travel(state(puzzle(listOf(piece))), piece)
        assertTrue(t.clears)
        assertEquals(2, t.advance)
        // route = body (2 cells) + path (2 cells)
        assertEquals(piece.cells + listOf(at(2, 3), at(2, 4)), t.route)
    }

    @Test
    fun travelBlockedAdvanceCountsFreeCells() {
        val mover = arrow(0, 2, 2, Direction.RIGHT)
        val blocker = arrow(1, 2, 4, Direction.UP)
        val t = ArrowsRules.travel(state(puzzle(listOf(mover, blocker))), mover)
        assertFalse(t.clears)
        // path is (2,3),(2,4); blocker occupies (2,4) so only 1 free cell before it
        assertEquals(1, t.advance)
        assertEquals(mover.cells + listOf(at(2, 3), at(2, 4)), t.route)
    }

    @Test
    fun travelWedgedHasZeroAdvance() {
        val mover = arrow(0, 2, 3, Direction.RIGHT)
        val blocker = arrow(1, 2, 4, Direction.UP)
        val t = ArrowsRules.travel(state(puzzle(listOf(mover, blocker))), mover)
        assertFalse(t.clears)
        assertEquals(0, t.advance)
    }

    @Test
    fun travelMirrorRingDoesNotClearAndHasNoAdvance() {
        val piece = ArrowPiece(0, listOf(at(2, 0), at(2, 1)), Direction.UP)
        val ring = mapOf(
            at(1, 1) to Mirror.FORWARD, at(1, 3) to Mirror.BACK,
            at(3, 3) to Mirror.FORWARD, at(3, 1) to Mirror.BACK,
        )
        val t = ArrowsRules.travel(state(puzzle(listOf(piece), ring)), piece)
        assertFalse(t.clears)
        assertEquals(0, t.advance)
        assertEquals(piece.cells, t.route)
    }

    @Test
    fun exitPathAllDirectionsAndMirrorChain() {
        val p = puzzle(emptyList())
        assertEquals(listOf(at(1, 2), at(0, 2)), ArrowsRules.exitPath(p, at(2, 2), Direction.UP))
        assertEquals(listOf(at(3, 2), at(4, 2)), ArrowsRules.exitPath(p, at(2, 2), Direction.DOWN))
        assertEquals(listOf(at(2, 1), at(2, 0)), ArrowsRules.exitPath(p, at(2, 2), Direction.LEFT))
        assertEquals(emptyList(), ArrowsRules.exitPath(p, at(0, 2), Direction.UP))
        // Mirror chain: RIGHT into FORWARD at (2,3) -> UP, then BACK at (0,3)? no row - check simple
        val p2 = puzzle(emptyList(), mapOf(at(2, 3) to Mirror.BACK, at(4, 3) to Mirror.FORWARD))
        // RIGHT to (2,3) BACK->DOWN to (3,3),(4,3) FORWARD: DOWN->LEFT to (4,2),(4,1),(4,0) out
        assertEquals(
            listOf(at(2, 3), at(3, 3), at(4, 3), at(4, 2), at(4, 1), at(4, 0)),
            ArrowsRules.exitPath(p2, at(2, 2), Direction.RIGHT),
        )
    }

    @Test
    fun tapClearsOnlyTargetAndResetsBlockedFlag() {
        val a = arrow(0, 2, 2, Direction.RIGHT)
        val free = ArrowPiece(1, listOf(at(0, 4), at(1, 4)), Direction.DOWN)
        val s = state(puzzle(listOf(a, free)), blockedId = 0)
        val (next, outcome) = ArrowsRules.tap(s, 1)
        assertEquals(TapOutcome.CLEARED, outcome)
        assertEquals(setOf(1), next.removed)
        assertEquals(-1, next.blockedId)
        assertEquals(STARTING_HEARTS, next.hearts)
    }

    @Test
    fun tapBlockedSetsFlagAndKeepsRemoved() {
        val mover = arrow(0, 2, 2, Direction.RIGHT)
        val blocker = arrow(1, 2, 4, Direction.UP)
        val s = state(puzzle(listOf(mover, blocker)), removed = emptySet())
        val (next, outcome) = ArrowsRules.tap(s, 0)
        assertEquals(TapOutcome.BLOCKED, outcome)
        assertEquals(0, next.blockedId)
        assertTrue(next.removed.isEmpty())
    }

    @Test
    fun tapUnknownAndAlreadyRemovedIgnoredUnchanged() {
        val a = arrow(0, 2, 2, Direction.RIGHT)
        val s = state(puzzle(listOf(a)), removed = setOf(0))
        assertEquals(s to TapOutcome.IGNORED, ArrowsRules.tap(s, 0))
        val fresh = state(puzzle(listOf(a)))
        assertEquals(fresh to TapOutcome.IGNORED, ArrowsRules.tap(fresh, 42))
    }

    @Test
    fun solveEmptyPuzzleReturnsEmptyOrder() {
        assertEquals(emptyList(), ArrowsRules.solve(puzzle(emptyList())))
    }

    @Test
    fun solveChainReturnsValidClearingOrder() {
        val a = arrow(0, 2, 2, Direction.RIGHT)
        val b = arrow(1, 2, 4, Direction.UP)
        val p = puzzle(listOf(a, b))
        val order = assertNotNull(ArrowsRules.solve(p))
        var s = state(p)
        for (id in order) {
            val (next, outcome) = ArrowsRules.tap(s, id)
            assertEquals(TapOutcome.CLEARED, outcome, "tap $id in solver order should clear")
            s = next
        }
        assertTrue(s.isWon)
    }

    @Test
    fun clearableNowEmptyWhenAllBlockedAndAllWhenFree() {
        val left = ArrowPiece(0, listOf(at(2, 0), at(2, 1)), Direction.RIGHT)
        val right = ArrowPiece(1, listOf(at(2, 4), at(2, 3)), Direction.LEFT)
        assertTrue(ArrowsRules.clearableNow(state(puzzle(listOf(left, right)))).isEmpty())
        val solo = arrow(0, 2, 2, Direction.RIGHT)
        assertEquals(listOf(0), ArrowsRules.clearableNow(state(puzzle(listOf(solo)))).map { it.id })
        assertNull(ArrowsRules.solve(puzzle(listOf(left, right))))
    }

    @Test
    fun resetAfterFailureRestoresHeartsAndClearsFlag() {
        val a = arrow(0, 2, 2, Direction.RIGHT)
        val s = state(puzzle(listOf(a)), removed = setOf(0), hearts = 0, blockedId = 0)
        val reset = ArrowsRules.resetAfterFailure(s)
        assertEquals(STARTING_HEARTS, reset.hearts)
        assertTrue(reset.removed.isEmpty())
        assertEquals(-1, reset.blockedId)
        assertFalse(reset.isFailed)
    }
}
