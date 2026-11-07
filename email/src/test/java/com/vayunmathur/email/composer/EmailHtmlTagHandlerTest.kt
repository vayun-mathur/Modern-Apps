package com.vayunmathur.email.composer

import android.text.SpannableStringBuilder
import com.vayunmathur.email.ui.composer.EmailHtmlTagHandler
import com.vayunmathur.library.ui.EmailBlockQuoteSpan
import com.vayunmathur.library.ui.HeadingSpan
import com.vayunmathur.library.ui.HrSpan
import com.vayunmathur.library.ui.InlineCodeSpan
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xml.sax.Attributes
import org.xml.sax.ContentHandler
import org.xml.sax.DTDHandler
import org.xml.sax.EntityResolver
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.XMLReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives [EmailHtmlTagHandler] directly: when a draft is re-opened its rich
 * spans (headings, quotes, code, rules) must survive the HTML round-trip. A
 * dropped span silently flattens the draft's formatting, so each test asserts
 * the exact span restored over the exact range.
 *
 * The style-attribute path needs a real SAX parser; these tests pin the
 * tag-only path (headings/blockquote/code/hr), which is what survives on all
 * Android versions.
 */
@RunWith(RobolectricTestRunner::class)
class EmailHtmlTagHandlerTest {

    /** Minimal XMLReader: attribute extraction is best-effort and returns null. */
    private class StubReader : XMLReader {
        override fun getProperty(name: String): Any = throw org.xml.sax.SAXNotRecognizedException(name)
        override fun setProperty(name: String, value: Any) {}
        override fun setEntityResolver(resolver: EntityResolver?) {}
        override fun getEntityResolver(): EntityResolver? = null
        override fun setDTDHandler(handler: DTDHandler?) {}
        override fun getDTDHandler(): DTDHandler? = null
        override fun setContentHandler(handler: ContentHandler?) {}
        override fun getContentHandler(): ContentHandler? = null
        override fun setErrorHandler(handler: ErrorHandler?) {}
        override fun getErrorHandler(): ErrorHandler? = null
        override fun parse(input: InputSource?) {}
        override fun parse(systemId: String?) {}
        override fun getFeature(name: String): Boolean = false
        override fun setFeature(name: String, value: Boolean) {}
    }

    private val reader = StubReader()

    private fun spanned(block: EmailHtmlTagHandler.(SpannableStringBuilder) -> Unit): SpannableStringBuilder {
        val handler = EmailHtmlTagHandler()
        val out = SpannableStringBuilder()
        handler.block(out)
        return out
    }

    @Test
    fun `h1 close restores a level-1 heading span`() {
        val out = spanned {
            it.append("Title")
            handleTag(true, "h1", it, reader)
            it.append("Title")
            handleTag(false, "h1", it, reader)
        }
        val spans = out.getSpans(0, out.length, HeadingSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals(1, spans.single().level)
    }

    @Test
    fun `h2 and h3 restore their levels`() {
        val out = spanned {
            handleTag(true, "h2", it, reader)
            it.append("sub")
            handleTag(false, "h2", it, reader)
            handleTag(true, "h3", it, reader)
            it.append("sub3")
            handleTag(false, "h3", it, reader)
        }
        val levels = out.getSpans(0, out.length, HeadingSpan::class.java).map { it.level }.sorted()
        assertEquals(listOf(2, 3), levels)
    }

    @Test
    fun `blockquote close restores the quote span`() {
        val out = spanned {
            handleTag(true, "blockquote", it, reader)
            it.append("quoted words")
            handleTag(false, "blockquote", it, reader)
        }
        val spans = out.getSpans(0, out.length, EmailBlockQuoteSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals(0, out.getSpanStart(spans.single()))
        assertEquals(out.length, out.getSpanEnd(spans.single()))
    }

    @Test
    fun `code close restores the inline code span`() {
        val out = spanned {
            it.append("run ")
            handleTag(true, "code", it, reader)
            it.append("x()")
            handleTag(false, "code", it, reader)
        }
        val spans = out.getSpans(0, out.length, InlineCodeSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals(4, out.getSpanStart(spans.single()))
    }

    @Test
    fun `hr open inserts an object replacement tagged with HrSpan`() {
        val out = spanned {
            it.append("before")
            handleTag(true, "hr", it, reader)
        }
        assertTrue(out.contains("\uFFFC"), out.toString())
        val spans = out.getSpans(0, out.length, HrSpan::class.java)
        assertEquals(1, spans.size)
    }

    @Test
    fun `unmatched close tags are ignored`() {
        val out = spanned {
            it.append("text")
            handleTag(false, "h1", it, reader)
            handleTag(false, "blockquote", it, reader)
            handleTag(false, "code", it, reader)
        }
        assertEquals("text", out.toString())
        assertTrue(out.getSpans(0, out.length, HeadingSpan::class.java).isEmpty())
    }

    @Test
    fun `tag names are case-insensitive`() {
        val out = spanned {
            handleTag(true, "H1", it, reader)
            it.append("Title")
            handleTag(false, "H1", it, reader)
        }
        assertEquals(1, out.getSpans(0, out.length, HeadingSpan::class.java).size)
    }

    /** Reader whose property lookup yields attributes carrying [style]. */
    private class StyledReader(private val style: String) : XMLReader {
        private val attrs = object : Attributes {
            override fun getLength(): Int = 1
            override fun getURI(index: Int): String = ""
            override fun getLocalName(index: Int): String = "style"
            override fun getQName(index: Int): String = "style"
            override fun getType(index: Int): String = "CDATA"
            override fun getValue(index: Int): String = style
            override fun getIndex(uri: String, localName: String): Int = if (localName == "style") 0 else -1
            override fun getIndex(qName: String): Int = if (qName == "style") 0 else -1
            override fun getType(uri: String, localName: String): String? = null
            override fun getType(qName: String): String? = null
            override fun getValue(uri: String, localName: String): String? = null
            override fun getValue(qName: String): String? = if (qName == "style") style else null
        }

        override fun getProperty(name: String): Any = attrs
        override fun setProperty(name: String, value: Any) {}
        override fun setEntityResolver(resolver: EntityResolver?) {}
        override fun getEntityResolver(): EntityResolver? = null
        override fun setDTDHandler(handler: DTDHandler?) {}
        override fun getDTDHandler(): DTDHandler? = null
        override fun setContentHandler(handler: ContentHandler?) {}
        override fun getContentHandler(): ContentHandler? = null
        override fun setErrorHandler(handler: ErrorHandler?) {}
        override fun getErrorHandler(): ErrorHandler? = null
        override fun parse(input: InputSource?) {}
        override fun parse(systemId: String?) {}
        override fun getFeature(name: String): Boolean = false
        override fun setFeature(name: String, value: Boolean) {}
    }

    private fun styled(style: String, block: EmailHtmlTagHandler.(SpannableStringBuilder) -> Unit): SpannableStringBuilder {
        val handler = EmailHtmlTagHandler()
        val out = SpannableStringBuilder()
        handler.block(out)
        return out
    }

    @Test
    fun `span color and background restore color spans`() {
        val styleReader = StyledReader("color:#FF0000; background-color:#00FF00")
        val out = styled("color:#FF0000; background-color:#00FF00") {
            handleTag(true, "span", it, styleReader)
            it.append("colored")
            handleTag(false, "span", it, styleReader)
        }
        val fg = out.getSpans(0, out.length, android.text.style.ForegroundColorSpan::class.java)
        val bg = out.getSpans(0, out.length, android.text.style.BackgroundColorSpan::class.java)
        assertEquals(1, fg.size)
        assertEquals(android.graphics.Color.rgb(255, 0, 0), fg.single().foregroundColor)
        assertEquals(1, bg.size)
        assertEquals(android.graphics.Color.rgb(0, 255, 0), bg.single().backgroundColor)
    }

    @Test
    fun `span font size and family restore spans, rgb color parses`() {
        val styleReader = StyledReader("font-size:1.5em; font-family:'serif'; color:rgb(1,2,3)")
        val out = styled("font-size:1.5em; font-family:'serif'; color:rgb(1,2,3)") {
            handleTag(true, "span", it, styleReader)
            it.append("sized")
            handleTag(false, "span", it, styleReader)
        }
        val sizes = out.getSpans(0, out.length, android.text.style.RelativeSizeSpan::class.java)
        assertEquals(1, sizes.size)
        assertEquals(1.5f, sizes.single().sizeChange)
        val families = out.getSpans(0, out.length, com.vayunmathur.library.ui.FontFamilySpan::class.java)
        assertEquals(1, families.size)
        assertEquals("serif", families.single().familyName)
        val fg = out.getSpans(0, out.length, android.text.style.ForegroundColorSpan::class.java)
        assertEquals(android.graphics.Color.rgb(1, 2, 3), fg.single().foregroundColor)
    }

    @Test
    fun `div text align restores alignment span and invalid align does not`() {
        val centerReader = StyledReader("text-align:center")
        val out = styled("text-align:center") {
            handleTag(true, "div", it, centerReader)
            it.append("centered")
            handleTag(false, "div", it, centerReader)
        }
        val aligns = out.getSpans(0, out.length, com.vayunmathur.library.ui.EmailAlignmentSpan::class.java)
        assertEquals(1, aligns.size)
        assertEquals("center", aligns.single().cssAlign)

        val bogusReader = StyledReader("text-align:sideways")
        val out2 = styled("text-align:sideways") {
            handleTag(true, "div", it, bogusReader)
            it.append("plain")
            handleTag(false, "div", it, bogusReader)
        }
        assertTrue(out2.getSpans(0, out2.length, com.vayunmathur.library.ui.EmailAlignmentSpan::class.java).isEmpty())
    }

    @Test
    fun `empty range close applies no span`() {
        val out = spanned {
            handleTag(true, "code", it, reader)
            handleTag(false, "code", it, reader)
        }
        assertTrue(out.getSpans(0, out.length, InlineCodeSpan::class.java).isEmpty())
    }
}
