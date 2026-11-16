package com.vayunmathur.library.ui

import android.text.SpannableStringBuilder
import android.text.style.URLSpan
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * W4: [HtmlEditorController] link editing — creating a link from a
 * selection, reading the link context back at the caret, retargeting an
 * existing link, and refusing blank URLs.
 */
@RunWith(AndroidJUnit4::class)
class W4HtmlLinkTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun controller(text: String, selStart: Int = 0, selEnd: Int = text.length): HtmlEditorController {
        val c = HtmlEditorController()
        c.editText = EditText(context).apply {
            setText(SpannableStringBuilder(text))
            setSelection(selStart, selEnd)
        }
        // linkContext() reads the controller's tracked selection, which the
        // Compose editor normally keeps in sync with the EditText.
        c.selectionStart = selStart
        c.selectionEnd = selEnd
        return c
    }

    private fun urlSpans(c: HtmlEditorController): List<URLSpan> {
        val e = c.editText!!.text
        return e.getSpans(0, e.length, URLSpan::class.java).toList()
    }

    @Test
    fun `apply link to a selection and read it back`() {
        val c = controller("click here now", 6, 10)
        val before = c.linkContext()
        assertNotNull(before)
        assertEquals(false, before.editing)
        assertEquals("here", before.text)

        c.applyLink(before, "here", "https://example.org/page")
        assertEquals("click here now", c.editText!!.text.toString())
        val spans = urlSpans(c)
        assertEquals(1, spans.size)
        assertEquals("https://example.org/page", spans[0].url)

        // Caret inside the link now reports an editing context.
        c.editText!!.setSelection(8)
        c.selectionStart = 8
        c.selectionEnd = 8
        val after = c.linkContext()
        assertNotNull(after)
        assertEquals(true, after.editing)
        assertEquals("here", after.text)
        assertEquals("https://example.org/page", after.url)
    }

    @Test
    fun `retargeting an existing link replaces text and url`() {
        val c = controller("click here now", 6, 10)
        c.applyLink(LinkContext(editing = false, text = "here", url = ""), "here", "https://old.example")
        c.editText!!.setSelection(6, 10)
        c.selectionStart = 6
        c.selectionEnd = 10
        val ctx = c.linkContext()
        assertNotNull(ctx)
        assertTrue(ctx.editing)
        c.applyLink(ctx, "this page", "https://new.example")
        assertEquals("click this page now", c.editText!!.text.toString())
        val spans = urlSpans(c)
        assertEquals(1, spans.size)
        assertEquals("https://new.example", spans[0].url)
        val start = c.editText!!.text.getSpanStart(spans[0])
        val end = c.editText!!.text.getSpanEnd(spans[0])
        assertEquals("this page", c.editText!!.text.substring(start, end))
    }

    @Test
    fun `blank url and collapsed caret are no-ops`() {
        val c = controller("plain text", 0, 5)
        val ctx = c.linkContext()
        assertNotNull(ctx)
        c.applyLink(ctx, "plain", "")
        assertTrue(urlSpans(c).isEmpty())
        assertEquals("plain text", c.editText!!.text.toString())

        val collapsed = controller("plain text", 3, 3)
        assertNull(collapsed.linkContext())
    }
}
