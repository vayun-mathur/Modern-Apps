package com.vayunmathur.games.nonogram.domain

import com.vayunmathur.games.nonogram.data.CellMark
import com.vayunmathur.games.nonogram.data.GameMode
import com.vayunmathur.games.nonogram.data.NonogramGameState
import com.vayunmathur.games.nonogram.data.STARTING_HEARTS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NonogramCluesTest {

    // # filled, . blank, X player's cross, R revealed blank. X/R/. all terminate
    // a run but none of them is a fill.
    private fun marks(line: String): List<CellMark> = line.map {
        when (it) {
            '#' -> CellMark.FILLED
            'X' -> CellMark.CROSSED
            'R' -> CellMark.REVEALED
            else -> CellMark.BLANK
        }
    }

    private fun grey(clues: List<Int>, line: String): ClueGreying =
        NonogramClues.greying(clues, marks(line))

    // 3x3 plus sign: picture cells 1, 3, 4, 5, 7; rows/cols are [1], [3], [1].
    private val plus = puzzle(
        """
        .#.
        ###
        .#.
        """
    )

    private fun state(
        filled: Set<Int> = emptySet(),
        crossed: Set<Int> = emptySet(),
        revealed: Set<Int> = emptySet(),
        auto: Set<Int> = emptySet(),
    ) = NonogramGameState(
        puzzle = plus,
        filled = filled,
        crossed = crossed,
        revealedBlanks = revealed,
        hearts = STARTING_HEARTS,
        mode = GameMode.CASUAL,
        level = 1,
        autoCrossed = auto,
    )

    // ---- Greying: prefix ----

    @Test fun emptyLineGreysNothing() {
        val g = grey(listOf(2, 1), ".....")
        assertEquals(0, g.prefixMatched); assertEquals(0, g.suffixMatched)
        assertEquals(listOf(false, false), g.greyed)
    }

    @Test fun exactPrefixRunGreysFirstClueOnly() {
        // Two fills closed by a blank match "2"; the trailing "1" has no run yet.
        val g = grey(listOf(2, 1), "##...")
        assertEquals(1, g.prefixMatched); assertEquals(listOf(true, false), g.greyed)
    }

    @Test fun prefixSkipsLeadingNonFillsAndAnyNonFillTerminates() {
        // Cross, revealed and blank separators all leave the run closed.
        assertEquals(listOf(true, false), grey(listOf(2, 1), "X##..").greyed)
        assertEquals(listOf(true, false), grey(listOf(2, 1), ".##R.").greyed)
        assertTrue(grey(listOf(2), "##").isGreyed(0), "the line edge terminates a run")
    }

    @Test fun incompleteRunGreysNothing() {
        // The lone run of 1 matches neither the leading 2 nor the trailing 3.
        assertEquals(listOf(false, false), grey(listOf(2, 3), "#....").greyed)
    }

    @Test fun oversizedRunIsNotAMatchedCluePlusExtra() {
        // One run of three is not a "2" with a fill left over.
        val g = grey(listOf(2, 1), "###..")
        assertEquals(0, g.prefixMatched); assertEquals(listOf(false, false), g.greyed)
    }

    @Test fun wrongFirstRunLengthBlocksTheWholePrefix() {
        // First run is 1, not 2: nothing from the start greys, even though a
        // later run happens to be 2 long.
        assertEquals(listOf(false, false), grey(listOf(2, 1), "#.##.").greyed)
    }

    @Test fun mergedRunsDoNotMatchSeparateClues() {
        // "###" is a single run of 3, never a 2 followed by a 1.
        assertEquals(listOf(false, false), grey(listOf(2, 1), "###").greyed)
    }

    // ---- Greying: suffix ----

    @Test fun suffixOnlyMatchGreysLastClue() {
        // The first run (1) does not match the leading 2, so only the end greys.
        val g = grey(listOf(2, 1), "#...#")
        assertEquals(0, g.prefixMatched); assertEquals(1, g.suffixMatched)
        assertEquals(listOf(false, true), g.greyed)
    }

    @Test fun suffixSkipsTrailingNonFills() {
        assertEquals(listOf(false, true), grey(listOf(2, 1), "#..#X").greyed)
    }

    @Test fun oversizedSuffixRunGreysNothingFromTheEnd() {
        // The final run is 3, not the trailing clue 1.
        val g = grey(listOf(2, 1), "...###")
        assertEquals(0, g.suffixMatched); assertEquals(listOf(false, false), g.greyed)
    }

    @Test fun incompleteSuffixRunGreysNothing() {
        // The lone run of 1 matches neither the leading 3 nor the trailing 2.
        assertEquals(listOf(false, false), grey(listOf(3, 2), "....#").greyed)
    }

    // ---- Greying: both ends, middle, full, zero clue ----

    @Test fun fullyMatchedLineGreysEveryClue() {
        val g = grey(listOf(2, 1), "##.#")
        assertEquals(2, g.prefixMatched); assertEquals(listOf(true, true), g.greyed)
    }

    @Test fun bothEndsCanGreyWithAnUnmatchedMiddle() {
        // Runs are 2, (nothing for the 1), 2: first and last grey, middle does not.
        val g = grey(listOf(2, 1, 2), "##...##")
        assertEquals(1, g.prefixMatched); assertEquals(1, g.suffixMatched)
        assertEquals(listOf(true, false, true), g.greyed, "no speculative middle matching")
    }

    @Test fun bothEndsMeetingInTheMiddleGreyEverything() {
        // Runs 2 then 1 exactly cover [2, 1]; prefix and suffix meet over it.
        val g = grey(listOf(2, 1), "##X#")
        assertEquals(listOf(true, true), g.greyed)
        assertTrue(g.prefixMatched + g.suffixMatched >= 2)
    }

    @Test fun middleRunAloneNeverGreysAMiddleClue() {
        // A lone central run of 1 matches the middle clue's length but is
        // reachable from neither end, so nothing greys.
        val g = grey(listOf(2, 1, 2), "...#...")
        assertEquals(listOf(false, false, false), g.greyed)
    }

    @Test fun zeroAndEmptyClues() {
        assertEquals(emptyList(), grey(emptyList(), "...").greyed)
        assertEquals(emptyList(), grey(emptyList(), "#..").greyed)
        assertEquals(listOf(true), grey(listOf(0), "...").greyed, "no fills: the 0 is accounted for")
        assertEquals(listOf(true), grey(listOf(0), "XXR").greyed)
        assertEquals(listOf(false), grey(listOf(0), "#..").greyed)
    }

    @Test fun playerCrossesDoNotCreateOrExtendRuns() {
        // Crosses around a single fill do not make it a run of 2.
        assertEquals(listOf(false), grey(listOf(2), "X#X").greyed)
        // ...and a cross is a valid separator between two matched runs.
        assertEquals(listOf(true, true), grey(listOf(1, 1), "#X#").greyed)
    }

    @Test fun rowAndColumnGreyingReadTheBoard() {
        val s = state(filled = setOf(3, 4, 5, 1))
        assertEquals(listOf(true), NonogramClues.rowGreying(s, 1).greyed, "row of 3 is filled")
        // Row 0's clue is [1] and its only fill is placed, so it greys too.
        assertEquals(listOf(true), NonogramClues.rowGreying(s, 0).greyed)
        assertEquals(listOf(true), NonogramClues.colGreying(s, 0).greyed, "column 0's only fill is placed")
        assertEquals(listOf(false), NonogramClues.colGreying(s, 1).greyed, "column of 3 has 2 of 3 fills")
    }

    // ---- Auto-crosses ----

    @Test fun noAutoCrossesWhileEveryLineIsIncomplete() {
        assertEquals(emptySet(), NonogramClues.withAutoCrosses(state(filled = setOf(4))).autoCrossed)
        assertEquals(emptySet(), NonogramClues.withAutoCrosses(state()).autoCrossed)
    }

    @Test fun completingALineAutoCrossesItsRemainingBlankCells() {
        // Filling the middle row also completes columns 0 and 2 (clue [1]
        // each), so all four corners auto-cross; column 1 ([3]) is incomplete.
        val s = NonogramClues.withAutoCrosses(state(filled = setOf(3, 4, 5)))
        assertEquals(setOf(0, 2, 6, 8), s.autoCrossed)
        assertEquals(emptySet(), s.crossed, "auto crosses are not the player's crosses")
        for (i in setOf(0, 2, 6, 8)) {
            assertEquals(CellMark.CROSSED, s.markAt(i))
            assertTrue(s.isAutoCrossed(i)); assertFalse(s.isManuallyCrossed(i))
        }
        assertSame(s, NonogramClues.withAutoCrosses(s), "derivation is idempotent")
    }

    @Test fun zeroClueLinesAutoCrossFromTheStart() {
        val striped = puzzle(
            """
            ##.
            ...
            ##.
            """
        )
        val s = NonogramClues.withAutoCrosses(
            NonogramGameState(striped, emptySet(), emptySet(), emptySet(), STARTING_HEARTS, GameMode.CASUAL, 1)
        )
        // Middle row (clue []) and right column (clue []) need no fills.
        assertEquals(setOf(2, 3, 4, 5, 8), s.autoCrossed)
    }

    @Test fun manualAndRevealedCellsAreNeverTurnedIntoAutoCrosses() {
        val s = NonogramClues.withAutoCrosses(
            state(filled = setOf(3, 4, 5), crossed = setOf(0), revealed = setOf(2))
        )
        assertEquals(setOf(6, 8), s.autoCrossed)
        assertEquals(setOf(0), s.crossed, "the player's cross stays the player's")
        assertEquals(CellMark.REVEALED, s.markAt(2))
    }

    @Test fun removingAJustifyingFillRemovesItsAutoCrossesOnly() {
        val derived = NonogramClues.withAutoCrosses(
            state(filled = setOf(3, 4, 5), crossed = setOf(0))
        )
        assertEquals(setOf(2, 6, 8), derived.autoCrossed)
        // Take the left middle fill back: the row and column 0 are incomplete
        // again, so 6 (justified only by them) drops; 2 and 8 stay, because
        // column 2 is still complete. The manual cross at 0 never drops.
        val broken = NonogramClues.withAutoCrosses(derived.copy(filled = derived.filled - 3))
        assertEquals(setOf(2, 8), broken.autoCrossed)
        assertEquals(setOf(0), broken.crossed)
        assertEquals(CellMark.CROSSED, broken.markAt(0))
        assertEquals(CellMark.BLANK, broken.markAt(6))
    }

    @Test fun undoThenRederiveDropsAutoCrosses() {
        val before = state()
        val played = NonogramClues.withAutoCrosses(NonogramMoves.guess(before, 3))
        val swept = NonogramClues.withAutoCrosses(
            NonogramMoves.guess(NonogramMoves.guess(played, 4), 5)
        )
        assertEquals(setOf(0, 2, 6, 8), swept.autoCrossed)
        val undone = NonogramClues.withAutoCrosses(NonogramMoves.undo(swept, before))
        assertEquals(emptySet(), undone.filled)
        assertEquals(emptySet(), undone.autoCrossed)
    }

    @Test fun autoCrossesDoNotWinOrLoseTheGame() {
        val blanks = plus.solution.indices.filterNot { plus.solution[it] }.toSet()
        val won = NonogramClues.withAutoCrosses(state(filled = plus.solution.indices.filter { plus.solution[it] }.toSet()))
        assertTrue(won.isWon); assertEquals(blanks, won.autoCrossed)
        val notWon = NonogramClues.withAutoCrosses(state(filled = setOf(3, 4, 5)))
        assertFalse(notWon.isWon, "auto crosses never substitute for a missing fill")
        assertFalse(notWon.isFailed)
    }

    @Test fun lineCompletionIsExactlyTheClueSum() {
        assertTrue(NonogramClues.isLineComplete(listOf(2, 1), 3))
        assertFalse(NonogramClues.isLineComplete(listOf(2, 1), 2))
        assertFalse(NonogramClues.isLineComplete(listOf(2, 1), 4))
        assertTrue(NonogramClues.isLineComplete(emptyList(), 0))
        assertTrue(NonogramClues.isLineComplete(listOf(0), 0))
    }
}
