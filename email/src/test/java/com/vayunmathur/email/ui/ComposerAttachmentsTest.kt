package com.vayunmathur.email.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.email.ui.composer.EmailHtmlEditorController
import com.vayunmathur.email.ui.composer.InlineImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/**
 * Renders the real [ComposerAttachmentSection]: attachment rows prove what
 * will be sent, and the remove button is the only way to drop a wrong file.
 * A miswired remove drops the wrong URI, so the callback argument is asserted
 * exactly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposerAttachmentsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Provider that reports a 26 MB size for every picked file. */
    class BigFileProvider : ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor {
            val cols = projection ?: arrayOf("_display_name", "_size")
            val cursor = MatrixCursor(cols)
            cursor.addRow(cols.map { if (it == "_size") 26L * 1024 * 1024 else "big.bin" })
            return cursor
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    private fun setContent(
        controller: EmailHtmlEditorController = EmailHtmlEditorController(""),
        attachments: List<Uri> = emptyList(),
        onRemoveAttachment: (Uri) -> Unit = {},
    ) {
        composeRule.setContent {
            ComposerAttachmentSection(
                context = context,
                bodyController = controller,
                attachments = attachments,
                onRemoveAttachment = onRemoveAttachment,
            )
        }
    }

    @Test
    fun `empty attachments render nothing`() {
        setContent()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Attachments", substring = true).fetchSemanticsNodes().let {
            assertEquals(0, it.size)
        }
    }

    @Test
    fun `attachment row shows the file name`() {
        setContent(attachments = listOf(Uri.parse("content://media/doc.pdf")))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("doc.pdf", substring = true).assertIsDisplayed()
    }

    @Test
    fun `remove button routes the exact uri`() {
        val uri = Uri.parse("content://media/a.pdf")
        var removed: Uri? = null
        setContent(attachments = listOf(uri), onRemoveAttachment = { removed = it })
        composeRule.waitForIdle()
        composeRule.onNodeWithText("a.pdf", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Close").performClick()
        assertEquals(uri, removed)
    }

    @Test
    fun `inline images show a thumbnail card and remove through the controller`() {
        val controller = EmailHtmlEditorController("")
        // In the real composer the controller is bound to the editor;
        // removeInlineImage needs that binding to drop the entry.
        controller.editText = android.widget.EditText(context)
        controller.inlineImages.add(
            InlineImage(
                cid = "img1@inline",
                localUri = Uri.parse("file:///cache/pic.jpg"),
                mimeType = "image/jpeg",
                fileName = "pic.jpg",
            )
        )
        setContent(controller = controller)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Inline images", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("pic.jpg", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Close").performClick()
        composeRule.waitForIdle()
        assertTrue(controller.inlineImages.isEmpty())
    }

    @Test
    fun `attachments over the size limit show the warning`() {
        ShadowContentResolver.registerProviderInternal("big.test", BigFileProvider())
        setContent(attachments = listOf(Uri.parse("content://big.test/file.bin")))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Attachments", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("exceeds 25 MB", substring = true).assertIsDisplayed()
    }

    @Test
    fun `each row carries its own remove action`() {
        var removed: Uri? = null
        setContent(
            attachments = listOf(Uri.parse("content://media/a.pdf"), Uri.parse("content://media/b.pdf")),
            onRemoveAttachment = { removed = it },
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithText("a.pdf", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("b.pdf", substring = true).assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Close").fetchSemanticsNodes().let {
            assertEquals(2, it.size)
        }
        assertEquals(null, removed)
    }
}
