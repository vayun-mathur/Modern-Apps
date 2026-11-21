package com.vayunmathur.office.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.OdfTable
import com.vayunmathur.library.ui.odf.OdfTableCell
import com.vayunmathur.library.ui.odf.OdfTableRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Keyboard focus traversal for the text-document editor (Robolectric +
 * createComposeRule, same level as [Slice4DocumentViewsTest]): the harness
 * composes the stops in the screen's visual order — title, body segments,
 * toolbar action — under the same root preview-key wiring [DocumentScreen]
 * uses ([handleOfficeFocusTraversalKey] + FocusManager + the body's
 * Escape-target requester), with [TextDocumentView] itself production.
 *
 * Traversal order under test: title -> first paragraph run -> table cell ->
 * second paragraph run -> toolbar action (document/visual order, no wrap at
 * the ends).
 */
@RunWith(AndroidJUnit4::class)
class TextDocumentFocusTest {

    @get:Rule val rule = createComposeRule()

    private fun para(text: String) =
        OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text))))

    private fun doc(): OdfDocument.TextDocument {
        val cell = OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan("cellone")))))
        val table = OdfTable(rows = listOf(OdfTableRow(listOf(cell))))
        return OdfDocument.TextDocument(
            "Doc",
            listOf(para("first"), OdfContentBlock.Table(table), para("second")),
        )
    }

    private class HarnessState {
        lateinit var titleRequester: FocusRequester
        lateinit var toolbarRequester: FocusRequester
        lateinit var bodyRequester: FocusRequester
        var bodyHasFocus = false
        var dialogOpen = false
    }

    private fun setEditor(state: HarnessState) {
        rule.setContent {
            val focusManager = LocalFocusManager.current
            val titleRequester = remember { FocusRequester() }
            val toolbarRequester = remember { FocusRequester() }
            val bodyRequester = remember { FocusRequester() }
            state.titleRequester = titleRequester
            state.toolbarRequester = toolbarRequester
            state.bodyRequester = bodyRequester
            var titleFocused by remember { mutableStateOf(false) }
            Column(
                Modifier.onPreviewKeyEvent { event ->
                    handleOfficeFocusTraversalKey(
                        event = event,
                        dialogOpen = state.dialogOpen,
                        onNext = { focusManager.moveFocus(FocusDirection.Next) },
                        onPrevious = { focusManager.moveFocus(FocusDirection.Previous) },
                        onEscapeToBody = {
                            if (state.bodyHasFocus) false
                            else runCatching { bodyRequester.requestFocus() }.isSuccess
                        },
                    )
                },
            ) {
                Text(
                    "DocTitle",
                    modifier = Modifier
                        .focusRequester(titleRequester)
                        .onFocusChanged { titleFocused = it.isFocused }
                        .officeFocusRing(titleFocused)
                        .focusable(),
                )
                TextDocumentView(
                    doc = doc(),
                    bodyFocusRequester = bodyRequester,
                    onBodyFocusChanged = { state.bodyHasFocus = it },
                )
                TextButton(
                    onClick = {},
                    modifier = Modifier.focusRequester(toolbarRequester),
                ) { Text("ToolbarAction") }
            }
        }
        rule.waitForIdle()
    }

    @Test fun tabTraversesTitleBodySegmentsToolbarInVisualOrder() {
        val state = HarnessState()
        setEditor(state)

        rule.runOnIdle { state.titleRequester.requestFocus() }
        rule.onNodeWithText("DocTitle").assertIsFocused()

        // Tab: title -> first paragraph run.
        rule.onNodeWithText("DocTitle").performKeyInput { pressKey(Key.Tab) }
        rule.waitForIdle()
        rule.onNodeWithText("first").assertIsFocused()
        rule.onNodeWithText("DocTitle").assertIsNotFocused()

        // Tab: first run -> table cell.
        rule.onNodeWithText("first").performKeyInput { pressKey(Key.Tab) }
        rule.waitForIdle()
        rule.onNodeWithText("cellone").assertIsFocused()

        // Tab: cell -> second paragraph run -> toolbar action.
        rule.onNodeWithText("cellone").performKeyInput { pressKey(Key.Tab) }
        rule.waitForIdle()
        rule.onNodeWithText("second").assertIsFocused()
        rule.onNodeWithText("second").performKeyInput { pressKey(Key.Tab) }
        rule.waitForIdle()
        rule.onNodeWithText("ToolbarAction").assertIsFocused()

        // Shift-Tab walks back: toolbar -> second run.
        rule.onNodeWithText("ToolbarAction").performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.Tab)
            keyUp(Key.ShiftLeft)
        }
        rule.waitForIdle()
        rule.onNodeWithText("second").assertIsFocused()
        rule.onNodeWithText("ToolbarAction").assertIsNotFocused()
    }

    @Test fun escapeFromToolbarReturnsFocusToBody() {
        val state = HarnessState()
        setEditor(state)

        rule.runOnIdle { state.toolbarRequester.requestFocus() }
        rule.onNodeWithText("ToolbarAction").assertIsFocused()

        rule.onNodeWithText("ToolbarAction").performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        // Body's first stop (first paragraph run) owns focus again.
        rule.onNodeWithText("first").assertIsFocused()
        rule.onNodeWithText("ToolbarAction").assertIsNotFocused()
    }

    @Test fun escapeInBodyIsNotConsumed() {
        val state = HarnessState()
        setEditor(state)

        rule.runOnIdle { state.bodyRequester.requestFocus() }
        rule.onNodeWithText("first").assertIsFocused()
        // Escape with the body already focused declines in the harness
        // callback (production DocumentScreen does the same), so the event
        // is left for other handlers and focus stays in the body.
        rule.onNodeWithText("first").performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        rule.onNodeWithText("first").assertIsFocused()
    }

    @Test fun traversalSuppressedWhileDialogOpen() {
        val state = HarnessState()
        setEditor(state)
        state.dialogOpen = true

        rule.runOnIdle { state.titleRequester.requestFocus() }
        rule.onNodeWithText("DocTitle").assertIsFocused()
        rule.onNodeWithText("DocTitle").performKeyInput { pressKey(Key.Tab) }
        rule.waitForIdle()
        // The dialog/menu keeps the keyboard: focus does not move.
        rule.onNodeWithText("DocTitle").assertIsFocused()
        rule.onNodeWithText("first").assertIsNotFocused()
    }
}
