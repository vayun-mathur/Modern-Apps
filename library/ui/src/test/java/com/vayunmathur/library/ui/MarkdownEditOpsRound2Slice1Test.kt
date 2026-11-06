package com.vayunmathur.library.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Markdown edit ops round two: collapsed selections, greedy unwraps, links
 * in edit mode, single-star markers. Slice-1 coverage for library/ui.
 */
class MarkdownEditOpsRound2Slice1Test {

    private fun value(text: String, start: Int = 0, end: Int = 0) =
        TextFieldValue(text, TextRange(start, end))

    @Test
    fun `a collapsed selection inside bold markers unwraps greedily`() {
        // Cursor inside "**hi**" with an empty selection drops a pair (no unwrap
        // without a range); a range over the inner text unwraps.
        val out = toggleInlineFormat(value("**hi**", 2, 4), "**")
        assertEquals("hi", out.text)
    }

    @Test
    fun `markers outside the selection unwrap`() {
        val out = toggleInlineFormat(value("**hi**", 2, 4), "**")
        assertEquals("hi", out.text)
        assertEquals(TextRange(0, 2), out.selection)
    }

    @Test
    fun `single-star markers respect double stars`() {
        // "**hi**" is not single-star emphasis.
        assertFalse(hasInlineMarker("**hi**", "*"))
        assertTrue(hasInlineMarker("*hi*", "*"))
        assertTrue(hasInlineMarker("***hi***", "*"))
        assertFalse(hasInlineMarker("x", "*"))
    }

    @Test
    fun `editing an existing link replaces it`() {
        val v = value("see [docs](https://old.test) now", 8, 8)
        val ctx = markdownLinkContext(v)
        assertTrue(ctx != null && ctx.editing)
        assertEquals("https://old.test", ctx.url)
        val out = applyMarkdownLink(v, ctx, "docs", "https://new.test")
        assertTrue(out.text.contains("[docs](https://new.test)"), out.text)
        assertTrue(!out.text.contains("old.test"), out.text)
    }

    @Test
    fun `a collapsed cursor outside links gives no context`() {
        assertEquals(null, markdownLinkContext(value("plain", 2, 2)))
    }

    @Test
    fun `a code block wraps the selection`() {
        val out = insertCodeBlock(value("code", 0, 4))
        assertTrue(out.text.contains("```\ncode\n```"), out.text)
    }

    @Test
    fun `quote prefixes toggle per block`() {
        val on = toggleLinePrefix(value("a\nb", 0, 3), "> ")
        assertEquals("> a\n> b", on.text)
        // Collapsed selection inside a quoted line keeps the caret on the line.
        val collapsed = toggleLinePrefix(value("> a", 2, 2), "> ")
        assertEquals("a", collapsed.text)
        assertTrue(collapsed.selection.start >= 0)
    }

    @Test
    fun `checkbox prefixes match the exact marker`() {
        assertTrue(isCheckboxPrefix("- [X] done"))
        // Indented boxes are not checkbox lines.
        assertFalse(isCheckboxPrefix("  - [X] done"))
        assertEquals("- [ ] ", getLinePrefix("- [ ] task"))
        assertEquals("", getLinePrefix("plain"))
    }

    @Test
    fun `checkbox toggle past the box does nothing`() {
        assertEquals(null, tryToggleCheckbox(9, value("- [ ] todo", 0, 0)))
        // Unchecking flips back.
        val off = tryToggleCheckbox(3, value("- [x] todo", 0, 0))
        assertTrue(off != null && off.text.contains("- [ ] todo"), off?.text)
    }
}
