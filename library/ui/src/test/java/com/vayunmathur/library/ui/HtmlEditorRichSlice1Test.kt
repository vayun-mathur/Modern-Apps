package com.vayunmathur.library.ui

import android.text.SpannableStringBuilder
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * The rich editor hooks: headings, alignment, quotes, code, colours, links.
 * Slice-1 coverage for library/ui (Html editors). Robolectric for EditText.
 */
@RunWith(AndroidJUnit4::class)
class HtmlEditorRichSlice1Test {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun controller(text: String): HtmlEditorController {
        val c = HtmlEditorController()
        c.editText = EditText(context).apply {
            setText(SpannableStringBuilder(text))
            setSelection(0, text.length)
        }
        return c
    }

    @Test
    fun `headings set and report their level`() {
        val c = controller("Title")
        c.toggleHeading(2)
        assertEquals(2, c.getCurrentHeadingLevel())
        c.toggleHeading(null)
        assertNull(c.getCurrentHeadingLevel())
        // Out-of-range levels are ignored.
        c.toggleHeading(9)
        assertNull(c.getCurrentHeadingLevel())
    }

    @Test
    fun `alignment sets and reads back`() {
        val c = controller("para")
        c.setAlignment("center")
        assertEquals("center", c.getCurrentAlignment())
        c.setAlignment("left")
        assertNull(c.getCurrentAlignment())
    }

    @Test
    fun `blockquotes toggle`() {
        val c = controller("quoted")
        c.toggleBlockquote()
        assertTrue(c.isBlockquoteActive())
        c.toggleBlockquote()
        assertFalse(c.isBlockquoteActive())
    }

    @Test
    fun `inline code toggles`() {
        val c = controller("code")
        c.toggleInlineCode()
        assertTrue(c.isInlineCodeActive())
        c.toggleInlineCode()
        assertFalse(c.isInlineCodeActive())
    }

    @Test
    fun `text and highlight colours set and clear`() {
        val c = controller("coloured")
        c.setTextColor(0xFFFF0000.toInt())
        assertEquals(0xFFFF0000.toInt(), c.getCurrentTextColor())
        c.setTextColor(null)
        assertNull(c.getCurrentTextColor())
        c.setHighlight(0xFFFFFF00.toInt())
        assertEquals(0xFFFFFF00.toInt(), c.getCurrentHighlightColor())
        c.setHighlight(null)
        assertNull(c.getCurrentHighlightColor())
    }

    @Test
    fun `font size and family set and clear`() {
        val c = controller("styled")
        c.setFontSizeFactor(1.5f)
        assertEquals(1.5f, c.getCurrentFontSizeFactor())
        c.setFontSizeFactor(null)
        assertNull(c.getCurrentFontSizeFactor())
        c.setFontFamily("serif")
        assertEquals("serif", c.getCurrentFontFamily())
        c.setFontFamily(null)
        assertNull(c.getCurrentFontFamily())
    }

    @Test
    fun `links apply to a selection and report context`() {
        val c = controller("click here")
        // Select "here" (6..10): context offers a fresh link for it.
        c.editText!!.setSelection(6, 10)
        c.updateSelection(6, 10)
        val ctx = c.linkContext()
        assertTrue(ctx != null && !ctx.editing, "ctx=$ctx")
        assertEquals("here", ctx.text)
        c.applyLink(ctx, "here", "https://example.com")
        assertTrue(c.editText!!.text.toString().contains("here"))
        // A blank URL is a no-op.
        c.applyLink(LinkContext(editing = false, text = "x", url = ""), "x", "  ")
    }

    @Test
    fun `clear formatting drops everything`() {
        val c = controller("rich")
        c.toggleBold()
        c.toggleHeading(1)
        c.setTextColor(0xFFFF0000.toInt())
        c.clearFormatting()
        assertFalse(c.isActive(EditorFormat.BOLD))
        assertNull(c.getCurrentHeadingLevel())
        assertNull(c.getCurrentTextColor())
        assertEquals("rich", c.editText!!.text.toString())
    }

    @Test
    fun `a horizontal rule inserts`() {
        val c = controller("before")
        c.editText!!.setSelection(6, 6)
        c.insertHorizontalRule()
        assertTrue(c.editText!!.text.length > "before".length)
    }
}
