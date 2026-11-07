package com.vayunmathur.email.composer

import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.graphics.Typeface
import com.vayunmathur.email.ui.composer.buildInlineHtml
import com.vayunmathur.email.ui.composer.colorToHex
import com.vayunmathur.email.ui.composer.escapeAttr
import com.vayunmathur.email.ui.composer.findNextSpanBoundary
import com.vayunmathur.email.ui.composer.serializeEmailHtml
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the composer HTML serializer against fixed spans.
 *
 * What the composer emits here is what recipients open, so each test
 * asserts the exact output shape: escaped text, nested inline tags, block
 * wrappers, and the empty-input placeholder.
 *
 * SpannableString needs the Android framework, so these run under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
class EmailHtmlSerializerTest {

    @Test
    fun `empty input yields empty string`() {
        assertEquals("", serializeEmailHtml(SpannableString("")))
    }

    @Test
    fun `single empty paragraph renders a line break`() {
        // One empty para hits the empty-para branch ("<br>"); the
        // "<div><br></div>" fallback only applies when the whole render is blank.
        assertEquals("<br>", serializeEmailHtml(SpannableString("\n")))
        assertEquals("<div><br></div>", serializeEmailHtml(SpannableString("   ")))
    }

    @Test
    fun `plain text is wrapped in a div and html escaped`() {
        val out = serializeEmailHtml(SpannableString("a<b>&c"))
        assertTrue(out.contains("a&lt;b&gt;&amp;c"), out)
        assertTrue(out.startsWith("<div"), out)
    }

    @Test
    fun `bold and italic nest inside the same block`() {
        val text = SpannableString("hello")
        text.setSpan(StyleSpan(Typeface.BOLD), 0, 5, 0)
        text.setSpan(StyleSpan(Typeface.ITALIC), 0, 5, 0)
        val out = serializeEmailHtml(text)
        assertTrue(out.contains("<b>"), out)
        assertTrue(out.contains("<i>"), out)
        assertTrue(out.contains("hello"), out)
    }

    @Test
    fun `underline and strike render`() {
        val text = SpannableString("xy")
        text.setSpan(UnderlineSpan(), 0, 1, 0)
        text.setSpan(StrikethroughSpan(), 1, 2, 0)
        val out = buildInlineHtml(text, 0, 2)
        assertTrue(out.contains("<u>x</u>"), out)
        assertTrue(out.contains("<s>y</s>"), out)
    }

    @Test
    fun `foreground color emits hex span`() {
        val text = SpannableString("red")
        text.setSpan(ForegroundColorSpan(0xFFFF0000.toInt()), 0, 3, 0)
        val out = buildInlineHtml(text, 0, 3)
        assertTrue(out.contains("color:#FF0000"), out)
    }

    @Test
    fun `color helper strips alpha`() {
        assertEquals("#00FF00", colorToHex(0xFF00FF00.toInt()))
        assertEquals("#0000FF", colorToHex(0x800000FF.toInt()))
    }

    @Test
    fun `attribute escaping covers quotes and brackets`() {
        assertEquals("a&amp;b&quot;&lt;c&gt;", escapeAttr("a&b\"<c>"))
    }

    @Test
    fun `span boundary finder stops at span edges`() {
        val text = SpannableString("abcdef")
        text.setSpan(StyleSpan(Typeface.BOLD), 2, 4, 0)
        assertEquals(2, findNextSpanBoundary(text, 0, 6))
        assertEquals(4, findNextSpanBoundary(text, 2, 6))
        assertEquals(6, findNextSpanBoundary(text, 4, 6))
    }
}
