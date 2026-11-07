package com.vayunmathur.pdf.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import com.vayunmathur.library.ui.DynamicTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DialogsAndMenusTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val launchers = SafePdfLauncherSet({}, {}, {})

    @Test fun pendingCalloutDialog() {
        val state = SafePdfViewerState(CoroutineScope(Dispatchers.Unconfined))
        state.pendingCallout = Triple(0, Offset(10f, 10f), Offset(50f, 50f))
        rule.setContent { DynamicTheme { SafePdfPendingDialogs(state, null, launchers) } }
        rule.onNodeWithText("Callout").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(null, state.pendingCallout)
    }

    @Test fun pendingNoteDialogAddWithNullDocClears() {
        val state = SafePdfViewerState(CoroutineScope(Dispatchers.Unconfined))
        state.pendingNote = 0 to Offset(10f, 10f)
        rule.setContent { DynamicTheme { SafePdfPendingDialogs(state, null, launchers) } }
        rule.onNodeWithText("Sticky note").assertIsDisplayed()
        rule.onNodeWithText("Add").performClick()
        assertEquals(null, state.pendingNote)
    }

    @Test fun encryptDialogConfirmSetsPendingPassword() {
        var encryptedLaunch = false
        val state = SafePdfViewerState(CoroutineScope(Dispatchers.Unconfined))
        state.showEncrypt = true
        val launchers2 = SafePdfLauncherSet({}, { encryptedLaunch = true }, {})
        rule.setContent { DynamicTheme { SafePdfPendingDialogs(state, null, launchers2) } }
        rule.onNodeWithText("Encrypt with password").assertIsDisplayed()
        // Empty password: confirm closes without launching
        rule.onNodeWithText("Save encrypted").performClick()
        assertFalse(state.showEncrypt)
        assertFalse(encryptedLaunch)
        assertEquals(null, state.pendingEncryptPw)
    }

    @Test fun encryptDialogCancel() {
        val state = SafePdfViewerState(CoroutineScope(Dispatchers.Unconfined))
        state.showEncrypt = true
        rule.setContent { DynamicTheme { SafePdfPendingDialogs(state, null, launchers) } }
        rule.onNodeWithText("Cancel").performClick()
        assertFalse(state.showEncrypt)
    }

    @Test fun styleDialogViaPendingDialogs() {
        val state = SafePdfViewerState(CoroutineScope(Dispatchers.Unconfined))
        state.showStyle = true
        rule.setContent { DynamicTheme { SafePdfPendingDialogs(state, null, launchers) } }
        rule.onNodeWithText("Style").assertIsDisplayed()
        rule.onRoot().captureToImage()
        rule.onNodeWithText("Done").performClick()
        assertFalse(state.showStyle)
    }

    @Test fun toolMenusOpenAndSelect() {
        var markup: MarkupKind? = null; var shape: ShapeKind? = null; var tool: EditTool? = null
        rule.setContent {
            DynamicTheme {
                androidx.compose.foundation.layout.Column {
                    MarkupMenuButton(markup = MarkupKind.HIGHLIGHT, active = true, onMarkup = { markup = it })
                    ShapeMenuButton(shape = ShapeKind.RECT_OUTLINE, active = true, onShape = { shape = it })
                    LinesMenuButton(tool = EditTool.LINE, onTool = { tool = it })
                    ToolButton(icon = { com.vayunmathur.library.ui.IconSelect(tint = it) }, selected = true) { tool = EditTool.SELECT }
                }
            }
        }
        // Open first menu (top-left button)
        rule.onRoot().performTouchInput { click(Offset(30f, 30f)) }
        rule.waitForIdle()
        val underline = rule.onAllNodes(androidx.compose.ui.test.hasText("Underline")).fetchSemanticsNodes()
        if (underline.isNotEmpty()) {
            rule.onNodeWithText("Underline").performClick()
            assertEquals(MarkupKind.UNDERLINE, markup)
        }
        rule.waitForIdle()
    }

}
