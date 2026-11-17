package com.vayunmathur.flashcards.util
import com.vayunmathur.flashcards.domain.HtmlConvert

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HtmlConvertTest {

    @Test
    fun boldItalicHtmlToMarkdown() {
        assertEquals("**bold** and *italic*", HtmlConvert.htmlToMarkdown("<b>bold</b> and <i>italic</i>"))
    }

    @Test
    fun brBecomesNewline() {
        assertEquals("a\nb", HtmlConvert.htmlToMarkdown("a<br>b"))
    }

    @Test
    fun linkHtmlToMarkdown() {
        assertEquals(
            "[Google](https://g.com)",
            HtmlConvert.htmlToMarkdown("""<a href="https://g.com">Google</a>"""),
        )
    }

    @Test
    fun imageBecomesMarkdownImage() {
        assertEquals("![a cat](c.png)", HtmlConvert.htmlToMarkdown("""<img src="c.png" alt="a cat">"""))
    }

    @Test
    fun imageUsesBasenameOfSrc() {
        assertEquals("![](pic.jpg)", HtmlConvert.htmlToMarkdown("""<img src="media/sub/pic.jpg">"""))
    }

    @Test
    fun imageRoundTrip() {
        val md = "![a cat](c.png)"
        val html = HtmlConvert.markdownFieldToHtml(md)
        assertTrue(html.contains("<img src=\"c.png\""), "should produce an img tag: $html")
        assertEquals(md, HtmlConvert.htmlToMarkdown(html))
    }

    @Test
    fun soundTagRemoved() {
        assertEquals("hello", HtmlConvert.htmlToMarkdown("hello[sound:a.mp3]"))
    }

    @Test
    fun entitiesDecoded() {
        assertEquals("a < b & c", HtmlConvert.htmlToMarkdown("a &lt; b &amp; c"))
    }

    @Test
    fun markdownFieldToHtmlBold() {
        assertEquals("<b>hi</b>", HtmlConvert.markdownFieldToHtml("**hi**"))
    }

    @Test
    fun templatePreservesPlaceholders() {
        val html = HtmlConvert.markdownTemplateToHtml("{{Front}}\n\n**bold**")
        assertTrue(html.contains("{{Front}}"), "placeholder should be preserved: $html")
        assertTrue(html.contains("<b>bold</b>"), "markdown should convert: $html")
    }

    @Test
    fun boldRoundTrip() {
        val md = "This is **strong** and *soft*"
        val html = HtmlConvert.markdownFieldToHtml(md)
        assertEquals(md, HtmlConvert.htmlToMarkdown(html))
    }

    @Test
    fun clozePlaceholderPreservedInTemplate() {
        val html = HtmlConvert.markdownTemplateToHtml("{{cloze:Text}}")
        assertEquals("{{cloze:Text}}", html)
    }
}
