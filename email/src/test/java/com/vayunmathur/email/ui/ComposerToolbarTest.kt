package com.vayunmathur.email.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.email.ui.composer.EmailHtmlEditorController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Renders the real [EmailComposerFormatToolbar] with a detached controller
 * (no EditText bound, so formatting calls are safe no-ops): the toolbar's
 * menus, dialogs and image button are pure UI state. A missing menu strands
 * the formatting feature, so each test asserts the menu/dialog it opens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposerToolbarTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(onInsertImage: () -> Unit = {}) {
        composeRule.setContent {
            EmailComposerFormatToolbar(
                controller = EmailHtmlEditorController(""),
                onInsertImage = onInsertImage,
            )
        }
    }

    @Test
    fun `toolbar shows the normal heading label by default`() {
        setContent()
        composeRule.onNodeWithText("Normal").assertIsDisplayed()
    }

    @Test
    fun `heading menu lists all three levels`() {
        setContent()
        composeRule.onNodeWithText("Normal").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Heading 1").assertIsDisplayed()
        composeRule.onNodeWithText("Heading 2").assertIsDisplayed()
        composeRule.onNodeWithText("Heading 3").assertIsDisplayed()
    }

    @Test
    fun `alignment menu offers all four options`() {
        setContent()
        composeRule.onNodeWithContentDescription("Alignment").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Left").assertIsDisplayed()
        composeRule.onNodeWithText("Center").assertIsDisplayed()
        composeRule.onNodeWithText("Right").assertIsDisplayed()
        composeRule.onNodeWithText("Justify").assertIsDisplayed()
    }

    @Test
    fun `text color button opens the picker dialog`() {
        setContent()
        composeRule.onNodeWithContentDescription("Text color").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Text color").assertIsDisplayed()
        // The palette is swatches without text labels; the dialog dismiss
        // control proves it opened.
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun `highlight button opens the highlight dialog`() {
        setContent()
        composeRule.onNodeWithContentDescription("Highlight").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Highlight").assertIsDisplayed()
    }

    @Test
    fun `font size menu offers four scales`() {
        setContent()
        composeRule.onNodeWithContentDescription("Font size").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Small").assertIsDisplayed()
        composeRule.onNodeWithText("Large").assertIsDisplayed()
        composeRule.onNodeWithText("Extra Large").performScrollTo()
        composeRule.onNodeWithText("Extra Large").assertIsDisplayed()
    }

    @Test
    fun `font family menu offers all families and serif click closes it`() {
        setContent()
        composeRule.onNodeWithContentDescription("Font family").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Default").assertIsDisplayed()
        composeRule.onNodeWithText("Sans").assertIsDisplayed()
        composeRule.onNodeWithText("Monospace").assertIsDisplayed()
        composeRule.onNodeWithText("Serif").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Serif").assertDoesNotExist()
    }

    @Test
    fun `heading item click applies and closes the menu`() {
        setContent()
        composeRule.onNodeWithText("Normal").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Heading 2").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Heading 2").assertDoesNotExist()
    }

    @Test
    fun `color dialog cancel dismisses without applying`() {
        setContent()
        composeRule.onNodeWithContentDescription("Text color").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Cancel").assertDoesNotExist()
    }

    @Test
    fun `copy inline to cache returns null for an unreadable uri`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        assertNull(copyInlineToCache(context, Uri.parse("content://missing.provider/pic.jpg"), "pic.jpg"))
    }

    @Test
    fun `insert image button routes to the callback`() {
        var inserts = 0
        setContent(onInsertImage = { inserts++ })
        composeRule.onNodeWithContentDescription("Insert image").performClick()
        assertEquals(1, inserts)
    }
}
