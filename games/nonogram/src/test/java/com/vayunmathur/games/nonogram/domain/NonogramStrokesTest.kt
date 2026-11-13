package com.vayunmathur.games.nonogram.domain

import com.vayunmathur.games.nonogram.data.GameMode
import com.vayunmathur.games.nonogram.data.MarkMode
import com.vayunmathur.games.nonogram.data.NonogramGameState
import com.vayunmathur.games.nonogram.data.NonogramPuzzle
import com.vayunmathur.games.nonogram.data.STARTING_HEARTS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NonogramStrokesTest {
    // 5x5, row-major. Row 0 = # # . # #, row 1 = # # # # #, others blank except column 0.
    private val rows = listOf(
        "##.##",
        "#####",
        "#....",
        "#....",
        "#....",
    )
    private val puzzle = NonogramPuzzle.from(5, rows.joinToString("").map { it == '#' })
    private fun game(crossed: Set<Int> = emptySet(), filled: Set<Int> = emptySet(), hearts: Int = STARTING_HEARTS) =
        NonogramGameState(puzzle, filled, crossed, emptySet(), hearts, GameMode.CASUAL, 1)
    private fun idx(row: Int, col: Int) = row * 5 + col

    /** Runs a whole stroke: down on [cells].first(), then the finger over each following cell. */
    private fun stroke(start: NonogramGameState, mode: MarkMode, vararg cells: Int): StrokeStep {
        var step = NonogramStrokes.begin(start, cells.first(), mode)
        for (c in cells.drop(1)) step = NonogramStrokes.extend(step.game, step.stroke, c)
        return step
    }

    @Test fun aTapIsAOneCellStroke() {
        val s = stroke(game(), MarkMode.FILL, idx(1, 2))
        assertEquals(setOf(idx(1, 2)), s.game.filled)
        assertEquals(StrokeAction.FILL, s.stroke.action)
        assertNull(s.stroke.axis)
    }

    @Test fun fillDragPaintsARowAndWalksSkippedCells() {
        // Finger jumps straight from column 0 to column 4: columns 1-3 still get filled.
        val s = stroke(game(), MarkMode.FILL, idx(1, 0), idx(1, 4))
        assertEquals((0..4).map { idx(1, it) }.toSet(), s.game.filled)
        assertEquals(StrokeAxis.ROW, s.stroke.axis)
        assertEquals(STARTING_HEARTS, s.game.hearts)
    }

    @Test fun fillStopsAtTheFirstWrongCellAndCostsOneHeart() {
        // Row 0 is "##.##": the stroke fills 0 and 1, reveals 2, and nothing after it.
        val s = stroke(game(), MarkMode.FILL, idx(0, 0), idx(0, 1), idx(0, 2), idx(0, 3), idx(0, 4))
        assertEquals(setOf(idx(0, 0), idx(0, 1)), s.game.filled)
        assertEquals(setOf(idx(0, 2)), s.game.revealedBlanks)
        assertEquals(STARTING_HEARTS - 1, s.game.hearts, "one heart per stroke, never per cell")
        assertTrue(s.stroke.stopped)
        // Coming back over earlier cells after the stop changes nothing either.
        val after = NonogramStrokes.extend(s.game, s.stroke, idx(0, 0))
        assertEquals(s.game, after.game)
    }

    @Test fun aWrongFirstCellStopsTheStrokeImmediately() {
        val s = stroke(game(), MarkMode.FILL, idx(2, 1), idx(2, 2), idx(2, 3))
        assertEquals(setOf(idx(2, 1)), s.game.revealedBlanks)
        assertEquals(STARTING_HEARTS - 1, s.game.hearts)
    }

    @Test fun theStrokeLocksToItsFirstLine() {
        // Mostly horizontal with a wobble into the next row: stays in row 2.
        val s = stroke(game(), MarkMode.CROSS, idx(2, 1), idx(2, 2), idx(3, 3), idx(3, 4))
        assertEquals((1..4).map { idx(2, it) }.toSet(), s.game.crossed)
        // Mostly vertical: locks to the column even when it starts diagonally.
        val v = stroke(game(), MarkMode.CROSS, idx(2, 2), idx(4, 3))
        assertEquals(StrokeAxis.COLUMN, v.stroke.axis)
        assertEquals(setOf(idx(2, 2), idx(3, 2), idx(4, 2)), v.game.crossed)
    }

    @Test fun eachCellIsAppliedOnce() {
        // Cross mode toggles on a tap, but a stroke going back and forth must not un-cross cells.
        val s = stroke(game(), MarkMode.CROSS, idx(3, 1), idx(3, 3), idx(3, 1), idx(3, 4), idx(3, 2))
        assertEquals((1..4).map { idx(3, it) }.toSet(), s.game.crossed)
        // And back past the anchor reaches new cells on the other side (crossing is free anywhere).
        val both = NonogramStrokes.extend(s.game, s.stroke, idx(3, 0))
        assertTrue(idx(3, 0) in both.game.crossed)
    }

    @Test fun crossModeOnAnXClearsTheRun() {
        val start = game(crossed = setOf(idx(3, 1), idx(3, 2), idx(3, 4)))
        val s = stroke(start, MarkMode.CROSS, idx(3, 1), idx(3, 4))
        assertEquals(StrokeAction.CLEAR, s.stroke.action)
        assertEquals(emptySet(), s.game.crossed, "a clear stroke only removes Xs and never adds one at column 3")
    }

    @Test fun fillStrokesStepOverThePlayersXs() {
        val start = game(crossed = setOf(idx(1, 2)))
        val s = stroke(start, MarkMode.FILL, idx(1, 0), idx(1, 4))
        assertEquals(setOf(idx(1, 0), idx(1, 1), idx(1, 3), idx(1, 4)), s.game.filled)
        assertEquals(setOf(idx(1, 2)), s.game.crossed, "the X protects its cell from the sweep")
        // ...but deliberately starting on the X attempts it, like a tap.
        val tap = stroke(start, MarkMode.FILL, idx(1, 2))
        assertTrue(idx(1, 2) in tap.game.filled)
    }

    @Test fun aStrokeStartingOnASettledCellWaitsForTheFirstLiveOne() {
        val start = game(filled = setOf(idx(1, 0)), crossed = setOf(idx(1, 1)))
        // Cross mode starting on a filled cell: the first live cell is the X at column 1, so clear.
        val s = stroke(start, MarkMode.CROSS, idx(1, 0), idx(1, 3))
        assertEquals(StrokeAction.CLEAR, s.stroke.action)
        assertEquals(emptySet(), s.game.crossed)
        assertNull(NonogramStrokes.actionFor(start, idx(1, 0), MarkMode.CROSS))
    }

    @Test fun nothingHappensOnceTheGameIsOver() {
        val over = game(hearts = 0)
        val s = stroke(over, MarkMode.FILL, idx(1, 0), idx(1, 4))
        assertEquals(over, s.game)
    }

    @Test fun theFinalFillEndsTheStroke() {
        // Fill everything but the last picture cell, then sweep row 1 to win mid-stroke.
        val picture = puzzle.solution.indices.filter { puzzle.solution[it] }.toSet()
        val start = game(filled = picture - idx(1, 4))
        val s = stroke(start, MarkMode.FILL, idx(1, 4))
        assertTrue(s.game.isWon); assertTrue(s.stroke.stopped)
    }
}
