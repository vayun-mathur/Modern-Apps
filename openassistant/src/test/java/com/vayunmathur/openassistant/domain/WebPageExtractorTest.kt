package com.vayunmathur.openassistant.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** On-device extraction + URL policy against fixtures (no live network). */
class WebPageExtractorTest {

    private val samplePage = """
        <!doctype html>
        <html><head>
        <title>  Sample Page Title </title>
        <meta property="og:title" content="OG Title Should Not Win">
        <style>body { color: red; }</style>
        <script>var secret = "scripttext";</script>
        </head><body>
        <nav><p>Navigation chrome</p></nav>
        <header><p>Header chrome</p></header>
        <main>
        <h1>Heading One</h1>
        <p>First paragraph of readable text.</p>
        <p>Second   paragraph
        with whitespace.</p>
        <ul><li>List item one</li><li>List item two</li></ul>
        <script>evil()</script>
        </main>
        <footer><p>Footer chrome</p></footer>
        </body></html>
    """.trimIndent()

    @Test fun extractsTitleAndMainText() {
        val page = WebPageExtractor.extract(samplePage)
        assertEquals("Sample Page Title", page.title)
        assertTrue(page.text.contains("Heading One"), page.text)
        assertTrue(page.text.contains("First paragraph of readable text."))
        assertTrue(page.text.contains("Second paragraph with whitespace."))
        assertTrue(page.text.contains("List item one"))
        assertFalse(page.text.contains("Navigation chrome"), page.text)
        assertFalse(page.text.contains("Header chrome"), page.text)
        assertFalse(page.text.contains("Footer chrome"), page.text)
        assertFalse(page.text.contains("scripttext"), page.text)
        assertFalse(page.text.contains("evil"), page.text)
    }

    @Test fun titleFallsBackToOgThenH1() {
        val og = """<html><head><meta property="og:title" content="OG Wins"></head>
            <body><main><p>body text here</p></main></body></html>"""
        assertEquals("OG Wins", WebPageExtractor.extract(og).title)
        val h1 = "<html><head></head><body><h1>H1 Wins</h1><p>x</p></body></html>"
        assertEquals("H1 Wins", WebPageExtractor.extract(h1).title)
    }

    @Test fun decodesEntitiesAndStripsInlineTags() {
        val html = "<html><head><title>T &amp; C</title></head><body><main>" +
            "<p>Tom &amp; Jerry &lt;3 &#8212; <b>bold</b> text</p></main></body></html>"
        val page = WebPageExtractor.extract(html)
        assertEquals("T & C", page.title)
        assertTrue(page.text.contains("Tom & Jerry <3 — bold text"), page.text)
    }

    @Test fun plainTextUsesFirstLineAsTitle() {
        val page = WebPageExtractor.extractPlainText("Hello title\n\nbody line")
        assertEquals("Hello title", page.title)
        assertTrue(page.text.contains("body line"))
    }

    @Test fun urlPolicyAllowsPublicAndBlocksLocal() {
        assertTrue(WebReadUrlPolicy.validate("https://example.com/page") is UrlCheck.Ok)
        assertTrue(WebReadUrlPolicy.validate("http://example.com/") is UrlCheck.Ok)
        assertTrue(WebReadUrlPolicy.validate("https://8.8.8.8/") is UrlCheck.Ok)
        for (bad in listOf(
            "ftp://example.com/", "file:///etc/passwd", "https://127.0.0.1/admin",
            "https://10.1.2.3/", "https://192.168.1.1/", "https://172.16.0.5/",
            "https://169.254.169.254/latest", "https://[::1]/", "https://localhost:8080/",
            "https://printer.local/", "not a url", "",
        )) {
            assertTrue(WebReadUrlPolicy.validate(bad) is UrlCheck.Refused, bad)
        }
    }
}
