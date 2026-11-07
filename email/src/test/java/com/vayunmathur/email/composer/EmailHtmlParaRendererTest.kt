package com.vayunmathur.email.composer

import android.text.SpannableString
import android.text.style.BulletSpan
import com.vayunmathur.email.ui.composer.serializeEmailHtml
import com.vayunmathur.library.ui.EmailAlignmentSpan
import com.vayunmathur.library.ui.EmailBlockQuoteSpan
import com.vayunmathur.library.ui.HeadingSpan
import com.vayunmathur.library.ui.HrSpan
import com.vayunmathur.library.ui.IndentSpan
import com.vayunmathur.library.ui.OrderedListSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Paragraph-level branches of the email HTML serializer: what the
 * recipient's client opens depends on the right block wrapper, so lists
 * must group into one list element, headings/blockquotes must carry
 * their styles, and a following plain paragraph must close an open list.
 */
@RunWith(RobolectricTestRunner::class)
class EmailHtmlParaRendererTest {

    private fun spanned(text: String, vararg spans: Pair<Any, IntRange>): SpannableString {
        val s = SpannableString(text)
        for ((span, range) in spans) {
            s.setSpan(span, range.first, range.last + 1, 0)
        }
        return s
    }

    @Test
    fun `bullet paragraphs group into a single unordered list`() {
        val out = serializeEmailHtml(
            spanned("one\ntwo", BulletSpan() to (0..2), BulletSpan() to (4..6))
        )
        assertEquals("<ul><li>one</li><li>two</li></ul>", out)
    }

    @Test
    fun `ordered paragraphs group into a single ordered list`() {
        val out = serializeEmailHtml(
            spanned("one\ntwo", OrderedListSpan(1) to (0..2), OrderedListSpan(2) to (4..6))
        )
        assertEquals("<ol><li>one</li><li>two</li></ol>", out)
    }

    @Test
    fun `switching from bullets to ordered closes the first list`() {
        val out = serializeEmailHtml(
            spanned("one\ntwo", BulletSpan() to (0..2), OrderedListSpan(1) to (4..6))
        )
        assertEquals("<ul><li>one</li></ul><ol><li>two</li></ol>", out)
    }

    @Test
    fun `a plain paragraph after a list closes it`() {
        val out = serializeEmailHtml(
            spanned("one\nplain", BulletSpan() to (0..2))
        )
        assertEquals("<ul><li>one</li></ul><div>plain</div>", out)
    }

    @Test
    fun `heading paragraph renders the heading tag with margin`() {
        val out = serializeEmailHtml(spanned("Title", HeadingSpan(2) to (0..4)))
        assertEquals("<h2 style=\"margin:0.5em 0\">Title</h2>", out)
    }

    @Test
    fun `heading with alignment and indent carries all styles`() {
        val out = serializeEmailHtml(
            spanned(
                "Title",
                HeadingSpan(1) to (0..4),
                EmailAlignmentSpan("center") to (0..4),
                IndentSpan(2) to (0..4),
            )
        )
        assertTrue(out.contains("<h1"), out)
        assertTrue(out.contains("text-align:center"), out)
        assertTrue(out.contains("margin-left:48px"), out)
    }

    @Test
    fun `blockquote paragraph renders the quote block`() {
        val out = serializeEmailHtml(spanned("quoted", EmailBlockQuoteSpan() to (0..5)))
        assertTrue(out.startsWith("<blockquote"), out)
        assertTrue(out.contains("border-left:2px solid #ccc"), out)
        assertTrue(out.contains("quoted"), out)
    }

    @Test
    fun `centered paragraph renders a text-align div`() {
        val out = serializeEmailHtml(spanned("mid", EmailAlignmentSpan("center") to (0..2)))
        assertEquals("<div style=\"text-align:center\">mid</div>", out)
    }

    @Test
    fun `indented paragraph renders the margin div`() {
        val out = serializeEmailHtml(spanned("deep", IndentSpan(3) to (0..3)))
        assertEquals("<div style=\"margin-left:72px\">deep</div>", out)
    }

    @Test
    fun `empty paragraph renders a plain break`() {
        // The empty-para branch emits <br> before any block styling; an
        // indent span sitting on the newline itself is not picked up.
        val out = serializeEmailHtml(spanned("\n", IndentSpan(1) to (0..0)))
        assertEquals("<br>", out)
    }

    @Test
    fun `horizontal rule paragraph renders hr and closes lists`() {
        val out = serializeEmailHtml(
            spanned("one\nx", BulletSpan() to (0..2), HrSpan() to (4..4))
        )
        assertEquals("<ul><li>one</li></ul><hr>", out)
    }

    @Test
    fun `list item keeps a heading inside the item`() {
        val out = serializeEmailHtml(
            spanned("head", BulletSpan() to (0..3), HeadingSpan(3) to (0..3))
        )
        assertEquals("<ul><li><h3 style=\"margin:0;\">head</h3></li></ul>", out)
    }

    @Test
    fun `list item keeps a blockquote inside the item`() {
        val out = serializeEmailHtml(
            spanned("q", BulletSpan() to (0..0), EmailBlockQuoteSpan() to (0..0))
        )
        assertTrue(out.startsWith("<ul><li><blockquote"), out)
        assertTrue(out.contains(">q</blockquote></li></ul>"), out)
    }

    @Test
    fun `indented list item wraps the item content`() {
        val out = serializeEmailHtml(
            spanned("item", BulletSpan() to (0..3), IndentSpan(1) to (0..3))
        )
        assertEquals(
            "<ul><li><div style=\"margin-left: 24px\">item</div></li></ul>",
            out,
        )
    }
}
