package com.vayunmathur.library.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Markdown to email-HTML conversion: blocks, inline spans, lists and escaping.
 * Slice-1 coverage for library/ui (converters).
 */
class MarkdownToHtmlSlice1Test {

    @Test
    fun `headings map to h-tags`() {
        assertTrue(markdownToHtml("# Title").contains("<h1>Title</h1>"))
        assertTrue(markdownToHtml("### Deep").contains("<h3>Deep</h3>"))
    }

    @Test
    fun `a paragraph wraps in p-tags`() {
        assertTrue(markdownToHtml("Hello world").contains("<p>Hello world</p>"))
    }

    @Test
    fun `inline spans convert`() {
        val html = markdownToHtml("**bold** and *italic* and `code` and ~~gone~~")
        assertTrue(html.contains("<b>bold</b>"), html)
        assertTrue(html.contains("<i>italic</i>"), html)
        assertTrue(html.contains("<code>code</code>"), html)
        assertTrue(html.contains("<s>gone</s>"), html)
    }

    @Test
    fun `links convert to anchors`() {
        val html = markdownToHtml("[Docs](https://example.com)")
        assertTrue(html.contains("<a href=\"https://example.com\">Docs</a>"), html)
    }

    @Test
    fun `html is escaped before conversion`() {
        val html = markdownToHtml("<b>not a tag</b>")
        assertTrue(html.contains("&lt;b&gt;"), html)
    }

    @Test
    fun `bullets group into one list`() {
        val html = markdownToHtml("- one\n- two")
        assertEquals(1, Regex("<ul>").findAll(html).count())
        assertEquals(2, Regex("<li>").findAll(html).count())
    }

    @Test
    fun `numbered items group into an ordered list`() {
        val html = markdownToHtml("1. one\n2. two")
        assertTrue(html.contains("<ol>"), html)
    }

    @Test
    fun `checkboxes render disabled inputs`() {
        val html = markdownToHtml("- [x] done\n- [ ] todo")
        assertTrue(html.contains("checked"), html)
        assertTrue(html.contains("type=\"checkbox\""), html)
    }

    @Test
    fun `a fenced block becomes pre-code with escaped content`() {
        val html = markdownToHtml("```\n<a>&\n```")
        assertTrue(html.contains("<pre><code>"), html)
        assertTrue(html.contains("&lt;a&gt;&amp;"), html)
    }

    @Test
    fun `quotes and rules convert`() {
        assertTrue(markdownToHtml("> cited").contains("<blockquote>cited</blockquote>"))
        assertTrue(markdownToHtml("---").contains("<hr>"))
    }

    @Test
    fun `blank lines break`() {
        assertTrue(markdownToHtml("a\n\nb").contains("<br>"))
    }

    @Test
    fun `the document is a full html shell`() {
        val html = markdownToHtml("hi")
        assertTrue(html.startsWith("<html><body>"))
        assertTrue(html.endsWith("</body></html>"))
    }
}
