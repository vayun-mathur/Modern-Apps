package com.vayunmathur.games.wordmaker.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrosswordDataTest {

    private val board = CrosswordData.fromString("CAT\n C \n T ")!!

    @Test fun parsesHorizontalAndVerticalWords() {
        assertEquals(setOf("CAT", "ACT"), board.solutionWords)
        assertEquals(listOf('A', 'C', 'T'), board.lettersInChooser)
        assertEquals(3, board.gridStructure.size)
        assertTrue(board.letterPositions.containsKey("CAT"))
        assertTrue(board.letterPositions.containsKey("ACT"))
    }
    @Test fun crossingBoardWordsAndPositions() {
        val b = CrosswordData.fromString("DOG\nO..\nGOD")!!
        // DOG appears both across the top and down column 0; GOD across the bottom.
        assertEquals(setOf("DOG", "GOD"), b.solutionWords)
        assertEquals(2, b.letterPositions["DOG"]!!.size)
        assertEquals(listOf(0 to 0, 0 to 1, 0 to 2), b.letterPositions["DOG"]!!.first())
    }
    @Test fun singleLettersAreNotWords() {
        val b = CrosswordData.fromString("A.B\n...\nC.D")!!
        assertTrue(b.solutionWords.isEmpty())
        assertTrue(b.lettersInChooser.isEmpty())
    }
    @Test fun blankAndEmptyReturnNull() {
        assertNull(CrosswordData.fromString(""))
        assertNull(CrosswordData.fromString("   \n  \n"))
    }
    @Test fun blankRowsBecomeDotsAndRaggedRowsPadded() {
        val b = CrosswordData.fromString("AB\n\nABCD")!!
        assertEquals(3, b.gridStructure.size)
        assertTrue(b.gridStructure.all { it.length == 4 })
        assertEquals("....", b.gridStructure[1])
        assertTrue(b.solutionWords.contains("ABCD"))
    }
    @Test fun duplicateWordOccurrencesCollected() {
        val b = CrosswordData.fromString("CAT.CAT")!!
        assertEquals(2, b.letterPositions["CAT"]!!.size)
    }
    @Test fun getWordAtFindsFoundWordsOnly() {
        assertNull(board.getWordAt(0, 0, emptySet()))
        assertEquals("CAT", board.getWordAt(0, 0, setOf("CAT", "ACT")))
        // a cell shared by both words resolves to the vertical one first
        assertEquals("ACT", board.getWordAt(0, 1, setOf("CAT", "ACT")))
        assertEquals("ACT", board.getWordAt(1, 1, setOf("ACT")))
        assertNull(board.getWordAt(2, 2, setOf("CAT", "ACT")))
    }
    @Test fun winsWithRequiresAllCovered() {
        assertFalse(board.winsWith(emptySet()))
        assertFalse(board.winsWith(setOf("CAT")))
        assertTrue(board.winsWith(setOf("CAT", "ACT")))
    }
    @Test fun winsWithCountsIncidentalCoverage() {
        // Finding every horizontal word fills the whole 3x3 grid, so the vertical
        // words are covered without being traced.
        val b = CrosswordData.fromString("ABC\nDEF\nGHI")!!
        assertTrue(b.winsWith(setOf("ABC", "DEF", "GHI")))
        assertFalse(b.winsWith(setOf("ABC", "DEF")))
    }
    @Test fun incidentalWordsDetectsCompletedByCrossings() {
        val b = CrosswordData.fromString("ABC\nDEF\nGHI")!!
        assertEquals(setOf("ADG", "BEH", "CFI"), b.incidentalWords(setOf("ABC", "DEF", "GHI")))
        assertTrue(b.incidentalWords(setOf("ABC", "DEF", "GHI", "ADG", "BEH", "CFI")).isEmpty())
        assertTrue(board.incidentalWords(emptySet()).isEmpty())
        assertTrue(board.incidentalWords(setOf("CAT")).isEmpty())
    }
    @Test fun chooserLettersUseMaxCountPerWord() {
        val b = CrosswordData.fromString("EEL\n...")!!.let { it }
        // EEL needs two Es; a second word with one E must not add more
        val b2 = CrosswordData.fromString("EEL.E")!!
        assertEquals(2, b2.lettersInChooser.count { it == 'E' })
        assertNotNull(b)
    }
    @Test fun difficultyAndModeValues() {
        assertEquals(120, Difficulty.EASY.timeLimitSeconds)
        assertEquals(25, Difficulty.MEDIUM.winDelta)
        assertEquals(30, Difficulty.HARD.lossDelta)
        assertEquals(listOf(GameMode.CASUAL, GameMode.COMPETITIVE, GameMode.DAILY), GameMode.entries.toList())
    }
    @Test fun wheelSpacingBoxSizesGrow() {
        val sizes = WheelSpacing.entries.map { it.boxSize }
        assertEquals(sizes.sorted(), sizes)
        assertTrue(WheelSpacing.WIDEST.ringRadius > WheelSpacing.DEFAULT.ringRadius)
        assertEquals(WheelSpacing.DEFAULT.ringRadius * 2 + androidx.compose.ui.unit.Dp(80f), WheelSpacing.DEFAULT.boxSize)
    }
}
