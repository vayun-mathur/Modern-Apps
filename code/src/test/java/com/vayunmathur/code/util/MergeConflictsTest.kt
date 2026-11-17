package com.vayunmathur.code.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.vayunmathur.code.domain.Resolution
import com.vayunmathur.code.domain.applyResolutions
import com.vayunmathur.code.domain.parseConflicts

/** Unit tests for the pure merge-conflict parser and resolver. */
class MergeConflictsTest {

    private val conflicted = buildString {
        append("line before\n")
        append("<<<<<<< HEAD\n")
        append("our change\n")
        append("=======\n")
        append("their change\n")
        append(">>>>>>> branch\n")
        append("line after")
    }

    @Test
    fun parsesSingleConflict() {
        val conflicts = parseConflicts(conflicted)
        assertEquals(1, conflicts.size)
        assertEquals(listOf("our change"), conflicts[0].ours)
        assertEquals(listOf("their change"), conflicts[0].theirs)
        assertEquals(1, conflicts[0].startLine)
        assertEquals(5, conflicts[0].endLine)
    }

    @Test
    fun noConflictsInCleanText() {
        assertTrue(parseConflicts("just\nsome\nlines").isEmpty())
    }

    @Test
    fun resolvesOurs() {
        val result = applyResolutions(conflicted, listOf(Resolution.OURS))
        assertEquals("line before\nour change\nline after", result)
    }

    @Test
    fun resolvesTheirs() {
        val result = applyResolutions(conflicted, listOf(Resolution.THEIRS))
        assertEquals("line before\ntheir change\nline after", result)
    }

    @Test
    fun resolvesBothKeepsOursThenTheirs() {
        val result = applyResolutions(conflicted, listOf(Resolution.BOTH))
        assertEquals("line before\nour change\ntheir change\nline after", result)
    }

    @Test
    fun handlesMultipleConflictsIndependently() {
        val text = buildString {
            append("<<<<<<< HEAD\n")
            append("a1\n")
            append("=======\n")
            append("b1\n")
            append(">>>>>>> x\n")
            append("middle\n")
            append("<<<<<<< HEAD\n")
            append("a2\n")
            append("=======\n")
            append("b2\n")
            append(">>>>>>> y")
        }
        assertEquals(2, parseConflicts(text).size)
        val result = applyResolutions(text, listOf(Resolution.OURS, Resolution.THEIRS))
        assertEquals("a1\nmiddle\nb2", result)
    }

    @Test
    fun missingChoiceLeavesBlockUntouched() {
        val result = applyResolutions(conflicted, emptyList())
        assertEquals(conflicted, result)
    }
}
