package com.vayunmathur.library.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Markdown edges not covered elsewhere: empty input, raw modes, math escapes, search bounds, list forms. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class LibraryCoverageMarkdownTest {

    @Test fun emptyAndBlankInput() {
        assertEquals("", parseMarkdown("").text)
        assertEquals("", parseMarkdown("   \n\n  ").text)
        assertEquals("", parseMarkdown("", showMarkers = false, searchQuery = "x").text)
        val onlyNewlines = parseMarkdown("\n\n\n", process = false, softWrap = false)
        assertEquals("\n\n\n", onlyNewlines.text)
    }

    @Test fun plainTextHasNoStyles() {
        val md = parseMarkdown("just words here")
        assertEquals("just words here", md.text)
        assertTrue(md.spanStyles.isEmpty(), md.spanStyles.toString())
        assertTrue(md.paragraphStyles.isEmpty())
    }

    @Test fun headerRequiresSpaceAndLevelsScale() {
        assertFalse(parseMarkdown("#NoSpace").spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        val sizes = (1..6).map { level ->
            val md = parseMarkdown("${"#".repeat(level)} Title$level")
            md.spanStyles.first { it.item.fontWeight == FontWeight.Bold }.item.fontSize.value
        }
        assertEquals(sizes.sortedDescending(), sizes, "header sizes must descend: $sizes")
        // Shown markers: header marker spans are not hidden.
        val shown = parseMarkdown("# T", showMarkers = true)
        assertFalse(shown.spanStyles.any { it.item.color == Color.Transparent })
    }

    @Test fun listMarkerFormsAndTaskStates() {
        val numberedDot = parseMarkdown("1. first\n2. second", process = true)
        assertTrue(numberedDot.text.contains("first"))
        assertTrue(numberedDot.paragraphStyles.isNotEmpty())
        val unchecked = parseMarkdown("- [ ] todo item")
        assertFalse(unchecked.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough })
        // Without processing, markers are not normalised to bullets.
        val raw = parseMarkdown("- dash\n* star", process = false, softWrap = false)
        assertTrue(raw.text.startsWith("- dash"), raw.text)
        // Deep indentation keeps content and yields paragraph indent.
        val deep = parseMarkdown("- a\n      - deep item")
        assertTrue(deep.text.contains("deep item"))
        assertTrue(deep.paragraphStyles.size >= 2)
    }

    @Test fun blockquoteMultipleLinesAndTrailingContent() {
        val md = parseMarkdown("> first quote\n> second quote\nplain after")
        val italics = md.spanStyles.filter { it.item.fontStyle == FontStyle.Italic }
        assertTrue(italics.size >= 2, italics.toString())
        assertTrue(md.text.contains("plain after"))
        // Blockquote at end of input with no trailing newline.
        val tail = parseMarkdown("intro\n> last line")
        assertTrue(tail.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }

    @Test fun inlineFormattingCombinations() {
        val md = parseMarkdown("**bold _inner_ end** and `code` and ~~old~~")
        assertTrue(md.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(md.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        assertTrue(md.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
        assertTrue(md.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough })
        // Single asterisk inside words does not create spurious bold.
        val star = parseMarkdown("2 * 3 * 4")
        assertEquals("2 * 3 * 4", star.text)
        // Link with shown markers keeps the URL annotation too.
        val link = parseMarkdown("[Label](https://example.com)", showMarkers = true)
        assertEquals(1, link.getLinkAnnotations(0, link.text.length).size)
    }

    @Test fun mathShownMarkersAreNotReformatted() {
        val md = parseMarkdown("$$\\frac{1}{2}$$", showMarkers = true)
        assertTrue(md.text.contains("\\frac{1}{2}"), md.text)
        // Still styled serif, and centred only when processing.
        assertTrue(md.spanStyles.any { it.item.fontFamily == FontFamily.Serif })
        val rawBlock = parseMarkdown("\$\$x\$\$", process = false, softWrap = false)
        assertFalse(rawBlock.paragraphStyles.any { it.item.textAlign == TextAlign.Center })
    }

    @Test fun mathEscapesAndLeftRightCommands() {
        // \leftarrow is a command, not \left + arrow text.
        val arrow = parseMarkdown("$$\\leftarrow x$$", showMarkers = false)
        assertTrue(arrow.text.contains("←"), arrow.text)
        assertFalse(arrow.text.contains("\\left"), arrow.text)
        val parens = parseMarkdown("$$\\left( \\frac{a}{b} \\right)$$", showMarkers = false)
        assertTrue(parens.text.contains("((a)/(b))"), parens.text)
        // Escaped dollar is not inline math.
        val escaped = parseMarkdown("cost is \\$5 today", showMarkers = false)
        assertEquals("cost is \\$5 today", escaped.text)
        // Nested sqrt/fraction formatting.
        val nested = parseMarkdown("$$\\sqrt{\\frac{4}{9}}$$", showMarkers = false)
        assertTrue(nested.text.contains("√(((4)/(9)))"), nested.text)
    }

    @Test fun latexRemainingCommandsAndUnknownCommand() {
        val md = parseMarkdown(
            "\\mathrm{plain} \\mathsf{sans} \\mathit{ital} \\mathbf{bold}",
            showMarkers = false,
        )
        assertTrue(md.spanStyles.any { it.item.fontStyle == FontStyle.Normal })
        assertTrue(md.spanStyles.any { it.item.fontFamily == FontFamily.SansSerif })
        assertTrue(md.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        val sweep = parseMarkdown(
            "$$\\epsilon \\vartheta \\varphi \\oplus \\otimes \\bullet \\approx \\cong \\equiv \\propto " +
                "\\supset \\ni \\ellnot \\leftrightarrow \\mapsto \\cot \\sec \\csc \\arcsin \\log \\ln " +
                "\\exp \\lim \\max \\min \\det \\gcd \\lfloor z \\rfloor \\partial \\angle \\degree$$",
            showMarkers = false,
        )
        for (expected in listOf("ε", "ϑ", "ϕ", "⊕", "⊗", "•", "≈", "≅", "≡", "∝", "⊃", "∋", "↔", "↦", "cot", "sec", "csc", "arcsin", "log", "ln", "exp", "lim", "max", "min", "det", "gcd", "⌊", "⌋", "∂", "∠", "°")) {
            assertTrue(sweep.text.contains(expected), "missing $expected in ${sweep.text}")
        }
        // \ellnot is not a known command and must pass through untouched (no crash).
        assertTrue(sweep.text.contains("\\ellnot") || sweep.text.contains("¬"), sweep.text)
    }

    @Test fun searchHighlightBoundsAndCase() {
        val beyond = parseMarkdown("cat cat", searchQuery = "cat", searchIndex = 5)
        // No match is "current" when the index is past the last hit: all plain yellow.
        val bgs = beyond.spanStyles.mapNotNull { it.item.background.takeIf { b -> b != Color.Unspecified } }
        assertEquals(2, bgs.size)
        assertTrue(bgs.all { it == Color.Yellow }, bgs.toString())
        val upper = parseMarkdown("Cat CAT cAt", searchQuery = "CAT", searchIndex = 0)
        assertEquals(3, upper.spanStyles.count { it.item.background != Color.Unspecified })
        val negative = parseMarkdown("cat cat", searchQuery = "cat", searchIndex = -1)
        assertTrue(negative.spanStyles.none { it.item.background == Color(0xFFFFA500) })
        val wholeWord = parseMarkdown("concatenate", searchQuery = "cat")
        assertEquals(1, wholeWord.spanStyles.count { it.item.background != Color.Unspecified })
    }

    @Test fun softWrapSpecialLinesAreNotMerged() {
        val md = parseMarkdown("intro line\n# Heading\n- item one\n> quote line\ntail line")
        assertTrue(md.text.contains("# Heading"), md.text)
        assertTrue(md.text.contains("tail line"), md.text)
        // Blank-line collapsing keeps exactly one separator.
        val paras = parseMarkdown("one\n\n\n\n\n\ntwo")
        assertEquals("one\ntwo", paras.text)
        // No soft wrap: lines stay separate even when process is on for lists.
        val noWrap = parseMarkdown("line a\nline b", process = false, softWrap = false)
        assertEquals("line a\nline b", noWrap.text)
        // Hard wrap with a trailing blank line trims in soft-wrap mode.
        assertEquals("end", parseMarkdown("end\n\n\n").text)
    }

    @Test fun fencedBlockWithLanguageAndBlankLinesInside() {
        val md = parseMarkdown("```\nline1\n\nline2\n```", process = false, softWrap = false)
        assertTrue(md.text.contains("line1"))
        assertTrue(md.text.contains("line2"))
        assertTrue(md.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
        // Inline code after a fence is still styled as code, fence content is not double-styled red.
        val mixed = parseMarkdown("```\nval a = 1\n```\nuse `a` here", process = false, softWrap = false)
        assertTrue(mixed.spanStyles.any { it.item.color == Color(0xFFD32F2F) }, mixed.spanStyles.toString())
    }
}
