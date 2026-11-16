package com.vayunmathur.games.sudoku.domain

import com.vayunmathur.games.sudoku.data.BoardSize
import com.vayunmathur.games.sudoku.data.Difficulty
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deeper solver/generator rules than the smoke-level suites: a known published
 * puzzle must solve to its known grid, contradictions in each kind of unit must
 * be rejected, and every generated puzzle must be a sub-grid of its own unique
 * solution.
 */
class SudokuSolverDeepTest {

    private val classicPuzzle = listOf(
        5, 3, 0, 0, 7, 0, 0, 0, 0,
        6, 0, 0, 1, 9, 5, 0, 0, 0,
        0, 9, 8, 0, 0, 0, 0, 6, 0,
        8, 0, 0, 0, 6, 0, 0, 0, 3,
        4, 0, 0, 8, 0, 3, 0, 0, 1,
        7, 0, 0, 0, 2, 0, 0, 0, 6,
        0, 6, 0, 0, 0, 0, 2, 8, 0,
        0, 0, 0, 4, 1, 9, 0, 0, 5,
        0, 0, 0, 0, 8, 0, 0, 7, 9,
    )
    private val classicSolution = listOf(
        5, 3, 4, 6, 7, 8, 9, 1, 2,
        6, 7, 2, 1, 9, 5, 3, 4, 8,
        1, 9, 8, 3, 4, 2, 5, 6, 7,
        8, 5, 9, 7, 6, 1, 4, 2, 3,
        4, 2, 6, 8, 5, 3, 7, 9, 1,
        7, 1, 3, 9, 2, 4, 8, 5, 6,
        9, 6, 1, 5, 3, 7, 2, 8, 4,
        2, 8, 7, 4, 1, 9, 6, 3, 5,
        3, 4, 5, 2, 8, 6, 1, 7, 9,
    )

    @Test
    fun knownPuzzleSolvesToItsKnownSolution() {
        val solver = SudokuSolver(BoardSize.NINE)
        assertEquals(classicSolution, solver.solve(classicPuzzle))
        assertEquals(1, SudokuSolver(BoardSize.NINE).countSolutions(classicPuzzle))
        // Every given survives into the solution at the same index.
        classicPuzzle.forEachIndexed { index, given ->
            if (given != 0) assertEquals(given, classicSolution[index], "given at $index")
        }
    }

    @Test
    fun removingOneClueFromAFullGridKeepsItUnique() {
        val solver = SudokuSolver(BoardSize.NINE)
        for (index in listOf(0, 40, 80)) {
            val dug = classicSolution.toMutableList().also { it[index] = 0 }
            assertEquals(1, solver.countSolutions(dug), "one blank at $index")
            assertEquals(classicSolution, solver.solve(dug))
        }
    }

    @Test
    fun contradictionsInColumnAndBoxAreRejected() {
        val size = BoardSize.SIX
        val columnClash = blank(size).toMutableList().also { it[0] = 4; it[size.side] = 4 }
        assertNull(SudokuSolver(size).solve(columnClash))
        assertEquals(0, SudokuSolver(size).countSolutions(columnClash))

        // Index 7 is row 1, column 1 on a 6x6: same 2x3 box as index 0, different row and column.
        val boxClash = blank(size).toMutableList().also { it[0] = 2; it[7] = 2 }
        assertNull(SudokuSolver(size).solve(boxClash))
        assertEquals(0, SudokuSolver(size).countSolutions(boxClash))
    }

    @Test
    fun aCellWithNoCandidatesIsUnsolvableWithoutAnyDuplicate() {
        val size = BoardSize.SIX
        // Cell 0 sees 2..6 in its row and 1 in its column: legal givens, no way to fill it.
        val grid = blank(size).toMutableList().also {
            it[1] = 2; it[2] = 3; it[3] = 4; it[4] = 5; it[5] = 6; it[size.side] = 1
        }
        assertNull(SudokuSolver(size).solve(grid))
        assertEquals(0, SudokuSolver(size).countSolutions(grid))
    }

    @Test
    fun solverRecoversAfterAFailedLoad() {
        val size = BoardSize.SIX
        val solver = SudokuSolver(size)
        val bad = blank(size).toMutableList().also { it[0] = 1; it[1] = 1 }
        assertNull(solver.solve(bad))
        // The scratch masks must be reset by the next load, not left poisoned by the clash.
        val solved = assertNotNull(solver.solve(blank(size), Random(3)))
        assertLegal(size, solved)
        assertEquals(1, solver.countSolutions(solved))
    }

    @Test
    fun unseededSolveIsTheDeterministicFirstCompletion() {
        val size = BoardSize.SIX
        val first = SudokuSolver(size).solve(blank(size))
        val second = SudokuSolver(size).solve(blank(size))
        assertEquals(first, second, "without an rng the digit order never changes")
        assertEquals((1..size.side).toList(), first!!.take(size.side), "first row is dealt in order")
    }

    @Test
    fun twelveByTwelveSolutionUsesLettersRangeDigits() {
        val size = BoardSize.TWELVE
        val solved = assertNotNull(SudokuSolver(size).solve(blank(size), Random(9)))
        assertLegal(size, solved)
        assertEquals((1..12).toSet(), solved.take(12).toSet())
        assertTrue(solved.all { it in 1..12 })
    }

    @Test
    fun generatedPuzzlesAreSubGridsOfTheirUniqueSolutionForManySeeds() {
        for (seed in 1L..4L) {
            for (difficulty in Difficulty.entries) {
                val puzzle = SudokuGenerator.generate(BoardSize.NINE, difficulty, Random(seed))
                assertLegal(BoardSize.NINE, puzzle.solution)
                puzzle.givens.forEachIndexed { index, given ->
                    assertTrue(given == 0 || given == puzzle.solution[index], "seed $seed index $index")
                }
                assertEquals(1, SudokuSolver(BoardSize.NINE).countSolutions(puzzle.givens))
                val clues = puzzle.givens.count { it != 0 }
                assertTrue(clues >= difficulty.targetClues(BoardSize.NINE))
                assertTrue(clues < BoardSize.NINE.cellCount, "there must be blanks to play")
            }
        }
    }

    @Test
    fun generatedSixAndTwelveKeepUniquenessAcrossSeeds() {
        for (seed in listOf(5L, 6L)) {
            val six = SudokuGenerator.generate(BoardSize.SIX, Difficulty.HARD, Random(seed))
            assertEquals(1, SudokuSolver(BoardSize.SIX).countSolutions(six.givens))
            assertLegal(BoardSize.SIX, six.solution)
        }
        val twelve = SudokuGenerator.generate(BoardSize.TWELVE, Difficulty.MEDIUM, Random(5))
        assertEquals(1, SudokuSolver(BoardSize.TWELVE).countSolutions(twelve.givens))
        assertLegal(BoardSize.TWELVE, twelve.solution)
        assertNotEquals(
            SudokuGenerator.generate(BoardSize.NINE, Difficulty.MEDIUM, Random(1)).givens,
            SudokuGenerator.generate(BoardSize.NINE, Difficulty.MEDIUM, Random(2)).givens,
        )
    }

    @Test
    fun countWithZeroLimitBehaviourOnCompleteGrid() {
        val size = BoardSize.SIX
        val solved = assertNotNull(SudokuSolver(size).solve(blank(size), Random(7)))
        assertEquals(1, SudokuSolver(size).countSolutions(solved, limit = 100))
        // Asking again on the same instance still sees the same complete grid.
        val solver = SudokuSolver(size)
        assertEquals(solved, solver.solve(solved))
    }
}
