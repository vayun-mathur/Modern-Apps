package com.vayunmathur.games.wordmaker.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class CrosswordStoreDeepTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun fromStringHandlesLeadingTrailingBlankAndMixedSeparators() {
        val b = CrosswordData.fromString("\n\nCAT\n C \n T \n\n")!!
        assertEquals(setOf("CAT", "ACT"), b.solutionWords)
        // Spaces inside rows become empty cells; rows are padded to the widest row.
        val ragged = CrosswordData.fromString("AB\nABCD\nA")!!
        assertTrue(ragged.gridStructure.all { it.length == 4 })
        assertEquals("A...", ragged.gridStructure[2])
        assertTrue("ABCD" in ragged.solutionWords)
        // A board of only separators has no words but still parses its shape.
        val empty = CrosswordData.fromString("...\n...")!!
        assertTrue(empty.solutionWords.isEmpty())
        assertTrue(empty.lettersInChooser.isEmpty())
        assertTrue(empty.winsWith(emptySet()))
        assertTrue(empty.incidentalWords(emptySet()).isEmpty())
        assertNull(empty.getWordAt(0, 0, setOf("CAT")))
    }

    @Test fun getWordAtPrefersVerticalAndIgnoresUnfoundWords() {
        val b = CrosswordData.fromString("CAT\n C \n T ")!!
        // The shared cell (0,1) belongs to CAT across and ACT down.
        assertEquals("ACT", b.getWordAt(0, 1, setOf("CAT", "ACT")))
        assertEquals("CAT", b.getWordAt(0, 0, setOf("CAT", "ACT")))
        assertEquals("CAT", b.getWordAt(0, 2, setOf("CAT")))
        assertNull(b.getWordAt(0, 2, setOf("ACT")))
        assertEquals("ACT", b.getWordAt(2, 1, setOf("ACT")))
        assertNull(b.getWordAt(1, 0, setOf("CAT", "ACT")))
        assertNull(b.getWordAt(9, 9, setOf("CAT", "ACT")))
        // Unknown words in the found set are ignored rather than crashing.
        assertNull(b.getWordAt(0, 0, setOf("ZZZ")))
        assertEquals("CAT", b.getWordAt(0, 0, setOf("CAT", "ZZZ")))
    }

    @Test fun winsWithAndIncidentalTreatUnknownAndPartialWordsCorrectly() {
        val b = CrosswordData.fromString("ABC\nDEF\nGHI")!!
        assertFalse(b.winsWith(setOf("ABC", "DEF")))
        // Unknown found words contribute no cells and do not change the outcome.
        assertFalse(b.winsWith(setOf("ABC", "DEF", "ZZZ")))
        assertTrue(b.winsWith(setOf("ABC", "DEF", "GHI", "ZZZ")))
        assertTrue(b.winsWith(b.solutionWords))
        // Incidental words exclude already-found and unknown entries.
        assertEquals(setOf("ADG", "BEH"), b.incidentalWords(setOf("ABC", "DEF", "GHI", "CFI", "ZZZ"))
            .also { assertFalse("CFI" in it) })
        assertTrue(b.incidentalWords(b.solutionWords).isEmpty())
        // A word with a duplicate occurrence is covered if any occurrence is filled.
        val dup = CrosswordData.fromString("CAT.CAT")!!
        assertEquals(2, dup.letterPositions["CAT"]!!.size)
        assertTrue(dup.winsWith(setOf("CAT")))
    }

    @Test fun chooserLettersTakeTheMaximumMultiplicityAcrossWords() {
        val b = CrosswordData.fromString("EEL\nLEE")!!
        // Both EEL and LEE need two Es and one L; the chooser holds the max, not the sum.
        assertEquals(2, b.lettersInChooser.count { it == 'E' })
        assertEquals(1, b.lettersInChooser.count { it == 'L' })
        assertEquals(listOf('E', 'E', 'L'), b.lettersInChooser)
        val single = CrosswordData.fromString("LEVEL")!!
        assertEquals(2, single.lettersInChooser.count { it == 'E' })
        assertEquals(2, single.lettersInChooser.count { it == 'L' })
        assertEquals(1, single.lettersInChooser.count { it == 'V' })
        // Chooser letters come out sorted regardless of word order.
        assertEquals(b.lettersInChooser.sorted(), b.lettersInChooser)
    }

    @Test fun fromAssetLoadsRealLevelsAndMissingFilesReturnNull() {
        val level1 = CrosswordData.fromAsset(context, "levels/1.txt")
        assertNotNull(level1)
        assertEquals(setOf("CAT", "ACT"), level1.solutionWords)
        assertNull(CrosswordData.fromAsset(context, "levels/999999.txt"))
        assertNull(CrosswordData.fromAsset(context, "does/not/exist.txt"))
        val level2 = CrosswordData.fromAsset(context, "levels/2.txt")
        assertNotNull(level2)
        assertTrue(level2.solutionWords.isNotEmpty())
    }

    @Test fun manuallyBuiltDataBehavesForEmptyAndUnknownWords() {
        val empty = CrosswordData(
            solutionWords = emptySet(),
            lettersInChooser = emptyList(),
            gridStructure = emptyList(),
            letterPositions = emptyMap(),
        )
        assertTrue(empty.winsWith(emptySet()))
        assertNull(empty.getWordAt(0, 0, emptySet()))
        assertTrue(empty.incidentalWords(setOf("CAT")).isEmpty())
        // A solution word with no recorded positions counts as already covered.
        val orphan = CrosswordData(
            solutionWords = setOf("CAT"),
            lettersInChooser = listOf('C', 'A', 'T'),
            gridStructure = listOf("CAT"),
            letterPositions = emptyMap(),
        )
        assertTrue(orphan.winsWith(emptySet()))
        assertTrue(orphan.incidentalWords(emptySet()).isEmpty())
    }

    @Test fun levelStoreEveryFlowRoundTripsIndividually() = runTest {
        val store = LevelDataStore(context)
        store.saveLevel(7)
        assertEquals(7, store.currentLevel.first())
        store.addFoundWord("DEEPONE")
        assertEquals(setOf("DEEPONE"), store.foundWords.first())
        store.setTapToSpell(true)
        assertTrue(store.tapToSpell.first())
        store.setTapToSpell(false)
        assertFalse(store.tapToSpell.first())
        for (spacing in WheelSpacing.entries) {
            store.setWheelSpacing(spacing)
            assertEquals(spacing, store.wheelSpacing.first())
        }
        assertTrue(WheelSpacing.DEFAULT.boxSize < WheelSpacing.WIDEST.boxSize)
        for (mode in GameMode.entries) {
            store.setGameMode(mode)
            assertEquals(mode, store.gameMode.first())
        }
        store.setGameMode(GameMode.CASUAL)
        for (difficulty in Difficulty.entries) {
            store.setDifficulty(difficulty)
            assertEquals(difficulty, store.difficulty.first())
        }
        // Score accumulates and clamps at zero in both directions.
        val start = store.competitiveScore.first()
        assertEquals(start + 30, store.addToCompetitiveScore(30))
        assertEquals(start + 10, store.addToCompetitiveScore(-20))
        assertEquals(0, store.addToCompetitiveScore(-100000))
        assertEquals(0, store.competitiveScore.first())
        // Hint cells de-duplicate and survive as coordinate pairs.
        store.addRevealedHint(3, 4)
        store.addRevealedHint(3, 4)
        store.addRevealedHint(0, 0)
        assertEquals(setOf(3 to 4, 0 to 0), store.revealedHints.first())
        // Bonus words: first sighting counts, repeat does not.
        val total0 = store.totalBonusWords.first()
        assertEquals(total0 + 1, store.addBonusWord("DEEPBONUS1"))
        assertEquals(total0 + 1, store.addBonusWord("DEEPBONUS1"))
        assertEquals(setOf("DEEPBONUS1"), store.bonusWords.first())
        assertEquals(total0 + 1, store.totalBonusWords.first())
    }

    @Test fun levelStoreFlowsEmitEveryWriteToCollectors() = runTest {
        val store = LevelDataStore(context)
        val levels = mutableListOf<Int>()
        val found = mutableListOf<Set<String>>()
        val hints = mutableListOf<Set<Pair<Int, Int>>>()
        val modes = mutableListOf<GameMode>()
        val scores = mutableListOf<Int>()
        val jobs = listOf(
            launch { store.currentLevel.collect { levels += it } },
            launch { store.foundWords.collect { found += it } },
            launch { store.revealedHints.collect { hints += it } },
            launch { store.gameMode.collect { modes += it } },
            launch { store.competitiveScore.collect { scores += it } },
            launch { store.tapToSpell.collect { } },
            launch { store.wheelSpacing.collect { } },
            launch { store.difficulty.collect { } },
            launch { store.bonusWords.collect { } },
            launch { store.totalBonusWords.collect { } },
            launch { store.dailyDay.collect { } },
            launch { store.dailyFoundWords.collect { } },
            launch { store.dailyBonusWords.collect { } },
            launch { store.dailyRevealedHints.collect { } },
        )
        store.saveLevel(11)
        store.addFoundWord("FLOWONE")
        store.addRevealedHint(2, 3)
        store.setGameMode(GameMode.DAILY)
        store.addToCompetitiveScore(5)
        jobs.forEach { it.cancel() }
        assertTrue(11 in levels)
        assertTrue(found.any { "FLOWONE" in it })
        assertTrue(hints.any { (2 to 3) in it })
        assertTrue(GameMode.DAILY in modes)
        assertTrue(scores.any { it >= 5 })
        store.setGameMode(GameMode.CASUAL)
        store.saveLevel(1)
    }

    @Test fun levelStoreDailyFlowsAreIsolatedFromCasualOnes() = runTest {
        val store = LevelDataStore(context)
        store.saveLevel(1)
        store.ensureDailyDay(31000L)
        assertEquals(31000L, store.dailyDay.first())
        store.addDailyFoundWord("DAYWORD")
        store.addDailyRevealedHint(1, 1)
        store.addDailyRevealedHint(1, 1)
        assertEquals(setOf("DAYWORD"), store.dailyFoundWords.first())
        assertEquals(setOf(1 to 1), store.dailyRevealedHints.first())
        // Casual sets are untouched by daily writes.
        assertFalse("DAYWORD" in store.foundWords.first())
        val total0 = store.totalBonusWords.first()
        assertEquals(total0 + 1, store.addDailyBonusWord("DAYBONUS"))
        assertEquals(total0 + 1, store.addDailyBonusWord("DAYBONUS"))
        assertEquals(setOf("DAYBONUS"), store.dailyBonusWords.first())
        assertTrue(store.bonusWords.first().isEmpty() || "DAYBONUS" !in store.bonusWords.first())
        // Same-day ensure is a no-op; a new day wipes only the daily sets.
        store.ensureDailyDay(31000L)
        assertEquals(setOf("DAYWORD"), store.dailyFoundWords.first())
        store.ensureDailyDay(31001L)
        assertTrue(store.dailyFoundWords.first().isEmpty())
        assertTrue(store.dailyBonusWords.first().isEmpty())
        assertTrue(store.dailyRevealedHints.first().isEmpty())
        assertEquals(31001L, store.dailyDay.first())
    }
}
