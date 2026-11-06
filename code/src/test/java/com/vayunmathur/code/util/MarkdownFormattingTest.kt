package com.vayunmathur.code.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unit tests for the pure preview/format helpers: Markdown → HTML and JSON/XML pretty-printing. */
class MarkdownFormattingTest {

    // ---- Markdown ----

    @Test
    fun headingConverts() {
        assertEquals("<h1>Title</h1>", markdownToHtml("# Title"))
        assertEquals("<h3>Sub</h3>", markdownToHtml("### Sub"))
    }

    @Test
    fun boldAndItalicConvert() {
        assertEquals("<p><strong>hi</strong></p>", markdownToHtml("**hi**"))
        assertEquals("<p><em>hi</em></p>", markdownToHtml("*hi*"))
    }

    @Test
    fun inlineCodeConverts() {
        assertEquals("<p>run <code>ls</code></p>", markdownToHtml("run `ls`"))
    }

    @Test
    fun linkConverts() {
        assertEquals("<p><a href=\"http://x\">t</a></p>", markdownToHtml("[t](http://x)"))
    }

    @Test
    fun unorderedListConverts() {
        assertEquals("<ul>\n<li>a</li>\n<li>b</li>\n</ul>", markdownToHtml("- a\n- b"))
    }

    @Test
    fun quoteConverts() {
        assertEquals("<blockquote>wise</blockquote>", markdownToHtml("> wise"))
    }

    @Test
    fun numberedListConverts() {
        assertEquals("<ol>\n<li>one</li>\n<li>two</li>\n</ol>", markdownToHtml("1. one\n2. two"))
    }

    @Test
    fun adjacentParagraphLinesJoinWithSpaces() {
        assertEquals("<p>first second</p>", markdownToHtml("first\nsecond"))
    }

    @Test
    fun fencedCodeEscapesHtml() {
        val html = markdownToHtml("```\n<a>\n```")
        assertEquals("<pre><code>&lt;a&gt;\n</code></pre>", html)
    }

    // ---- JSON ----

    @Test
    fun jsonPrettyPrints() {
        assertEquals("{\n  \"a\": 1\n}", formatJson("""{"a":1}"""))
    }

    @Test
    fun jsonKeepsStringsIntact() {
        val result = formatJson("""{"k":"a,b:c"}""")
        assertTrue(result!!.contains("\"a,b:c\""))
    }

    @Test
    fun jsonRejectsUnbalanced() {
        assertNull(formatJson("""{"a":1"""))
        assertNull(formatJson(""))
    }

    // ---- XML ----

    @Test
    fun xmlIndentsByNesting() {
        val result = formatXml("<a><b>x</b></a>")
        assertEquals("<a>\n  <b>\n    x\n  </b>\n</a>", result)
    }

    @Test
    fun xmlBlankIsNull() {
        assertNull(formatXml("   "))
    }
}
