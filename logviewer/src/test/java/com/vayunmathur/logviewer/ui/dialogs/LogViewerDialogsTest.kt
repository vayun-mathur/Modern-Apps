package com.vayunmathur.logviewer.ui.dialogs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.DynamicTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class LogViewerDialogsTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ------------------------------------------------------------ TextInputDialog

    @Test
    fun `text input confirms the edited text`() {
        var confirmed: String? = null
        var dismissed = 0
        composeRule.setContent {
            DynamicTheme {
                TextInputDialog(
                    title = "Set filter",
                    initialText = "old.*",
                    onConfirm = { confirmed = it },
                    onDismiss = { dismissed++ },
                    singleLine = true,
                    hint = "Regex",
                )
            }
        }
        composeRule.onNodeWithText("Set filter").assertIsDisplayed()
        composeRule.onNodeWithText("old.*").performTextReplacement("new.*[0-9]+")
        composeRule.onNodeWithText("Apply").performClick()
        assertEquals("new.*[0-9]+", confirmed)
        assertEquals(0, dismissed)
    }

    @Test
    fun `text input cancel dismisses without confirming`() {
        var confirmed: String? = null
        var dismissed = 0
        composeRule.setContent {
            DynamicTheme {
                TextInputDialog(
                    title = "Set filter",
                    initialText = "keep me",
                    onConfirm = { confirmed = it },
                    onDismiss = { dismissed++ },
                )
            }
        }
        composeRule.onNodeWithText("Cancel").performClick()
        assertNull(confirmed)
        assertEquals(1, dismissed)
    }

    @Test
    fun `text input shows its hint when empty`() {
        composeRule.setContent {
            DynamicTheme {
                TextInputDialog(
                    title = "Set filter",
                    initialText = "",
                    onConfirm = {},
                    onDismiss = {},
                    hint = "Regex hint text",
                )
            }
        }
        composeRule.onNodeWithText("Regex hint text").assertIsDisplayed()
    }

    @Test
    fun `text input rejects input beyond the length cap`() {
        var confirmed: String? = null
        composeRule.setContent {
            DynamicTheme {
                TextInputDialog(
                    title = "Set filter",
                    initialText = "short",
                    onConfirm = { confirmed = it },
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText("short").performTextReplacement("x".repeat(10_001))
        composeRule.onNodeWithText("Apply").performClick()
        // The over-cap replacement never landed, so the initial text is what confirms.
        assertEquals("short", confirmed)
    }

    @Test
    fun `text input accepts exactly the length cap`() {
        var confirmed: String? = null
        val atCap = "y".repeat(10_000)
        composeRule.setContent {
            DynamicTheme {
                TextInputDialog(
                    title = "Set filter",
                    initialText = "",
                    onConfirm = { confirmed = it },
                    onDismiss = {},
                )
            }
        }
        // The field is empty, so target it through its hint-less editable node by text entry
        // on the dialog's text field: use the Apply flow after typing via the root field.
        composeRule.onNodeWithText("Set filter").assertIsDisplayed()
        composeRule.onNode(
            androidx.compose.ui.test.hasSetTextAction(),
        ).performTextReplacement(atCap)
        composeRule.onNodeWithText("Apply").performClick()
        assertEquals(atCap, confirmed)
    }

    // ------------------------------------------------------------ StackTraceDialog

    @Test
    fun `stack trace dialog shows the trace and copy confirms both callbacks`() {
        var copies = 0
        var dismissals = 0
        composeRule.setContent {
            DynamicTheme {
                StackTraceDialog(
                    stackTrace = "java.io.IOException: disk full\n\tat Save.write(Save.kt:3)",
                    onCopy = { copies++ },
                    onDismiss = { dismissals++ },
                )
            }
        }
        composeRule.onNodeWithText("Unable to save file").assertIsDisplayed()
        composeRule.onNodeWithText("java.io.IOException: disk full", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Copy").performClick()
        assertEquals(1, copies)
        assertEquals(1, dismissals)
    }

    @Test
    fun `stack trace dialog cancel only dismisses`() {
        var copies = 0
        var dismissals = 0
        composeRule.setContent {
            DynamicTheme {
                StackTraceDialog(
                    stackTrace = "trace body",
                    onCopy = { copies++ },
                    onDismiss = { dismissals++ },
                )
            }
        }
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(0, copies)
        assertEquals(1, dismissals)
    }
}
