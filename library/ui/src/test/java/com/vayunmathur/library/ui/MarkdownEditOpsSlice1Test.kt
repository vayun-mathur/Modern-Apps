package com.vayunmathur.library.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Markdown editing ops: inline toggles, line prefixes, headings, links, checkboxes.
 * Slice-1 coverage for library/ui (converters + editors).
 */
class MarkdownEditOpsSlice1Test {

    private fun value(text: String, start: Int = 0, end: Int = 0) =
        TextFieldValue(text, TextRange(start, end))

    @Test
    fun `wrapping a word bolds it`() {
        val out = toggleInlineFormat(value("hello", 0, 5), "**")
        assertEquals("**hello**", out.text)
    }

    @Test
    fun `unwrapping removes the markers`() {
        val out = toggleInlineFormat(value("**hello**", 0, 9), "**")
        assertEquals("hello", out.text)
    }

    @Test
    fun `an empty selection drops a marker pair with the cursor inside`() {
        val out = toggleInlineFormat(value("hi", 1, 1), "**")
        assertEquals("h****i", out.text)
        assertEquals(TextRange(3), out.selection)
    }

    @Test
    fun `line prefixes toggle on and off`() {
        val on = toggleLinePrefix(value("item", 0, 4), "- ")
        assertEquals("- item", on.text)
        val off = toggleLinePrefix(value("- item", 0, 6), "- ")
        assertEquals("item", off.text)
    }

    @Test
    fun `numbered prefixes count up`() {
        val out = toggleLinePrefix(value("a\nb", 0, 3), "1. ")
        assertEquals("1. a\n2. b", out.text)
    }

    @Test
    fun `headings insert and remove`() {
        val on = insertHeading(value("Title", 0, 5), 2)
        assertEquals("## Title", on.text)
        val off = insertHeading(value("## Title", 0, 8), 2)
        assertEquals("Title", off.text)
    }

    @Test
    fun `checkbox prefixes detect and strip`() {
        assertTrue(isCheckboxPrefix("- [x] done"))
        assertFalse(isCheckboxPrefix("- plain"))
        assertEquals("done", stripLinePrefix("- [x] done"))
        // A bare checkbox marker with no text strips to empty.
        assertEquals("", stripLinePrefix("- [ ] "))
        assertEquals("- ", getLinePrefix("- item"))
        assertEquals("## ", getLinePrefix("## Head"))
    }

    @Test
    fun `links parse at the cursor`() {
        val text = "see [docs](https://example.com) now"
        val match = findMarkdownLinkAt(text, 8)
        assertTrue(match != null)
        assertEquals("https://example.com", match.url)
        assertEquals(null, findMarkdownLinkAt("no links here", 3)?.url)
    }

    @Test
    fun `checkbox toggles flip at the offset`() {
        val v = value("- [ ] todo", 0, 0)
        val toggled = tryToggleCheckbox(3, v)
        assertTrue(toggled != null)
        assertTrue(toggled.text.contains("- [x] todo"))
        assertEquals(null, tryToggleCheckbox(0, value("plain", 0, 0)))
    }

    @Test
    fun `checkbox positions scan the text`() {
        val positions = findCheckboxPositions("- [ ] a\n- plain\n- [x] b")
        assertEquals(2, positions.size)
        assertEquals(listOf(false, true), positions.map { it.second })
    }

    @Test
    fun `inline active states read the selection`() {
        assertTrue(isInlineFormatActive("**hi**", TextRange(0, 6), "**"))
        assertFalse(isInlineFormatActive("hi", TextRange(0, 2), "**"))
        assertEquals(2, getActiveHeadingLevel("## Head", 5))
        assertEquals(null, getActiveHeadingLevel("plain", 2))
        assertTrue(isLinePrefixActive("- item", TextRange(0, 6), "- "))
    }

    @Test
    fun `code blocks and rules insert`() {
        val code = insertCodeBlock(value("hi", 0, 2))
        assertTrue(code.text.contains("```"), code.text)
        val rule = insertHorizontalRule(value("hi", 0, 2))
        assertTrue(rule.text.contains("---"), rule.text)
    }

    @Test
    fun `applying a link wraps the selection`() {
        val v = value("docs", 0, 4)
        val ctx = markdownLinkContext(v)
        assertTrue(ctx != null)
        val out = applyMarkdownLink(v, ctx, "docs", "https://example.com")
        assertTrue(out.text.contains("[docs](https://example.com)"), out.text)
    }
}
