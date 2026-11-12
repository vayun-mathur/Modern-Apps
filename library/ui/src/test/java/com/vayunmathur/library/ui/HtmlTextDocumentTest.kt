package com.vayunmathur.library.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The document [HtmlText] wraps around a mail body, tested as a pure string
 * transform: default output keeps the historical wrapper for existing callers,
 * and fit-to-width adds the width caps, the zoomable viewport and drops the
 * sender's own viewport tag (#900).
 */
class HtmlTextDocumentTest {

    private val light = HtmlTextDocumentSpec(backgroundHex = "#FFFFFF", textHex = "#111111", isDark = false)
    private val newsletter =
        "<meta name=\"viewport\" content=\"width=600\">" +
            "<table width=\"600\" style=\"width:600px;min-width:600px\"><tr><td>Deal of the week</td></tr></table>"

    @Test
    fun `default document keeps the fixed device-width viewport and no fit css`() {
        val doc = HtmlTextDocument.build("<p>hi</p>", light)
        assertTrue(doc.contains("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"), doc)
        assertFalse(doc.contains("overflow-wrap: anywhere"), doc)
        assertFalse(doc.contains("user-scalable"), doc)
        assertTrue(doc.contains("<body><p>hi</p></body>"), doc)
    }

    @Test
    fun `default document leaves a sender viewport tag alone`() {
        val doc = HtmlTextDocument.build(newsletter, light)
        assertTrue(doc.contains("content=\"width=600\""), doc)
    }

    @Test
    fun `fit to width caps fixed-width containers and lets long links break`() {
        val doc = HtmlTextDocument.build(newsletter, light.copy(fitContentToWidth = true))
        assertTrue(doc.contains(HtmlTextDocument.FIT_TO_WIDTH_CSS.trim()), doc)
        assertTrue(doc.contains("table, td, th, div { min-width: 0 !important; }"), doc)
        assertTrue(doc.contains("max-width: 100% !important"), doc)
        assertTrue(doc.contains("overflow-wrap: anywhere"), doc)
        // The body itself is untouched apart from the viewport tag.
        assertTrue(doc.contains("<table width=\"600\" style=\"width:600px;min-width:600px\">"), doc)
    }

    @Test
    fun `fit to width opens at 1 to 1 so text stays readable`() {
        val doc = HtmlTextDocument.build(newsletter, light.copy(fitContentToWidth = true))
        assertTrue(doc.contains("<meta name=\"viewport\" content=\"${HtmlTextDocument.FIT_VIEWPORT}\">"), doc)
        assertTrue(HtmlTextDocument.FIT_VIEWPORT.contains("width=device-width"))
        assertTrue(HtmlTextDocument.FIT_VIEWPORT.contains("initial-scale=1,"))
        assertTrue(doc.contains("font-size: 14px"), doc)
    }

    @Test
    fun `fit to width drops the sender viewport tag`() {
        val doc = HtmlTextDocument.build(newsletter, light.copy(fitContentToWidth = true))
        assertFalse(doc.contains("width=600\""), doc)
        assertEquals(1, Regex("name=\"viewport\"").findAll(doc).count(), doc)
    }

    @Test
    fun `sender viewport stripping handles case, quoting and attribute order`() {
        val html = "<META content='width=640' NAME='Viewport'/><p>x</p><meta name=viewport content=width=700>" +
            "<meta name=\"description\" content=\"keep me\">"
        assertEquals(
            "<p>x</p><meta name=\"description\" content=\"keep me\">",
            HtmlTextDocument.stripSenderViewport(html),
        )
    }

    @Test
    fun `fit to width applies in dark mode too`() {
        val doc = HtmlTextDocument.build(newsletter, light.copy(isDark = true, fitContentToWidth = true))
        assertTrue(doc.contains("filter: invert(1) hue-rotate(180deg);"), doc)
        assertTrue(doc.contains("overflow-wrap: anywhere"), doc)
    }

    @Test
    fun `quotes and csp still compose with fit to width`() {
        val spec = light.copy(hideQuotes = true, blockRemoteContent = true, fitContentToWidth = true)
        val doc = HtmlTextDocument.build("<p>hi</p>", spec)
        assertTrue(doc.contains(".gmail_quote"), doc)
        assertTrue(doc.contains("Content-Security-Policy"), doc)
        assertTrue(doc.contains("overflow-wrap: anywhere"), doc)
    }
}
