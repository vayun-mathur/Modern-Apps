package com.vayunmathur.email.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the pure message-model helpers in EmailModels: preview snippets,
 * sender names, colors and unsubscribe detection feed the list rows, the
 * widget and notifications. A wrong snippet shows blank rows; a wrong method
 * fires the wrong unsubscribe flow.
 */
class EmailModelHelpersTest {

    private fun msg(
        body: String? = "Hello world",
        isHtml: Boolean = false,
        from: String = "Friend <friend@x.com>",
        listUnsubscribe: String? = null,
        listUnsubscribePost: String? = null,
    ) = EmailMessage(
        accountEmail = "me@x.com", folderName = "INBOX", id = 1L,
        subject = "s", from = from, date = "d",
        body = body, isHtml = isHtml,
        listUnsubscribe = listUnsubscribe,
        listUnsubscribePost = listUnsubscribePost,
    )

    @Test
    fun `preview text collapses whitespace and truncates`() {
        val m = msg(body = "line1\n\n   line2   line3")
        assertEquals("line1 line2 line3", m.previewText(200))
        assertEquals("line1 lin", m.previewText(9))
    }

    @Test
    fun `preview text of null body is empty`() {
        assertEquals("", msg(body = null).previewText(200))
    }

    @Test
    fun `sender display name prefers the quoted name`() {
        assertEquals("Friend", senderDisplayName("Friend <friend@x.com>"))
        assertEquals("Ada", senderDisplayName("\"Ada\" <ada@x.com>"))
    }

    @Test
    fun `sender display name falls back to bare address`() {
        assertEquals("friend@x.com", senderDisplayName("friend@x.com"))
        assertEquals("", senderDisplayName("  "))
    }

    @Test
    fun `one-click post wins when the post header is present`() {
        val method = msg(
            listUnsubscribe = "<https://x.com/unsub>",
            listUnsubscribePost = "List-Unsubscribe=One-Click",
        ).detectUnsubscribe()
        assertTrue(method is UnsubscribeMethod.OneClickPost, method.toString())
        assertEquals("https://x.com/unsub", (method as UnsubscribeMethod.OneClickPost).url)
    }

    @Test
    fun `https without one-click opens the web page`() {
        val method = msg(listUnsubscribe = "<https://x.com/unsub>").detectUnsubscribe()
        assertTrue(method is UnsubscribeMethod.OpenWeb, method.toString())
        assertEquals("https://x.com/unsub", (method as UnsubscribeMethod.OpenWeb).url)
    }

    @Test
    fun `mailto target composes an unsubscribe mail`() {
        val method = msg(listUnsubscribe = "<mailto:leave@x.com?subject=bye>").detectUnsubscribe()
        assertTrue(method is UnsubscribeMethod.SendMail, method.toString())
        assertEquals("leave@x.com", (method as UnsubscribeMethod.SendMail).address)
    }

    @Test
    fun `https wins over mailto when both are listed`() {
        val method = msg(
            listUnsubscribe = "<https://x.com/u>, <mailto:leave@x.com>",
            listUnsubscribePost = "List-Unsubscribe=One-Click",
        ).detectUnsubscribe()
        assertTrue(method is UnsubscribeMethod.OneClickPost, method.toString())
    }

    @Test
    fun `no headers and no body link yields null`() {
        assertNull(msg(listUnsubscribe = null, body = "Hello world").detectUnsubscribe())
        assertNull(msg(listUnsubscribe = "  ", body = "Hello world").detectUnsubscribe())
    }

    @Test
    fun `account color is stable per address`() {
        assertEquals(accountColor("me@x.com"), accountColor("me@x.com"))
    }
}
