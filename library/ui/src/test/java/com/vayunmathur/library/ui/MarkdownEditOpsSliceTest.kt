package com.vayunmathur.library.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 3/3 (library/ui root files): markdown editor ops — pure JVM string/selection
 * logic in [MarkdownEditOps.kt] (checkbox/heading/bullet/quote/numbered prefixes,
 * inline markers, block selection, code blocks, rules, links, checkboxes).
 */
class MarkdownEditOpsSliceTest {

    private fun tfv(text: String, start: Int, end: Int = start) =
        TextFieldValue(text, TextRange(start, end))

    // ---- Prefix helpers ----

    @Test fun checkboxPrefixForms() {
        assertTrue(isCheckboxPrefix("- [ ] task"))
        assertTrue(isCheckboxPrefix("- [x] done"))
        assertTrue(isCheckboxPrefix("- [X] done"))
        assertFalse(isCheckboxPrefix("- [ ]task"))
        assertFalse(isCheckboxPrefix("- [] task"))
        assertFalse(isCheckboxPrefix("- task"))
    }

    @Test fun stripLinePrefixAllForms() {
        assertEquals("t", stripLinePrefix("### t"))
        assertEquals("t", stripLinePrefix("###### t"))
        assertEquals("t", stripLinePrefix("- [x] t"))
        assertEquals("t", stripLinePrefix("- t"))
        assertEquals("t", stripLinePrefix("12. t"))
        assertEquals("t", stripLinePrefix("> t"))
        assertEquals("plain", stripLinePrefix("plain"))
        // Seven hashes is not a heading prefix.
        assertEquals("####### t", stripLinePrefix("####### t"))
    }

    @Test fun getLinePrefixAllForms() {
        assertEquals("## ", getLinePrefix("## t"))
        assertEquals("- [ ] ", getLinePrefix("- [ ] t"))
        assertEquals("- ", getLinePrefix("- t"))
        assertEquals("3. ", getLinePrefix("3. t"))
        assertEquals("> ", getLinePrefix("> t"))
        assertEquals("", getLinePrefix("plain"))
    }

    @Test fun matchesPrefixSemantics() {
        assertTrue(matchesPrefix("12. t", "1. "))
        assertFalse(matchesPrefix("- t", "1. "))
        assertTrue(matchesPrefix("- [x] t", "- [ ] "))
        assertFalse(matchesPrefix("- t", "- [ ] "))
        // Plain bullet excludes checkboxes.
        assertTrue(matchesPrefix("- t", "- "))
        assertFalse(matchesPrefix("- [ ] t", "- "))
        assertTrue(matchesPrefix("## t", "## "))
    }

    // ---- Block selection ----

    @Test fun selectedLinesCoverFullLines() {
        val text = "a\nbb\nccc"
        assertEquals(0 to 1, getSelectedLines(text, TextRange(0, 0)))
        assertEquals(2 to 4, getSelectedLines(text, TextRange(3, 3)))
        // Reversed selection normalizes.
        assertEquals(0 to 4, getSelectedLines(text, TextRange(4, 0)))
        // Selection ending exactly at a newline excludes that newline's line.
        assertEquals(0 to 1, getSelectedLines(text, TextRange(0, 2)))
    }

    @Test fun computeBlockSelectionCollapsedTracksLengthDiff() {
        val text = "aa\nbb"
        val lines = listOf("aa", "bb")
        val newLines = listOf("AA!", "bb")
        val sel = computeBlockSelection(text, TextRange(1, 1), 0, lines, newLines, "AA!\nbb")
        assertEquals(TextRange(2), sel)
        // Ranged selection covers the rewritten block.
        val ranged = computeBlockSelection(text, TextRange(0, 5), 0, lines, newLines, "AA!\nbb")
        assertEquals(TextRange(0, 6), ranged)
    }

    // ---- Inline markers ----

    @Test fun hasInlineMarkerGuards() {
        assertTrue(hasInlineMarker("**b**", "**"))
        assertFalse(hasInlineMarker("*b*", "**"))
        // Single-star content wrapped in double stars is not single-star emphasis.
        assertFalse(hasInlineMarker("**b**", "*"))
        assertTrue(hasInlineMarker("*b*", "*"))
        assertTrue(hasInlineMarker("***b***", "*"))
        assertFalse(hasInlineMarker("x", "**"))
        assertFalse(hasInlineMarker("**x", "**"))
    }

    @Test fun emphasisActiveStarSemantics() {
        // Italic needs an odd leftover run on each side; bold needs a pair.
        assertTrue(emphasisActive("*a*", 1, 2, "*"))
        assertFalse(emphasisActive("**a**", 2, 3, "*"))
        assertTrue(emphasisActive("**a**", 2, 3, "**"))
        assertFalse(emphasisActive("*a*", 1, 2, "**"))
        assertTrue(emphasisActive("~~a~~", 2, 3, "~~"))
        assertFalse(emphasisActive("~a~", 1, 2, "~~"))
        assertEquals(2, runLengthLeft("**a", 2, '*'))
        assertEquals(2, runLengthRight("a**", 1, '*'))
        assertEquals(0, runLengthLeft("ba", 1, '*'))
    }

    @Test fun toggleInlineFormatCollapsedInsertsPair() {
        val out = toggleInlineFormat(tfv("ab", 1), "**")
        assertEquals("a****b", out.text)
        assertEquals(TextRange(3), out.selection)
    }

    @Test fun toggleInlineFormatWrapsSelection() {
        val out = toggleInlineFormat(tfv("hello", 1, 4), "**")
        assertEquals("h**ell**o", out.text)
        assertEquals(TextRange(3, 6), out.selection)
    }

    @Test fun toggleInlineFormatUnwrapsMarkedSelection() {
        val out = toggleInlineFormat(tfv("**bold**", 0, 8), "**")
        assertEquals("bold", out.text)
        assertEquals(TextRange(0, 4), out.selection)
        // Markers just outside the selection are removed greedily.
        val greedy = toggleInlineFormat(tfv("**bold**", 2, 6), "**")
        assertEquals("bold", greedy.text)
    }

    @Test fun isInlineFormatActiveReadsSelection() {
        assertFalse(isInlineFormatActive("**b**", TextRange(1, 1), "**"))
        assertTrue(isInlineFormatActive("**b**", TextRange(0, 5), "**"))
        assertTrue(isInlineFormatActive("**b**", TextRange(2, 3), "**"))
        assertFalse(isInlineFormatActive("plain", TextRange(0, 5), "**"))
    }

    // ---- Line prefixes ----

    @Test fun toggleLinePrefixAddsAndRemoves() {
        val added = toggleLinePrefix(tfv("a\nb", 0, 3), "- ")
        assertEquals("- a\n- b", added.text)
        assertEquals(TextRange(0, 7), added.selection)
        val removed = toggleLinePrefix(tfv("- a\n- b", 0, 7), "- ")
        assertEquals("a\nb", removed.text)
        // Mixed: missing prefix wins, existing stripped first (no doubling).
        val mixed = toggleLinePrefix(tfv("- a\nb", 0, 5), "- ")
        assertEquals("- a\n- b", mixed.text)
    }

    @Test fun toggleLinePrefixNumberedRenumbers() {
        val out = toggleLinePrefix(tfv("b\na", 0, 3), "1. ")
        assertEquals("1. b\n2. a", out.text)
        val off = toggleLinePrefix(tfv("1. b\n2. a", 0, 8), "1. ")
        assertEquals("b\na", off.text)
    }

    @Test fun insertHeadingSetsAndClearsLevel() {
        val set = insertHeading(tfv("Title", 0, 5), 2)
        assertEquals("## Title", set.text)
        val cleared = insertHeading(tfv("## Title", 0, 8), 2)
        assertEquals("Title", cleared.text)
        // Different existing level is replaced, not stacked.
        val replaced = insertHeading(tfv("# Title", 0, 7), 3)
        assertEquals("### Title", replaced.text)
    }

    @Test fun isLinePrefixActiveAndHeadingLevel() {
        assertTrue(isLinePrefixActive("- a\n- b", TextRange(0, 7), "- "))
        // Partial-line selections still cover whole lines, so a prefix on the covered
        // line reads active even when the selection starts mid-line.
        assertTrue(isLinePrefixActive("- a\nb", TextRange(2, 4), "- "))
        assertFalse(isLinePrefixActive("a\nb", TextRange(0, 3), "- "))
        assertEquals(2, getActiveHeadingLevel("## T\nbody", 1))
        assertNull(getActiveHeadingLevel("plain", 2))
        assertNull(getActiveHeadingLevel("####### t", 1))
    }

    // ---- Blocks, rules ----

    @Test fun insertCodeBlockCollapsedAndRanged() {
        val collapsed = insertCodeBlock(tfv("ab", 1))
        assertEquals("a```\n\n```b", collapsed.text)
        assertEquals(TextRange(5), collapsed.selection)
        val ranged = insertCodeBlock(tfv("sel", 0, 3))
        assertEquals("```\nsel\n```", ranged.text)
        assertEquals(TextRange(4, 7), ranged.selection)
    }

    @Test fun insertHorizontalRuleSplitsAtCursor() {
        val out = insertHorizontalRule(tfv("ab", 1))
        assertEquals("a\n---\nb", out.text)
        assertEquals(TextRange(6), out.selection)
    }

    // ---- Links ----

    @Test fun findMarkdownLinkAtLocatesSpan() {
        val text = "see [docs](https://d.test) now"
        val m = findMarkdownLinkAt(text, 8)
        assertEquals("docs", m!!.text)
        assertEquals("https://d.test", m.url)
        assertEquals(text.indexOf('['), m.range.first)
        assertNull(findMarkdownLinkAt(text, 0))
        assertNull(findMarkdownLinkAt("plain", 2))
    }

    @Test fun markdownLinkContextStates() {
        // Cursor inside a link offers editing with its text/url.
        val editing = markdownLinkContext(tfv("see [d](https://d.test)", 7))
        assertEquals(LinkContext(editing = true, text = "d", url = "https://d.test"), editing)
        // A range selection offers creation seeded with the selected text.
        val creating = markdownLinkContext(tfv("hello", 1, 4))
        assertEquals(LinkContext(editing = false, text = "ell", url = ""), creating)
        // Collapsed cursor outside any link disables the button.
        assertNull(markdownLinkContext(tfv("plain", 1)))
    }

    @Test fun applyMarkdownLinkCreatesAndEdits() {
        val created = applyMarkdownLink(
            tfv("hello", 1, 4), LinkContext(editing = false, text = "ell", url = ""), "ell", "https://e.test")
        assertEquals("h[ell](https://e.test)o", created.text)
        val edited = applyMarkdownLink(
            tfv("see [d](https://old.test)", 7), LinkContext(editing = true, text = "d", url = "https://old.test"),
            "docs", "https://new.test")
        assertEquals("see [docs](https://new.test)", edited.text)
    }

    // ---- Checkboxes ----

    @Test fun tryToggleCheckboxFlipsBracket() {
        val v = tfv("- [ ] task", 3)
        val checked = tryToggleCheckbox(3, v)
        assertEquals("- [x] task", checked!!.text)
        assertEquals(TextRange(3), checked.selection)
        val unchecked = tryToggleCheckbox(3, checked)
        assertEquals("- [ ] task", unchecked!!.text)
        // Outside the bracket span: no toggle.
        assertNull(tryToggleCheckbox(8, v))
        // Non-checkbox line: no toggle.
        assertNull(tryToggleCheckbox(1, tfv("plain", 1)))
    }

    @Test fun findCheckboxPositionsListsStates() {
        val found = findCheckboxPositions("- [ ] a\n- [x] b\n- plain")
        assertEquals(2, found.size)
        assertEquals(false, found[0].second)
        assertEquals(true, found[1].second)
        assertTrue(findCheckboxPositions("no boxes").isEmpty())
    }
}
