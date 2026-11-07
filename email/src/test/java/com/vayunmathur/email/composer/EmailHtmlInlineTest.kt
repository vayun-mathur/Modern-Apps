package com.vayunmathur.email.composer

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import com.vayunmathur.email.ui.composer.CidImageSpan
import com.vayunmathur.email.ui.composer.buildInlineHtml
import com.vayunmathur.email.ui.composer.serializeEmailHtml
import com.vayunmathur.library.ui.FontFamilySpan
import com.vayunmathur.library.ui.HrSpan
import com.vayunmathur.library.ui.InlineCodeSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Inline-span branches of the email HTML serializer: each editor span
 * type must become its email-safe tag (links keep their href escaped,
 * font/color/size spans become styled spans, CID images become cid:
 * img tags, HR markers become hr), because that HTML is what the
 * recipient's client renders.
 */
@RunWith(RobolectricTestRunner::class)
class EmailHtmlInlineTest {

    private fun spanned(text: String, span: Any, range: IntRange): SpannableString {
        val s = SpannableString(text)
        s.setSpan(span, range.first, range.last + 1, 0)
        return s
    }

    @Test
    fun `link span becomes an anchor with the escaped href`() {
        val out = buildInlineHtml(
            spanned("click", URLSpan("https://x.example/?a=1&b=\"2\""), 0..4), 0, 5,
        )
        assertEquals("<a href=\"https://x.example/?a=1&amp;b=&quot;2&quot;\">click</a>", out)
    }

    @Test
    fun `inline code span becomes the styled code tag`() {
        val out = buildInlineHtml(spanned("code", InlineCodeSpan(), 0..3), 0, 4)
        assertTrue(out.startsWith("<code"), out)
        assertTrue(out.contains("font-family:monospace"), out)
        assertTrue(out.endsWith("code</code>"), out)
    }

    @Test
    fun `font family spans map named families to css`() {
        assertTrue(
            buildInlineHtml(spanned("a", FontFamilySpan("serif"), 0..0), 0, 1)
                .contains("font-family:serif"),
        )
        assertTrue(
            buildInlineHtml(spanned("a", FontFamilySpan("sans_serif"), 0..0), 0, 1)
                .contains("font-family:sans-serif"),
        )
        assertTrue(
            buildInlineHtml(spanned("a", TypefaceSpan("monospace"), 0..0), 0, 1)
                .contains("font-family:monospace"),
        )
        assertTrue(
            buildInlineHtml(spanned("a", FontFamilySpan("Fancy Font"), 0..0), 0, 1)
                .contains("font-family:Fancy Font"),
        )
    }

    @Test
    fun `relative size span becomes an em font-size`() {
        val out = buildInlineHtml(spanned("big", RelativeSizeSpan(1.5f), 0..2), 0, 3)
        assertEquals("<span style=\"font-size:1.50em\">big</span>", out)
    }

    @Test
    fun `background color span becomes a styled span`() {
        val out = buildInlineHtml(
            spanned("hl", BackgroundColorSpan(0xFF00FF00.toInt()), 0..1), 0, 2,
        )
        assertEquals("<span style=\"background-color:#00FF00\">hl</span>", out)
    }

    @Test
    fun `cid image span becomes a cid img tag and swallows its placeholder`() {
        val span = CidImageSpan(
            cid = "img1@inline",
            localUri = Uri.parse("file:///cache/pic.jpg"),
            drawable = ColorDrawable(Color.RED),
            mimeType = "image/jpeg",
            fileName = "pic.jpg",
        )
        val text = spanned("\uFFFC", span, 0..0)
        assertEquals("<div><img src=\"cid:img1@inline\"></div>", serializeEmailHtml(text))
    }

    @Test
    fun `hr marker char without a span is dropped, with a span becomes hr`() {
        // A leading replacement char starts its own slice and is skipped;
        // mid-text it rides along inside the surrounding slice.
        assertEquals("<div>a</div>", serializeEmailHtml(SpannableString("\uFFFCa")))
        val text = spanned("\uFFFC", HrSpan(), 0..0)
        assertEquals("<hr>", serializeEmailHtml(text))
    }

    @Test
    fun `newlines inside a paragraph become breaks`() {
        val out = buildInlineHtml(SpannableString("line1\nline2"), 0, 11)
        assertEquals("line1<br>line2", out)
    }

    @Test
    fun `stacked spans nest code innermost and color outermost`() {
        val s = SpannableString("x")
        s.setSpan(InlineCodeSpan(), 0, 1, 0)
        s.setSpan(android.text.style.ForegroundColorSpan(0xFFFF0000.toInt()), 0, 1, 0)
        val out = buildInlineHtml(s, 0, 1)
        assertTrue(out.startsWith("<span style=\"color:#FF0000\"><code"), out)
        assertTrue(out.endsWith("</code></span>"), out)
    }
}
