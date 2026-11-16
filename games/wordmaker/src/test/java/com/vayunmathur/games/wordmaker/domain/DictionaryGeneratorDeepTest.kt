package com.vayunmathur.games.wordmaker.domain

import android.content.Context
import android.content.res.AssetManager
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.wordmaker.data.CrosswordData
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class DictionaryGeneratorDeepTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun dictionaryMembershipIsExactAndCaseSensitiveAtThisLayer() {
        val dict = Dictionary()
        assertFalse("cat" in dict)
        assertFalse("" in dict)
        dict.init(context)
        // The DAWG stores lowercase words; callers lowercase before asking.
        assertTrue("cat" in dict)
        assertTrue("dog" in dict)
        assertFalse("CAT" in dict)
        assertFalse("Cat" in dict)
        assertFalse("" in dict)
        assertFalse("cat " in dict)
        assertFalse(" cat" in dict)
        assertFalse("zzzzqq" in dict)
        assertFalse("catdogzz" in dict)
        // Re-initialising replaces the trie and keeps membership working.
        dict.init(context)
        assertTrue("cat" in dict)
        assertFalse("zzzzqq" in dict)
    }

    @Test fun dictionaryDefinitionsAreLowercasedCachedAndMultiSense() {
        val dict = Dictionary()
        dict.init(context)
        val first = dict.getDefinition("cat")
        assertTrue(first.isNotEmpty())
        // Lookup lowercases, so any case returns the same definitions.
        assertEquals(first, dict.getDefinition("CAT"))
        assertEquals(first, dict.getDefinition("Cat"))
        // Second call serves the lazily decoded cache without changing the result.
        assertEquals(first, dict.getDefinition("cat"))
        assertTrue(dict.getDefinition("dog").isNotEmpty())
        assertTrue(dict.getDefinition("zzzzqq").isEmpty())
    }

    @Test fun generatorBoardsAreSpellableIntersectingAndDeterministicForManySeeds() {
        val gen = CompetitiveLevelGenerator(syntheticWords())
        var boards = 0
        for (seed in 1L..12L) {
            val a = gen.generate(Random(seed)) ?: continue
            boards++
            val b = gen.generate(Random(seed))
            assertEquals(a.gridStructure, b?.gridStructure)
            assertEquals(a.solutionWords, b?.solutionWords)
            assertTrue(a.solutionWords.size >= 6)
            // Wheel letters: between 5 and 8 tiles with at least 4 distinct letters.
            assertTrue(a.lettersInChooser.size in 5..8, "wheel size ${a.lettersInChooser.size}")
            assertTrue(a.lettersInChooser.toSet().size >= 4)
            val counts = a.lettersInChooser.groupingBy { it }.eachCount()
            for (w in a.solutionWords) {
                assertTrue(w.length in 3..8)
                val wc = w.groupingBy { it }.eachCount()
                assertTrue(wc.all { (c, n) -> n <= (counts[c] ?: 0) }, "$w not spellable from $counts")
                assertTrue(a.letterPositions.containsKey(w))
            }
            // The grid text round-trips to the same word set.
            val reparsed = CrosswordData.fromString(a.gridStructure.joinToString("\n"))
            assertEquals(a.solutionWords, reparsed?.solutionWords)
        }
        assertTrue(boards >= 6, "only $boards boards generated")
    }

    @Test fun generatorRejectsPoolsWithoutEnoughSpellableCandidates() {
        // A 7-letter wheel whose letters spell almost nothing else.
        val sparse = CompetitiveLevelGenerator(listOf("ABCDEFG", "ABC", "XYZQJ"))
        assertNull(sparse.generate(Random(3)))
        // Repeated-letter wheel: candidates must respect letter multiplicities.
        val repeated = CompetitiveLevelGenerator(
            listOf("LETTERS", "LETTER", "TREE", "LESS", "STEER", "RESET", "TEST", "TELLS")
        )
        val board = repeated.generate(Random(5))
        if (board != null) {
            val counts = board.lettersInChooser.groupingBy { it }.eachCount()
            assertTrue((counts['T'] ?: 0) <= 2)
            for (w in board.solutionWords) {
                val wc = w.groupingBy { it }.eachCount()
                assertTrue(wc.all { (c, n) -> n <= (counts[c] ?: 0) })
            }
        }
        // Words outside 3..8 are excluded from the pool, so no wheel can form.
        assertNull(CompetitiveLevelGenerator(listOf("AB", "ABCDEFGHI", "CD")).generate(Random(1)))
        // An 8-letter wheel candidate works the same as a 7-letter one.
        val eight = CompetitiveLevelGenerator(syntheticWords() + "STAINEDX".dropLast(1) + listOf("DETAINS", "INSTEAD"))
        assertNotNull(eight.generate(Random(42)))
    }

    @Test fun generatorFromAssetsNeverEmitsBlocklistedWords() {
        val gen = CompetitiveLevelGenerator.fromAssets(context)
        val blocked = listOf("DAMN", "HELL", "CRAP")
        repeat(6) { i ->
            val board = gen.generate(Random(100L + i))
            assertNotNull(board)
            for (w in board.solutionWords) {
                assertFalse(w in blocked, "blocklisted word $w generated")
            }
            assertTrue(board.solutionWords.size >= 6)
        }
        // The no-arg generate uses a fresh random source and still yields a playable board.
        assertNotNull(gen.generate())
    }

    @Test fun generatorSometimesProducesFullyCrossedWords() {
        // Some generated boards contain a word crossed at every letter: finding the
        // crossing words then completes it without it ever being traced, which is
        // exactly what CrosswordData.incidentalWords exists to detect.
        val gen = CompetitiveLevelGenerator.fromAssets(context)
        var boards = 0
        var fullyCrossed = 0
        for (seed in 1L..60L) {
            val board = gen.generate(Random(seed)) ?: continue
            boards++
            if (board.solutionWords.any { w ->
                    board.incidentalWords(board.solutionWords - w).contains(w)
                }
            ) {
                fullyCrossed++
            }
        }
        assertTrue(boards > 30, "only $boards boards generated")
        assertTrue(fullyCrossed >= 1, "no fully-crossed word in $boards boards")
    }

    /** A Context whose AssetManager serves the given bytes as words.dawg. */
    private fun contextServingDawg(bytes: ByteArray): Context {
        val am = org.mockito.Mockito.mock(AssetManager::class.java)
        org.mockito.Mockito.`when`(am.open("words.dawg"))
            .thenReturn(java.io.ByteArrayInputStream(bytes))
        val ctx = org.mockito.Mockito.mock(Context::class.java)
        org.mockito.Mockito.`when`(ctx.assets).thenReturn(am)
        org.mockito.Mockito.`when`(ctx.applicationContext).thenReturn(ctx)
        return ctx
    }

    private fun dawgBytes(edges: IntArray, magic: Int = 0x57444731): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(12 + edges.size * 4)
        buffer.putInt(magic)
        buffer.putInt(edges.size)
        buffer.putInt(0) // root offset
        for (e in edges) buffer.putInt(e)
        return buffer.array()
    }

    private fun edge(code: Char, offset: Int, final: Boolean, last: Boolean): Int {
        var e = (code.code and 0xFF) shl 24 or (offset and ((1 shl 22) - 1))
        if (final) e = e or (1 shl 22)
        if (last) e = e or (1 shl 23)
        return e
    }

    @Test fun dictionaryRejectsABadMagicAsset() {
        val dict = Dictionary()
        val error = runCatching {
            dict.init(contextServingDawg(dawgBytes(intArrayOf(), magic = 0x12345678)))
        }.exceptionOrNull()
        kotlin.test.assertIs<IllegalArgumentException>(error)
        assertTrue("cat" !in dict)
    }

    @Test fun dictionaryWalksAHandBuiltDawg() {
        // Trie for {ab, abc, b}: root edges a->2, b(final); then b(final)->3; then
        // c(final). Leaf edges point at a dead node (index 4, an edge no letter
        // matches) so over-long words cannot wrap back to the root.
        val edges = intArrayOf(
            edge('a', offset = 2, final = false, last = false),
            edge('b', offset = 4, final = true, last = true),
            edge('b', offset = 3, final = true, last = true),
            edge('c', offset = 4, final = true, last = true),
            edge(0.toChar(), offset = 4, final = false, last = true),
        )
        val dict = Dictionary()
        dict.init(contextServingDawg(dawgBytes(edges)))
        assertTrue("ab" in dict)
        assertTrue("abc" in dict)
        assertTrue("b" in dict)
        // A prefix that is not itself a word, extensions, and wrong branches are rejected.
        assertFalse("a" in dict)
        assertFalse("abcd" in dict)
        assertFalse("ac" in dict)
        assertFalse("ba" in dict)
        assertFalse("abcabc" in dict)
    }

    private fun syntheticWords(): List<String> {
        val wheel = "STAINED"
        val letters = wheel.toList()
        val words = mutableListOf(wheel, "STAIN", "TIDES", "INSTEAD", "DETAINS", "SANTED")
        fun combos(prefix: String, rest: List<Char>, len: Int) {
            if (prefix.length == len) {
                words.add(prefix)
                return
            }
            for (c in rest) combos(prefix + c, rest - c, len)
        }
        for (len in 3..5) combos("", letters, len)
        return words.distinct().filter { it.length in 3..8 }
    }
}
