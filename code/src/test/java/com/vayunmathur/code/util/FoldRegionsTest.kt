package com.vayunmathur.code.util

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.vayunmathur.code.domain.FoldRegion
import com.vayunmathur.code.domain.computeFoldRegions
import com.vayunmathur.code.platform.dedentSelection
import com.vayunmathur.code.platform.indentSelection

/** Unit tests for the pure fold-region computation and indent/dedent helpers. */
class FoldRegionsTest {

    @Test
    fun nestedIndentationProducesNestedRegions() {
        val src = "class A {\n    fun b() {\n        x()\n    }\n}"
        val regions = computeFoldRegions(src)
        assertTrue(FoldRegion(0, 3) in regions) // class body: lines 1..3
        assertTrue(FoldRegion(1, 2) in regions) // function body: line 2
    }

    @Test
    fun flatTextHasNoRegions() {
        assertTrue(computeFoldRegions("a\nb\nc").isEmpty())
    }

    @Test
    fun blankLinesDoNotBreakRegions() {
        val src = "def f():\n    a\n\n    b"
        val regions = computeFoldRegions(src)
        assertTrue(FoldRegion(0, 3) in regions)
    }

    @Test
    fun indentSelectionAddsIndentToEachLine() {
        val value = TextFieldValue("a\nb", TextRange(0, 3))
        val result = indentSelection(value, "  ")
        assertEquals("  a\n  b", result.text)
    }

    @Test
    fun dedentSelectionRemovesUpToTabWidthSpaces() {
        val value = TextFieldValue("    a\n  b", TextRange(0, 8))
        val result = dedentSelection(value, 4)
        assertEquals("a\nb", result.text)
    }

    @Test
    fun dedentSelectionRemovesOneLeadingTab() {
        val value = TextFieldValue("\tabc", TextRange(0, 4))
        val result = dedentSelection(value, 4)
        assertEquals("abc", result.text)
    }
}
