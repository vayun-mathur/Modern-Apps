package com.vayunmathur.keyboard.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DictionaryTest {

    /** Frequencies are deliberately not in alphabetical order, so ranking is exercised. */
    private val dict = Dictionary.fromEntries(
        listOf(
            "the" to 1000,
            "there" to 900,
            "their" to 800,
            "them" to 700,
            "theme" to 50,
            "then" to 600,
            "cat" to 500,
            "car" to 400,
            "cart" to 300,
            "a" to 990,
            "hello" to 200,
        )
    )

    // ---- contains ----

    @Test
    fun containsIsCaseInsensitive() {
        assertTrue(dict.contains("the"))
        assertTrue(dict.contains("The"))
        assertTrue(dict.contains("THE"))
    }

    @Test
    fun containsRejectsUnknownAndEmpty() {
        assertFalse(dict.contains("thex"))
        assertFalse(dict.contains(""))
    }

    // ---- suggestions ----

    @Test
    fun suggestionsAreRankedByFrequencyNotAlphabetically() {
        // A prefix that is itself a word matches itself, so "the" leads on frequency.
        // Alphabetical order would have been the, their, them, theme, then, there.
        assertEquals(listOf("the", "there", "their"), dict.suggestions("the", limit = 3))
    }

    @Test
    fun suggestionsRespectTheLimit() {
        assertEquals(1, dict.suggestions("the", limit = 1).size)
        // the, there, their, them, theme, then
        assertEquals(6, dict.suggestions("the", limit = 10).size)
    }

    @Test
    fun suggestionsIncludeAnExactPrefixMatch() {
        assertTrue(dict.suggestions("cat", limit = 3).contains("cat"))
    }

    @Test
    fun suggestionsMatchTheTypedCapitalisation() {
        assertEquals(listOf("The"), dict.suggestions("The", limit = 1))
        assertEquals(listOf("THE"), dict.suggestions("THE", limit = 1))
    }

    @Test
    fun blankOrUnmatchedPrefixYieldsNothing() {
        assertTrue(dict.suggestions("", limit = 3).isEmpty())
        assertTrue(dict.suggestions("   ", limit = 3).isEmpty())
        assertTrue(dict.suggestions("zzz", limit = 3).isEmpty())
    }

    @Test
    fun nonPositiveLimitYieldsNothing() {
        assertTrue(dict.suggestions("the", limit = 0).isEmpty())
    }

    // ---- autocorrect ----

    /**
     * Separate fixture: words far enough apart that each case below has exactly one
     * candidate within one edit, except [theMostFrequentCandidateWins] which has two by
     * design. Reusing [dict] made the assertions depend on incidental neighbours.
     */
    private val editDict = Dictionary.fromEntries(
        listOf("bat" to 900, "cat" to 500, "cart" to 300, "hello" to 200)
    )

    @Test
    fun knownWordsAreNeverCorrected() {
        assertNull(editDict.autocorrect("cat"))
        assertNull(editDict.autocorrect("hello"))
    }

    @Test
    fun oneSubstitutionIsCorrected() {
        assertEquals("hello", editDict.autocorrect("hallo"))
    }

    @Test
    fun oneDeletionIsCorrected() {
        assertEquals("hello", editDict.autocorrect("helo"))
    }

    @Test
    fun oneInsertionIsCorrected() {
        assertEquals("hello", editDict.autocorrect("helllo"))
    }

    @Test
    fun theMostFrequentCandidateWins() {
        // "xat" is one substitution from both bat (900) and cat (500).
        assertEquals("bat", editDict.autocorrect("xat"))
    }

    @Test
    fun adjacentTranspositionCountsAsOneEdit() {
        // The most common typo class of all. Under plain Levenshtein these are two edits,
        // which left them either uncorrected or corrected to an unrelated word.
        val d = Dictionary.fromEntries(
            listOf("the" to 1000, "ten" to 900, "this" to 800, "receive" to 700)
        )
        assertEquals("the", d.autocorrect("teh"))
        assertEquals("this", d.autocorrect("thsi"))
        assertEquals("receive", d.autocorrect("recieve"))
    }

    @Test
    fun aTranspositionBeatsASubstitutionOnlyOnFrequency() {
        // "teh" is a transposition of "the" and a substitution of "ten"; both are one
        // edit now, so the tie is broken by frequency like any other pair.
        val tenWins = Dictionary.fromEntries(listOf("the" to 100, "ten" to 900))
        assertEquals("ten", tenWins.autocorrect("teh"))
    }

    @Test
    fun nonAdjacentSwapsAreStillTwoEdits() {
        // "abc" -> "cba" swaps the outer pair, which is two edits, not one.
        val d = Dictionary.fromEntries(listOf("cba" to 100))
        assertNull(d.autocorrect("abc"))
    }

    /**
     * The three cases below are the ones the candidate index is easiest to get wrong: each
     * one is a correction whose first character is not the first character the user typed,
     * so a naive "same length, same first letter" bucket would never find it.
     */
    @Test
    fun aTranspositionOfTheFirstTwoCharactersIsCorrected() {
        val d = Dictionary.fromEntries(listOf("you" to 100))
        assertEquals("you", d.autocorrect("oyu"))
    }

    @Test
    fun anExtraCharacterTypedBeforeTheWordIsCorrected() {
        val d = Dictionary.fromEntries(listOf("the" to 100))
        assertEquals("the", d.autocorrect("xthe"))
    }

    @Test
    fun aMissingFirstCharacterIsCorrected() {
        val d = Dictionary.fromEntries(listOf("the" to 100))
        assertEquals("the", d.autocorrect("he"))
    }

    @Test
    fun twoEditsAwayIsNotCorrected() {
        assertNull(editDict.autocorrect("xyzzy"))
    }

    @Test
    fun singleCharacterInputIsNeverCorrected() {
        assertNull(editDict.autocorrect("q"))
    }

    @Test
    fun correctionKeepsTheTypedCapitalisation() {
        assertEquals("Hello", editDict.autocorrect("Hallo"))
        assertEquals("HELLO", editDict.autocorrect("HALLO"))
    }

    // ---- frequency 0 means "known, but never offered" ----

    private val withProfanity = Dictionary.fromEntries(
        listOf("duck" to 100, "dusk" to 90, "fword" to 0, "sword" to 10)
    )

    @Test
    fun neverOfferedWordsAreStillKnownWords() {
        // The whole point: it must not be treated as a misspelling...
        assertTrue(withProfanity.contains("fword"))
        assertNull(withProfanity.autocorrect("fword"))
    }

    @Test
    fun neverOfferedWordsAreNotSuggested() {
        assertTrue(withProfanity.suggestions("fwor", limit = 3).isEmpty())
    }

    @Test
    fun neverOfferedWordsAreNotProducedAsCorrections() {
        // "fwordx" is one edit from "fword" and nothing else, so the only candidate is
        // suppressed and there is no correction at all.
        assertNull(withProfanity.autocorrect("fwordx"))
        // "sword" (frequency 10) is offerable, so a typo of it still corrects.
        assertEquals("sword", withProfanity.autocorrect("swordd"))
    }

    // ---- empty dictionary ----

    @Test
    fun emptyDictionaryIsInertRatherThanCrashing() {
        assertFalse(Dictionary.EMPTY.contains("the"))
        assertTrue(Dictionary.EMPTY.suggestions("the").isEmpty())
        assertNull(Dictionary.EMPTY.autocorrect("teh"))
    }

    @Test
    fun duplicateEntriesKeepTheHighestFrequency() {
        val d = Dictionary.fromEntries(listOf("word" to 5, "word" to 90, "ward" to 50))
        // If the lower duplicate had won, "ward" would outrank "word" here.
        assertEquals(listOf("word"), d.suggestions("w", limit = 1))
    }
}
