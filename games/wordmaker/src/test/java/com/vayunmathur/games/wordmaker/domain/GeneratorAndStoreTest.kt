package com.vayunmathur.games.wordmaker.domain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.wordmaker.data.LevelDataStore
import com.vayunmathur.games.wordmaker.data.Difficulty
import com.vayunmathur.games.wordmaker.data.GameMode
import com.vayunmathur.games.wordmaker.data.WheelSpacing
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class GeneratorAndStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // A wheel word plus every 3+ letter combination of its distinct letters, so the
    // generator has plenty of spellable candidates that share letters (intersections).
    private fun syntheticWords(): List<String> {
        val wheel = "STAINED" // 7 distinct letters
        val letters = wheel.toList()
        val words = mutableListOf(wheel, "STAIN", "TIDES", "INSTEAD")
        // all ordered triples/permutations-ish: use combinations of letters in wheel order
        fun combos(prefix: String, rest: List<Char>, len: Int) {
            if (prefix.length == len) { words.add(prefix); return }
            for (c in rest) combos(prefix + c, rest - c, len)
        }
        for (len in 3..5) combos("", letters, len)
        return words.distinct()
    }

    @Test fun generatorProducesPlayableBoardDeterministically() {
        val gen = CompetitiveLevelGenerator(syntheticWords())
        val a = gen.generate(Random(42))
        assertNotNull(a)
        assertTrue(a.solutionWords.size >= 4)
        assertTrue(a.lettersInChooser.size in 5..8)
        // every solution word is spellable from the chooser letters
        val counts = a.lettersInChooser.groupingBy { it }.eachCount()
        for (w in a.solutionWords) {
            val wc = w.groupingBy { it }.eachCount()
            assertTrue(wc.all { (c, n) -> n <= (counts[c] ?: 0) }, "word $w not spellable from $counts")
        }
        val b = gen.generate(Random(42))
        assertEquals(a.solutionWords, b?.solutionWords)
        assertEquals(a.gridStructure, b?.gridStructure)
    }
    @Test fun generatorWithNoWheelCandidatesReturnsNull() {
        assertNull(CompetitiveLevelGenerator(listOf("CAT", "DOG", "PIG")).generate(Random(1)))
        assertNull(CompetitiveLevelGenerator(emptyList()).generate(Random(1)))
        // words too long are filtered out of the pool entirely
        assertNull(CompetitiveLevelGenerator(listOf("ABCDEFGHIJ", "KLMNOPQRST")).generate(Random(1)))
    }
    @Test fun generatorFromAssetsLoadsWordList() {
        val gen = CompetitiveLevelGenerator.fromAssets(context)
        val board = gen.generate(Random(7))
        assertNotNull(board)
        assertTrue(board.solutionWords.isNotEmpty())
    }
    @Test fun dictionaryContainsAndDefines() {
        val dict = Dictionary()
        // before init nothing is contained
        assertTrue("cat" !in dict)
        dict.init(context)
        assertTrue("cat" in dict)
        assertTrue("dog" in dict)
        assertTrue("zzzzqq" !in dict)
        assertTrue("" !in dict)
        // case is the caller's concern (the ViewModel lowercases first)
        val defs = dict.getDefinition("cat")
        assertTrue(defs.isNotEmpty())
        assertTrue(dict.getDefinition("CAT").isNotEmpty())
        assertTrue(dict.getDefinition("zzzzqq").isEmpty())
    }
    @Test fun levelDataStoreCasualProgress() = runTest {
        val store = LevelDataStore(context)
        store.saveLevel(3)
        assertEquals(3, store.currentLevel.first())
        assertTrue(store.foundWords.first().isEmpty())
        store.addFoundWord("CAT"); store.addFoundWord("ACT")
        assertEquals(setOf("CAT", "ACT"), store.foundWords.first())
        // the store is app-scoped and shared with other tests: assert relative totals
        val beforeTotal = store.totalBonusWords.first()
        assertEquals(beforeTotal + 1, store.addBonusWord("ZQXONE"))
        assertEquals(beforeTotal + 1, store.addBonusWord("ZQXONE")) // duplicate does not recount
        assertEquals(beforeTotal + 2, store.addBonusWord("ZQXTWO"))
        assertEquals(beforeTotal + 2, store.totalBonusWords.first())
        store.addRevealedHint(1, 2); store.addRevealedHint(1, 2)
        assertEquals(setOf(1 to 2), store.revealedHints.first())
        // saving a level clears per-level progress
        store.saveLevel(4)
        assertEquals(4, store.currentLevel.first())
        assertTrue(store.foundWords.first().isEmpty())
        assertTrue(store.bonusWords.first().isEmpty())
        assertTrue(store.revealedHints.first().isEmpty())
    }
    @Test fun levelDataStoreSettingsAndScoreClamp() = runTest {
        val store = LevelDataStore(context)
        store.setTapToSpell(true); assertTrue(store.tapToSpell.first())
        store.setTapToSpell(false); assertTrue(!store.tapToSpell.first())
        store.setWheelSpacing(WheelSpacing.WIDER); assertEquals(WheelSpacing.WIDER, store.wheelSpacing.first())
        store.setGameMode(GameMode.DAILY); assertEquals(GameMode.DAILY, store.gameMode.first())
        store.setGameMode(GameMode.CASUAL); assertEquals(GameMode.CASUAL, store.gameMode.first())
        store.setDifficulty(Difficulty.HARD); assertEquals(Difficulty.HARD, store.difficulty.first())
        val s1 = store.addToCompetitiveScore(50)
        assertTrue(s1 >= 50)
        assertEquals(0, store.addToCompetitiveScore(-100000)) // clamped at zero
        assertEquals(0, store.competitiveScore.first())
    }
    @Test fun levelDataStoreDailyRollover() = runTest {
        val store = LevelDataStore(context)
        assertEquals(LevelDataStore.NO_DAY, LevelDataStore.NO_DAY)
        store.ensureDailyDay(20000L)
        assertEquals(20000L, store.dailyDay.first())
        store.addDailyFoundWord("CAT")
        store.addDailyRevealedHint(0, 1)
        val before = store.totalBonusWords.first()
        assertEquals(before + 1, store.addDailyBonusWord("ZQXDAY"))
        assertEquals(before + 1, store.addDailyBonusWord("ZQXDAY")) // duplicate: no recount
        assertEquals(setOf("CAT"), store.dailyFoundWords.first())
        // same day again is a no-op
        store.ensureDailyDay(20000L)
        assertEquals(setOf("CAT"), store.dailyFoundWords.first())
        // next day discards progress
        store.ensureDailyDay(20001L)
        assertEquals(20001L, store.dailyDay.first())
        assertTrue(store.dailyFoundWords.first().isEmpty())
        assertTrue(store.dailyBonusWords.first().isEmpty())
        assertTrue(store.dailyRevealedHints.first().isEmpty())
    }
}
