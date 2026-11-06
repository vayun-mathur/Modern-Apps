package com.vayunmathur.library.ui

import android.text.SpannableStringBuilder
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * The rich-text editor controller core: char spans, lists, indent and state.
 * Slice-1 coverage for library/ui (Html editors). Needs a framework context
 * for [EditText], so Robolectric.
 */
@RunWith(AndroidJUnit4::class)
class HtmlEditorControllerSlice1Test {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun controller(text: String): HtmlEditorControllerCore {
        val c = HtmlEditorControllerCore()
        c.editText = EditText(context).apply {
            setText(SpannableStringBuilder(text))
            setSelection(0, text.length)
        }
        return c
    }

    private fun spansOf(c: HtmlEditorControllerCore): String =
        c.editText!!.text.toString()

    @Test
    fun `bold toggles on and off`() {
        val c = controller("hello")
        c.toggleBold()
        assertTrue(c.isActive(EditorFormat.BOLD))
        c.toggleBold()
        assertFalse(c.isActive(EditorFormat.BOLD))
        assertEquals("hello", spansOf(c))
    }

    @Test
    fun `italic underline and strike toggle`() {
        val c = controller("hello")
        c.toggleItalic()
        assertTrue(c.isActive(EditorFormat.ITALIC))
        c.toggle(EditorFormat.UNDERLINE)
        assertTrue(c.isActive(EditorFormat.UNDERLINE))
        c.toggle(EditorFormat.STRIKETHROUGH)
        assertTrue(c.isActive(EditorFormat.STRIKETHROUGH))
    }

    @Test
    fun `toggle dispatches every format`() {
        val c = controller("hello")
        for (f in listOf(EditorFormat.BOLD, EditorFormat.ITALIC, EditorFormat.UNDERLINE, EditorFormat.STRIKETHROUGH)) {
            c.toggle(f)
            assertTrue(c.isActive(f), "$f")
        }
        // LINK has no toggle behaviour.
        c.toggle(EditorFormat.LINK)
    }

    @Test
    fun `bullets toggle on and off`() {
        val c = controller("one\ntwo")
        c.toggleBullet()
        assertTrue(c.isActive(EditorFormat.BULLET))
        c.toggleBullet()
        assertFalse(c.isActive(EditorFormat.BULLET))
    }

    @Test
    fun `ordered lists toggle and renumber`() {
        val c = controller("one\ntwo")
        c.toggleOrderedList()
        assertTrue(c.isActive(EditorFormat.ORDERED_LIST))
        c.toggleOrderedList()
        assertFalse(c.isActive(EditorFormat.ORDERED_LIST))
    }

    @Test
    fun `indent increases and decreases`() {
        val c = controller("para")
        c.toggle(EditorFormat.INDENT)
        assertTrue(c.isActive(EditorFormat.INDENT))
        c.toggle(EditorFormat.OUTDENT)
        assertFalse(c.isActive(EditorFormat.INDENT))
        assertFalse(c.isActive(EditorFormat.OUTDENT))
    }

    @Test
    fun `selection and focus state round-trip`() {
        val c = controller("hi")
        c.updateSelection(0, 1)
        assertEquals(0, c.currentSelectionStart)
        assertEquals(1, c.currentSelectionEnd)
        c.updateFocus(true)
        assertTrue(c.isFocused)
        val v0 = c.currentSetVersion
        c.setHtml("<p>x</p>")
        assertEquals("<p>x</p>", c.html)
        assertEquals(v0 + 1, c.currentSetVersion)
        c.commitHtml("<p>y</p>")
        assertEquals("<p>y</p>", c.html)
    }

    @Test
    fun `paragraph iteration covers lines`() {
        val e = SpannableStringBuilder("one\ntwo\n")
        val paras = e.paragraphsAll()
        assertEquals(listOf(0 to 3, 4 to 7, 8 to 8), paras)
        var count = 0
        e.forEachParagraph(0, 8) { _, _ -> count++ }
        // The trailing empty paragraph is a boundary, not a line to act on.
        assertEquals(2, count)
        var all = 0
        e.forEachParagraph(0, 0) { _, _ -> all++ }
        assertEquals(1, all)
    }
}
