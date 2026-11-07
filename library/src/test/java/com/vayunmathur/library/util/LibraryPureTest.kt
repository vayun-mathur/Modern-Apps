package com.vayunmathur.library.util

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Markdown conversion, number formatting, localized date names, misc utils. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class LibraryPureTest {

    @Test
    fun markdownPlainTextPassesThrough() {
        assertEquals("hello world", parseMarkdown("hello world").text)
        assertEquals("", parseMarkdown("").text)
    }

    @Test
    fun markdownBoldItalicCodeKeepText() {
        val bold = parseMarkdown("a **bold** word")
        assertEquals("a **bold** word", bold.text)
        assertTrue(bold.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        val italic = parseMarkdown("an *italic* word")
        assertTrue(italic.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        val code = parseMarkdown("run `code` now")
        assertEquals("run `code` now", code.text)
        assertTrue(code.spanStyles.isNotEmpty())
    }

    @Test
    fun markdownHiddenMarkersStillContainText() {
        val hidden = parseMarkdown("# Title\n\n**bold**", showMarkers = false)
        assertTrue(hidden.text.contains("Title"))
        assertTrue(hidden.text.contains("bold"))
        assertTrue(hidden.spanStyles.isNotEmpty())
    }

    @Test
    fun markdownListsNormalizeMarkers() {
        val list = parseMarkdown("- one\n- two", showMarkers = true)
        assertTrue(list.text.contains("one"))
        assertTrue(list.text.contains("two"))
        val numbered = parseMarkdown("1. first\n2. second")
        assertTrue(numbered.text.contains("first"))
    }

    @Test
    fun markdownLinksAndStrikethrough() {
        val link = parseMarkdown("see [docs](https://example.com)")
        assertEquals("see [docs](https://example.com)", link.text)
        assertTrue(
            link.getLinkAnnotations(0, link.text.length).isNotEmpty() ||
                link.spanStyles.isNotEmpty(),
        )
        val strike = parseMarkdown("~~old~~ new")
        assertEquals("~~old~~ new", strike.text)
    }

    @Test
    fun markdownSoftWrapMergesLines() {
        val wrapped = parseMarkdown("line one\nline two")
        assertEquals("line one line two", wrapped.text)
        val unwrapped = parseMarkdown("line one\nline two", softWrap = false, process = false)
        assertEquals("line one\nline two", unwrapped.text)
    }

    @Test
    fun markdownBlockquoteAndHeader() {
        val quote = parseMarkdown("> quoted")
        assertTrue(quote.text.contains("quoted"))
        val header = parseMarkdown("## Section")
        assertTrue(header.text.contains("Section"))
        assertTrue(header.paragraphStyles.isNotEmpty() || header.spanStyles.isNotEmpty())
    }

    @Test
    fun numberFormatting() {
        assertEquals("3.14", 3.14159.toStringDigits(2))
        assertEquals("3.1", 3.14159.toStringDigits(1))
        assertEquals("1,234,567", 1234567L.toStringCommas())
        assertEquals("0", 0L.toStringCommas())
        assertTrue(10f.formatSpeed(Locale.US).isNotBlank())
        assertTrue(10f.formatSpeed(Locale.GERMANY).isNotBlank())
    }

    @Test
    fun localizedNamesEnglish() {
        val months = localizedMonthNames(DateNameStyle.FULL, Locale.US)
        assertEquals(12, months.size)
        assertEquals("January", months[0])
        assertEquals("December", months[11])
        assertEquals("AM" to "PM", localizedAmPmMarkers(Locale.US))
        val days = localizedDayOfWeekNames(DateNameStyle.FULL, Locale.US)
        assertEquals(7, days.size)
        assertEquals("Monday", days[0])
        assertEquals("Sunday", days[6])
        // en-US weeks start Sunday (ISO 7); en-GB starts Monday (ISO 1).
        assertEquals(7, localeFirstDayOfWeek(Locale.US))
        assertEquals(1, localeFirstDayOfWeek(Locale.UK))
        assertEquals(listOf(7, 1, 2, 3, 4, 5, 6), localeWeekDayNumbers(Locale.US))
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), localeWeekDayNumbers(Locale.UK))
    }

    @Test
    fun miscUtils() {
        assertEquals(42, tryOrDefault(0) { 42 })
        assertEquals(0, tryOrDefault(0) { throw RuntimeException("x") })
        assertEquals(3.14, 3.14159.round(2))
        assertEquals(3.0, 3.14159.round(0))
        assertEquals(listOf("a", "b"), "a\nb".byteInputStream().readLines())
        assertEquals("Hello", "hello".firstLetterUppercase())
        assertEquals("Hello", "Hello".firstLetterUppercase())
        assertEquals("", "".firstLetterUppercase())
        val tuple = Tuple4(1, "two", 3.0, true)
        assertEquals(1, tuple.first)
        assertEquals("two", tuple.second)
        assertEquals(true, tuple.fourth)
    }
}
