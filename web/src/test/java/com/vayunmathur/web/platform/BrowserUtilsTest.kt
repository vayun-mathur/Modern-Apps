package com.vayunmathur.web.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Only the navigation paths are exercised here. The search fallback goes through
 * `Uri.encode`, which is an unimplemented stub in a JVM unit test, so the "this is a search"
 * cases are asserted through [BrowserUtils.looksLikeUrl] instead.
 */
class BrowserUtilsTest {

    @Test
    fun `LAN-looking input navigates over http`() {
        assertEquals("http://nas.local", BrowserUtils.toNavigationUrl("nas.local"))
        assertEquals("http://192.168.1.1:8080", BrowserUtils.toNavigationUrl("192.168.1.1:8080"))
        assertEquals("http://localhost:3000", BrowserUtils.toNavigationUrl("localhost:3000"))
    }

    @Test
    fun `public input navigates over https`() {
        assertEquals("https://example.com", BrowserUtils.toNavigationUrl("example.com"))
        assertEquals("https://example.com/a?b=1", BrowserUtils.toNavigationUrl("example.com/a?b=1"))
    }

    @Test
    fun `an explicit scheme is kept verbatim`() {
        assertEquals("http://router", BrowserUtils.toNavigationUrl("http://router"))
        assertEquals("http://example.com/", BrowserUtils.toNavigationUrl("http://example.com/"))
    }

    @Test
    fun `input that is not URL-like falls through to search`() {
        assertFalse(BrowserUtils.looksLikeUrl("router"))
        assertFalse(BrowserUtils.looksLikeUrl("hello world"))
        assertTrue(BrowserUtils.looksLikeUrl("nas.local"))
        assertTrue(BrowserUtils.looksLikeUrl("192.168.1.1"))
    }

    @Test
    fun `uppercase scheme is still a URL`() {
        assertTrue(BrowserUtils.looksLikeUrl("HTTP://example.com/"))
        assertTrue(BrowserUtils.looksLikeUrl("Https://Example.COM/a?b=1"))
    }

    @Test
    fun `uppercase explicit scheme navigates verbatim`() {
        assertEquals("HTTP://example.com/", BrowserUtils.toNavigationUrl("HTTP://example.com/"))
        assertEquals("HTTPS://example.com/a", BrowserUtils.toNavigationUrl("HTTPS://example.com/a"))
    }

    @Test
    fun `only http, https and about blank may reach WebView loadUrl`() {
        assertTrue(BrowserUtils.canLoadInWebView("https://example.com/"))
        assertTrue(BrowserUtils.canLoadInWebView("http://nas.local/"))
        assertTrue(BrowserUtils.canLoadInWebView("HTTP://example.com/"))
        assertTrue(BrowserUtils.canLoadInWebView("about:blank"))
        assertTrue(BrowserUtils.canLoadInWebView("ABOUT:BLANK"))
    }

    @Test
    fun `programmatic javascript and exotic schemes never reach WebView loadUrl`() {
        assertFalse(BrowserUtils.canLoadInWebView("javascript:alert(1)"))
        assertFalse(BrowserUtils.canLoadInWebView("JaVaScRiPt:alert(1)"))
        assertFalse(BrowserUtils.canLoadInWebView("   javascript:alert(1)"))
        assertFalse(BrowserUtils.canLoadInWebView("data:text/html,<h1>x</h1>"))
        assertFalse(BrowserUtils.canLoadInWebView("blob:https://example.com/uuid"))
        assertFalse(BrowserUtils.canLoadInWebView("file:///etc/hosts"))
        assertFalse(BrowserUtils.canLoadInWebView("content://media/external/images/1"))
        assertFalse(BrowserUtils.canLoadInWebView("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(BrowserUtils.canLoadInWebView("tel:+15551234567"))
        assertFalse(BrowserUtils.canLoadInWebView("search://results?q=weather"))
        assertFalse(BrowserUtils.canLoadInWebView(""))
    }

    @Test
    fun `launch extractor passes clean web URLs through`() {
        assertEquals("https://example.com/app", BrowserUtils.extractHttpUrl("https://example.com/app"))
        assertEquals("HTTP://example.com/", BrowserUtils.extractHttpUrl("  HTTP://example.com/  "))
        assertEquals(
            "https://example.com/page",
            BrowserUtils.extractHttpUrl("check this out https://example.com/page"),
        )
    }

    @Test
    fun `launch extractor rejects non-web launch strings`() {
        assertNull(BrowserUtils.extractHttpUrl(""))
        assertNull(BrowserUtils.extractHttpUrl("   "))
        assertNull(BrowserUtils.extractHttpUrl("javascript:alert(1)"))
        assertNull(BrowserUtils.extractHttpUrl("JaVaScRiPt:alert(1)"))
        assertNull(BrowserUtils.extractHttpUrl("file:///etc/hosts"))
        assertNull(BrowserUtils.extractHttpUrl("data:text/html,<h1>x</h1>"))
        assertNull(BrowserUtils.extractHttpUrl("just some words"))
    }
}
