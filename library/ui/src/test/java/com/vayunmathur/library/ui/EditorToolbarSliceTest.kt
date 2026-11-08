package com.vayunmathur.library.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Slice 3/3: editor toolbar — [EditorBaseButtons] with a fake [EditorFormatter],
 * [LinkDialog] confirm/unlink flows, [EditorBottomBar].
 */
@RunWith(RobolectricTestRunner::class)
class EditorToolbarSliceTest {

    @get:Rule
    val rule = createComposeRule()

    private class FakeFormatter(
        override val supported: Set<EditorFormat> = setOf(
            EditorFormat.BOLD, EditorFormat.ITALIC, EditorFormat.LINK,
        ),
        override val enabled: Boolean = true,
        private val active: Set<EditorFormat> = emptySet(),
        private val link: LinkContext? = null,
    ) : EditorFormatter {
        val toggled = mutableListOf<EditorFormat>()
        var applied: Pair<String, String>? = null
        var unlinked = 0

        override fun isActive(format: EditorFormat) = format in active
        override fun toggle(format: EditorFormat) {
            toggled += format
        }

        override fun linkContext() = link
        override fun applyLink(context: LinkContext, text: String, url: String) {
            applied = text to url
        }

        override fun removeLink(context: LinkContext) {
            unlinked++
        }
    }

    @Test fun baseButtonsToggleFormats() {
        val formatter = FakeFormatter()
        rule.setContent {
            EditorBottomBar {
                EditorBaseButtons(formatter = formatter)
            }
        }
        // Click the button (parent of the icon): the "Bold" description sits on
        // the inner Icon, so act on its clickable ancestor.
        rule.onNodeWithContentDescription("Bold").performClick()
        rule.onNodeWithContentDescription("Italic").performClick()
        assertEquals(listOf(EditorFormat.BOLD, EditorFormat.ITALIC), formatter.toggled)
    }

    @Test fun unsupportedFormatsAreHidden() {
        rule.setContent {
            EditorBaseButtons(formatter = FakeFormatter())
        }
        rule.onNodeWithContentDescription("Bold").assertIsDisplayed()
        assertTrue(
            rule.onAllNodesWithContentDescription("Underline")
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test fun linkButtonOpensDialogAndApplies() {
        val formatter = FakeFormatter(link = LinkContext(editing = false, text = "", url = ""))
        rule.setContent {
            EditorBaseButtons(formatter = formatter)
        }
        rule.onNodeWithContentDescription("Link").performClick()
        // Two text fields (link text, URL); type a URL into the second and confirm.
        val fields = rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
        assertEquals(2, fields.size)
        rule.onAllNodes(hasSetTextAction())[1].performTextInput("https://example.com")
        // Confirm button labelled "Add" in insert mode.
        rule.onNodeWithText("Add").performClick()
        assertEquals("https://example.com" to "https://example.com", formatter.applied)
    }

    @Test fun linkDialogConfirmUsesUrlAsTextWhenBlank() {
        var confirmed: Pair<String, String>? = null
        rule.setContent {
            LinkDialog(
                context = LinkContext(editing = false, text = "", url = ""),
                onConfirm = { text, url -> confirmed = text to url },
                onDismiss = {},
            )
        }
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("Label")
        rule.onAllNodes(hasSetTextAction())[1].performTextInput("https://example.com")
        rule.onNodeWithText("Add").performClick()
        assertEquals("Label" to "https://example.com", confirmed)
    }

    @Test fun linkDialogEditModeUnlinks() {
        var unlinked = 0
        var dismissed = 0
        rule.setContent {
            LinkDialog(
                context = LinkContext(editing = true, text = "T", url = "https://e.com"),
                onConfirm = { _, _ -> },
                onDismiss = { dismissed++ },
                onUnlink = { unlinked++ },
            )
        }
        // Prefilled fields show the existing text/url; edit-mode buttons exist.
        rule.onNodeWithText("T").assertIsDisplayed()
        rule.onNodeWithText("Edit link").assertIsDisplayed()
        rule.onNodeWithText("Unlink").performClick()
        assertEquals(1, unlinked)
        assertEquals(0, dismissed)
    }

    @Test fun formatIconButtonSlotRendersAndClicks() {
        var clicks = 0
        rule.setContent {
            FormatIconButton(
                contentDescription = "Custom",
                onClick = { clicks++ },
                icon = { IconAdd() },
            )
        }
        rule.onNodeWithContentDescription("Custom").performClick()
        assertEquals(1, clicks)
    }

    @Test fun editorBottomBarShowsContent() {
        rule.setContent {
            EditorBottomBar {
                Text("Bar child")
            }
        }
        rule.onNodeWithText("Bar child").assertIsDisplayed()
    }
}
