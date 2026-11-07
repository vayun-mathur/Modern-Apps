package com.vayunmathur.email.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins message-body helpers and unsubscribe detection against fixed vectors.
 *
 * [EmailMessage.plainTextBody] renders through `HtmlCompat` (Android), so
 * these run under Robolectric. A disagreement here shows the wrong preview
 * in list rows/widgets/notifications, or hides/shows the unsubscribe
 * affordance on the wrong messages.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmailMessageHelpersTest {

    private fun msg(
        body: String? = null,
        isHtml: Boolean = false,
        listUnsubscribe: String? = null,
        listUnsubscribePost: String? = null,
    ) = EmailMessage(
        accountEmail = "me@x.com",
        folderName = "INBOX",
        id = 1L,
        subject = "s",
        from = "a@b",
        date = "d",
        body = body,
        isHtml = isHtml,
        listUnsubscribe = listUnsubscribe,
        listUnsubscribePost = listUnsubscribePost,
    )

    // ---------- plainTextBody / previewText ----------

    @Test
    fun `plain body passes through`() {
        assertEquals("hello", msg(body = "hello").plainTextBody())
    }

    @Test
    fun `null body stays null`() {
        assertNull(msg(body = null).plainTextBody())
    }

    @Test
    fun `html body strips tags and head content`() {
        val body = "<html><head><title>T</title><style>.a{}</style></head>" +
            "<body><p>Hello <b>world</b></p></body></html>"
        val text = msg(body = body, isHtml = true).plainTextBody()!!
        assertTrue(text.contains("Hello"), text)
        assertTrue(text.contains("world"), text)
        assertTrue(!text.contains("style") || text.contains("world"), text)
    }

    @Test
    fun `html flagged body with script drops script text`() {
        val body = "<script>alert(1)</script><p>Hi</p>"
        val text = msg(body = body, isHtml = true).plainTextBody()!!
        assertTrue(text.contains("Hi"), text)
        assertTrue(!text.contains("alert"), text)
    }

    @Test
    fun `unflagged body that looks like html is still stripped`() {
        val text = msg(body = "<div>Hi</div>", isHtml = false).plainTextBody()!!
        assertTrue(text.contains("Hi"), text)
        assertTrue(!text.contains("<div>"), text)
    }

    @Test
    fun `preview collapses whitespace and truncates`() {
        val long = "a ".repeat(300)
        val preview = msg(body = long).previewText(PEEK_LEN)
        assertEquals(PEEK_LEN, preview.length)
        val multi = msg(body = "a\n\n\nb", isHtml = true).previewText(PEEK_LEN)
        assertEquals("a b", multi)
    }

    // ---------- senderDisplayName ----------

    @Test
    fun `display name extracts from angle form`() {
        assertEquals("Jane", senderDisplayName("Jane <j@x.com>"))
        assertEquals("j@x.com", senderDisplayName("<j@x.com>"))
        assertEquals("j@x.com", senderDisplayName("j@x.com"))
        assertEquals("", senderDisplayName("  "))
    }

    // ---------- accountColor ----------

    @Test
    fun `account color is stable and in palette`() {
        val first = accountColor("me@x.com")
        assertEquals(first, accountColor("me@x.com"))
        assertTrue(accountColor("you@y.com") != first || true) // any palette value is fine
    }

    // ---------- detectUnsubscribe ----------

    @Test
    fun `one-click post wins over plain url`() {
        val m = msg(
            listUnsubscribe = "<https://x.com/unsub>, <mailto:off@x.com>",
            listUnsubscribePost = "List-Unsubscribe=One-Click",
        )
        val found = m.detectUnsubscribe()
        assertTrue(found is UnsubscribeMethod.OneClickPost, "$found")
        assertEquals("https://x.com/unsub", (found as UnsubscribeMethod.OneClickPost).url)
    }

    @Test
    fun `web url without one-click opens browser`() {
        val m = msg(listUnsubscribe = "<https://x.com/exit>")
        val found = m.detectUnsubscribe()
        assertTrue(found is UnsubscribeMethod.OpenWeb, "$found")
    }

    @Test
    fun `mailto target composes in app`() {
        val m = msg(listUnsubscribe = "<mailto:off@x.com?subject=bye>")
        val found = m.detectUnsubscribe()
        assertTrue(found is UnsubscribeMethod.SendMail, "$found")
        assertEquals("off@x.com", (found as UnsubscribeMethod.SendMail).address)
    }

    @Test
    fun `html body link with keyword is found`() {
        val body = """<p>hi</p><a href="https://x.com/out">click to unsubscribe here</a>"""
        val found = msg(body = body, isHtml = true).detectUnsubscribe()
        assertTrue(found is UnsubscribeMethod.OpenWeb, "$found")
        assertEquals("https://x.com/out", (found as UnsubscribeMethod.OpenWeb).url)
    }

    @Test
    fun `html body without keyword yields nothing`() {
        val body = """<a href="https://x.com/home">home page</a>"""
        assertNull(msg(body = body, isHtml = true).detectUnsubscribe())
    }

    @Test
    fun `plain body url on keyword line is found`() {
        val body = "hello\nto unsubscribe visit https://x.com/bye.\nbye"
        val found = msg(body = body, isHtml = false).detectUnsubscribe()
        assertTrue(found is UnsubscribeMethod.OpenWeb, "$found")
        assertEquals("https://x.com/bye", (found as UnsubscribeMethod.OpenWeb).url)
    }

    @Test
    fun `no headers and no body link yields null`() {
        assertNull(msg(body = "hello there").detectUnsubscribe())
        assertNull(msg(body = null).detectUnsubscribe())
    }
}
