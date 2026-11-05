package com.vayunmathur.appstore.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DescriptionMarkupTest {

    @Test
    fun `fastlane text keeps inline html and turns newlines and dashes into breaks and bullets`() {
        val raw = "<b>ColorBlendr</b> modifies colors.\n\n<b>Features:</b>\n\n- Accent\n- Background"
        assertEquals(
            "<b>ColorBlendr</b> modifies colors.<br><br><b>Features:</b><br><br>• Accent<br>• Background",
            DescriptionMarkup.toHtml(raw),
        )
    }

    @Test
    fun `markdown inline syntax is converted`() {
        assertEquals(
            "<b>bold</b> <i>it</i> <i>u</i> <s>gone</s> <tt>a&lt;b</tt> <a href=\"https://x.org\">site</a>",
            DescriptionMarkup.toHtml("**bold** *it* _u_ ~~gone~~ `a<b` [site](https://x.org)"),
        )
    }

    @Test
    fun `headings become bold lines and rules and images are dropped`() {
        assertEquals(
            "<b>Title</b><br><br>text",
            DescriptionMarkup.toHtml("## Title\n---\n![shot](https://x.org/a.png)text"),
        )
    }

    @Test
    fun `snake case identifiers are not italicised`() {
        assertEquals("QUERY_ALL_PACKAGES", DescriptionMarkup.toHtml("QUERY_ALL_PACKAGES"))
    }

    @Test
    fun `real html is passed through without adding breaks`() {
        val raw = "<p>One</p>\n<ul><li>Two</li></ul>"
        assertEquals(raw, DescriptionMarkup.toHtml(raw))
    }

    @Test
    fun `images comments and scripts are stripped`() {
        assertEquals(
            "<p>ok</p>",
            DescriptionMarkup.toHtml("<img src=\"a.png\"><!-- hi --><script>x()</script><p>ok</p>"),
        )
    }

    @Test
    fun `non web links lose their target`() {
        val html = DescriptionMarkup.toHtml(
            "<a href=\"intent://evil\">a</a> [b](market://details?id=x) <a href='https://ok.org'>c</a>",
        )
        assertFalse(html.contains("intent://"))
        assertFalse(html.contains("market://"))
        assertTrue(html.contains("<a>a</a>"))
        assertTrue(html.contains(" b "))
        assertTrue(html.contains("<a href='https://ok.org'>c</a>"))
    }

    @Test
    fun `autolinks become anchors`() {
        assertEquals(
            "see <a href=\"https://x.org\">https://x.org</a>",
            DescriptionMarkup.toHtml("see <https://x.org>"),
        )
    }
}
