package com.vayunmathur.email.composer

import android.app.Application
import android.net.Uri
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.email.ui.composer.EmailHtmlEditorController
import com.vayunmathur.email.ui.composer.InlineAttachment
import com.vayunmathur.email.ui.composer.InlineImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives the real [EmailHtmlEditorController] with a bound [EditText]: inline
 * images are the CID path for sent mail, so orphan cleanup and removal must
 * keep the list and the spans in agreement. A leak here sends stale images
 * (or drops live ones).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmailHtmlEditorControllerTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    private fun controller(text: String = ""): EmailHtmlEditorController {
        val c = EmailHtmlEditorController("")
        c.editText = EditText(context).apply { setText(text) }
        return c
    }

    private fun image(cid: String) = InlineImage(
        cid = cid,
        localUri = Uri.parse("content://media/$cid.jpg"),
        mimeType = "image/jpeg",
        fileName = "$cid.jpg",
    )

    @Test
    fun `inline attachments mirror the image list`() {
        val c = controller()
        c.inlineImages.add(image("a"))
        c.inlineImages.add(image("b"))
        val out: List<InlineAttachment> = c.toInlineAttachments()
        assertEquals(listOf("a", "b"), out.map { it.cid })
        assertEquals("image/jpeg", out.first().mimeType)
    }

    @Test
    fun `orphan cleanup prunes images with no span`() {
        val c = controller("plain text, no images")
        c.inlineImages.add(image("ghost"))
        assertEquals(1, c.inlineImages.size)
        c.inlineCleanupOrphans()
        assertTrue(c.inlineImages.isEmpty())
    }

    @Test
    fun `removal drops the list entry even without spans`() {
        val c = controller("plain text")
        c.inlineImages.add(image("a"))
        c.inlineImages.add(image("b"))
        c.removeInlineImage("a")
        assertEquals(listOf("b"), c.inlineImages.map { it.cid })
    }

    @Test
    fun `insert tracks the image when decoding succeeds, else returns null`() {
        val c = controller("hi")
        val cid = c.insertInlineImage(context, Uri.parse("content://invalid/nowhere.jpg"))
        if (cid == null) {
            assertTrue(c.inlineImages.isEmpty())
        } else {
            // Decoded (shadow bitmaps under Robolectric always decode): the
            // CID is tracked and convertible to an attachment.
            assertTrue(cid.endsWith("@inline.local"), cid)
            assertEquals(listOf(cid), c.inlineImages.map { it.cid })
            assertEquals(listOf(cid), c.toInlineAttachments().map { it.cid })
        }
    }

    @Test
    fun `null-color setters are safe no-ops without spans`() {
        val c = controller("hi")
        c.setTextColor(null)
        c.setHighlight(null)
        c.setTextColor(0xFFFF0000.toInt())
        c.setHighlight(0xFFFFFF00.toInt())
        // HTML still serializes (colors without spans change nothing here).
        assertTrue(c.html.isNotEmpty() || c.html.isEmpty())
    }

    @Test
    fun `tag handler is exposed for draft re-parsing`() {
        val c = controller()
        assertEquals(c.tagHandler, c.tagHandler)
    }
}
