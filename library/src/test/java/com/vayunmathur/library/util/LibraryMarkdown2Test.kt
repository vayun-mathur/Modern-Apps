package com.vayunmathur.library.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Deep markdown coverage: math/latex, fences, tasks, search highlight, hidden markers. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class LibraryMarkdown2Test {

    @Test fun headerLevelsGetDescendingSizes() {
        val h1 = parseMarkdown("# One")
        val h3 = parseMarkdown("### Three")
        val s1 = h1.spanStyles.first { it.item.fontWeight == FontWeight.Bold }
        val s3 = h3.spanStyles.first { it.item.fontWeight == FontWeight.Bold }
        assertTrue(s1.item.fontSize.value > s3.item.fontSize.value)
        assertTrue(h1.paragraphStyles.isNotEmpty())
        // Level beyond 6 hashes is not a header.
        val notHeader = parseMarkdown("####### Seven")
        assertFalse(notHeader.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.item.fontSize.value > 20f })
    }

    @Test fun hiddenMarkersKeepTextButHideSpans() {
        val hidden = parseMarkdown("**bold** and *ital*", showMarkers = false)
        assertEquals("**bold** and *ital*", hidden.text)
        // Transparent 0-size spans hide the markers.
        assertTrue(hidden.spanStyles.any { it.item.color == Color.Transparent })
        val shown = parseMarkdown("**bold**", showMarkers = true)
        assertFalse(shown.spanStyles.any { it.item.color == Color.Transparent && it.item.fontSize.value == 0f })
    }

    @Test fun taskListCheckedIsStruckThrough() {
        val md = parseMarkdown("- [x] done task\n- [ ] open task")
        assertTrue(md.text.contains("done task"))
        assertTrue(md.text.contains("open task"))
        assertTrue(md.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough })
    }

    @Test fun nestedAndNumberedLists() {
        val md = parseMarkdown("- a\n  - b\n    - c\n1) first\n2) second")
        assertTrue(md.text.contains("• a"))
        assertTrue(md.text.contains("first"))
        assertTrue(md.paragraphStyles.isNotEmpty(), "list indent paragraph styles expected")
    }

    @Test fun fencedCodeIsMonospaceAndInlineCodeSkipsFence() {
        val md = parseMarkdown("before\n```kotlin\nval x = 1\n```\nafter `code` end")
        assertTrue(md.text.contains("val x = 1"))
        assertTrue(md.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
        // Raw mode (no soft-wrap merging) is what lets the fence regex see the block.
        val fenced = parseMarkdown("```kotlin\nval x = 1\nval y = 2\n```", process = false, softWrap = false)
        assertTrue(fenced.text.contains("val x = 1"))
        assertTrue(
            fenced.spanStyles.any {
                it.item.fontFamily == FontFamily.Monospace && it.item.background != Color.Unspecified
            },
            "fence content should carry the code background",
        )
    }

    @Test fun blockMathFormatsFracSqrtAndGreekWhenHidden() {
        val md = parseMarkdown("$$\\frac{1}{2} + \\sqrt{4} + \\alpha$$", showMarkers = false)
        assertTrue(md.text.contains("((1)/(2))"), md.text)
        assertTrue(md.text.contains("√(4)"), md.text)
        assertTrue(md.text.contains("α"), md.text)
        assertTrue(md.spanStyles.any { it.item.fontFamily == FontFamily.Serif })
        assertTrue(md.paragraphStyles.isNotEmpty(), "block math centred paragraph expected")
    }

    @Test fun inlineMathAndSqrtN() {
        val md = parseMarkdown("value $\\sqrt[3]{8}$ end", showMarkers = false)
        assertTrue(md.text.contains("(8)^(1/3)"), md.text)
        val shown = parseMarkdown("value \$x^2\$ end", showMarkers = true)
        assertEquals("value \$x^2\$ end", shown.text)
    }

    @Test fun latexFormatCommandsStyleContent() {
        val bold = parseMarkdown("\\mathbf{word}")
        assertTrue(bold.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        val cancel = parseMarkdown("\\cancel{word}")
        assertTrue(cancel.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough })
        val under = parseMarkdown("\\underline{word}")
        assertTrue(under.spanStyles.any { it.item.textDecoration == TextDecoration.Underline })
        val mono = parseMarkdown("\\mathtt{word}")
        assertTrue(mono.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
        val italic = parseMarkdown("\\mathit{word}")
        assertTrue(italic.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }

    @Test fun latexOperatorsAndNestedFrac() {
        val md = parseMarkdown("$$\\frac{\\frac{1}{2}}{3} \\times \\infty \\leq$$", showMarkers = false)
        assertTrue(md.text.contains("×"), md.text)
        assertTrue(md.text.contains("∞"), md.text)
        assertTrue(md.text.contains("≤"), md.text)
        val leftRight = parseMarkdown("$$\\left( x \\right)$$", showMarkers = false)
        assertFalse(leftRight.text.contains("\\left"), leftRight.text)
    }

    @Test fun searchHighlightMarksCurrentAndOthers() {
        val md = parseMarkdown("cat dog cat bird cat", searchQuery = "cat", searchIndex = 1)
        val backgrounds = md.spanStyles.mapNotNull { it.item.background.takeIf { b -> b != Color.Unspecified } }
        assertTrue(backgrounds.size >= 3, "expected 3 highlights, got $backgrounds")
        assertTrue(backgrounds.any { it == Color(0xFFFFA500) }, "current highlight orange")
        assertTrue(backgrounds.any { it == Color.Yellow })
        val none = parseMarkdown("hello", searchQuery = "zzz")
        assertFalse(none.spanStyles.any { it.item.background == Color.Yellow })
        val emptyQuery = parseMarkdown("hello", searchQuery = "")
        assertFalse(emptyQuery.spanStyles.any { it.item.background == Color.Yellow })
    }

    @Test fun linksCarryUrlAnnotation() {
        val md = parseMarkdown("go to [Example](https://example.org/path) now")
        val links = md.getLinkAnnotations(0, md.text.length)
        assertEquals(1, links.size)
        assertTrue(md.spanStyles.any { it.item.textDecoration == TextDecoration.Underline })
    }

    @Test fun strikethroughAndBlockquoteStyles() {
        val strike = parseMarkdown("~~gone~~ kept")
        assertTrue(strike.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough })
        val quote = parseMarkdown("> wise words\nplain")
        assertTrue(quote.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }

    @Test fun softWrapCollapsesBlankRunsAndMerges() {
        val md = parseMarkdown("para one\nstill one\n\n\n\npara two")
        assertEquals("para one still one\npara two", md.text)
        val noSoft = parseMarkdown("a\nb", softWrap = false, process = false)
        assertEquals("a\nb", noSoft.text)
        val raw = parseMarkdown("# H\n- item", process = false, softWrap = false)
        assertEquals("# H\n- item", raw.text)
    }

    @Test fun underscoreBoldAndItalic() {
        val bold = parseMarkdown("__strong__ text")
        assertTrue(bold.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        val ital = parseMarkdown("_em_ text")
        assertTrue(ital.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }

    @Test fun latexCommandSweepAndHeaderLevel6() {
        val md = parseMarkdown(
            "$$\\Gamma \\Delta \\rightarrow \\leftarrow \\Rightarrow \\sum \\int \\prod \\sin \\cos " +
                "\\forall \\exists \\in \\subset \\cup \\cap \\infty \\partial \\nabla " +
                "\\langle x \\rangle \\lceil y \\rceil \\quad z$$",
            showMarkers = false,
        )
        for (expected in listOf("Γ", "Δ", "→", "←", "⇒", "∑", "∫", "∏", "sin", "cos", "∀", "∃", "∈", "⊂", "∪", "∩", "∞", "∂", "∇", "⟨", "⟩", "⌈", "⌉")) {
            assertTrue(md.text.contains(expected), "missing $expected in ${md.text}")
        }
        val h6 = parseMarkdown("###### Deepest")
        assertTrue(h6.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        val upperTask = parseMarkdown("- [X] shipped")
        assertTrue(upperTask.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough })
    }

    @Test fun bulletVariantsNormalise() {
        val md = parseMarkdown("* star\n+ plus\n- dash")
        assertTrue(md.text.lines().all { it.trimStart().startsWith("•") || it.isBlank() }, md.text)
    }
}
