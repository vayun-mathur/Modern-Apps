package com.vayunmathur.code.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unit tests for the pure fuzzy matcher used by quick-open and the command palette. */
class FuzzyMatchTest {

    @Test
    fun emptyQueryMatchesEverythingNeutrally() {
        assertEquals(0, fuzzyScore("", "anything"))
    }

    @Test
    fun nonSubsequenceReturnsNull() {
        assertNull(fuzzyScore("xyz", "CodeEditor"))
        assertNull(fuzzyScore("edroc", "code"))
        // Query longer than candidate can never be a subsequence.
        assertNull(fuzzyScore("codes", "code"))
    }

    @Test
    fun subsequenceMatches() {
        assertNotNull(fuzzyScore("cdr", "CodeEditor"))
        assertNotNull(fuzzyScore("code", "CodeEditor"))
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertNotNull(fuzzyScore("CODE", "code.kt"))
        assertNotNull(fuzzyScore("code", "CODE.KT"))
    }

    @Test
    fun consecutivePrefixBeatsScatteredMatch() {
        val prefix = fuzzyScore("code", "code.kt")!!
        val scattered = fuzzyScore("code", "c_o_d_e.kt")!!
        assertTrue(prefix > scattered, "prefix=$prefix scattered=$scattered")
    }

    @Test
    fun camelCaseHumpsScoreHigherThanBuriedLetters() {
        val humps = fuzzyScore("ce", "CodeEditor")!!
        val buried = fuzzyScore("ce", "coreachievement")!!
        assertTrue(humps > buried, "humps=$humps buried=$buried")
    }

    @Test
    fun filenameBoundaryPreferred() {
        // Matching the file name segment should beat matching across the directory prefix.
        val onName = fuzzyScore("model", "src/util/DiffModel.kt")!!
        val onlyExisting = fuzzyScore("model", "src/util/DiffModel.kt")
        assertNotNull(onlyExisting)
        assertTrue(onName > 0)
    }

    @Test
    fun rankOrdersBestFirst() {
        val files = listOf("app/EditorViewModel.kt", "ed.txt", "code/Editor.kt", "readme.md")
        val ranked = fuzzyRank("edi", files) { it }
        assertEquals("code/Editor.kt", ranked.first())
        assertTrue("readme.md" !in ranked) // no subsequence -> filtered out
    }

    @Test
    fun blankQueryReturnsListUnchanged() {
        val files = listOf("b", "a", "c")
        assertEquals(files, fuzzyRank("  ", files) { it })
    }

    @Test
    fun rankIsStableOnTies() {
        val items = listOf("ab1", "ab2", "ab3")
        assertEquals(items, fuzzyRank("ab", items) { it })
    }

    @Test
    fun shorterCandidateWinsScoreTies() {
        val ranked = fuzzyRank("abc", listOf("xabcxx", "abc")) { it }
        assertEquals(listOf("abc", "xabcxx"), ranked)
    }

    @Test
    fun emptyCandidateNeverMatchesNonEmptyQuery() {
        assertEquals(null, fuzzyScore("a", ""))
        assertEquals(null, fuzzyScore("ab", "a"))
    }
}
