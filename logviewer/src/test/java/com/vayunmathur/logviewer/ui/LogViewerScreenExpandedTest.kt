package com.vayunmathur.logviewer.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.logviewer.domain.LogKind
import com.vayunmathur.logviewer.platform.LogViewerUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** Expanded-width layout: the stack trace is a side panel, not a dialog. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1280dp-h800dp")
class LogViewerScreenExpandedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val actions = FakeLogViewerActions()

    @Test
    fun `stack trace renders beside the log on expanded widths`() {
        composeRule.setContent {
            DynamicTheme {
                LogViewerScreen(
                    state = LogViewerUiState(
                        loading = false,
                        title = "Weather crashed",
                        lines = listOf("log line one", "log line two"),
                        kind = LogKind.ErrorReport,
                        fontSizeSp = 12f,
                        snapshotFileName = "crash.txt",
                        stackTrace = "java.lang.Exception: side panel trace",
                    ),
                    actions = actions,
                )
            }
        }
        composeRule.onNodeWithText("log line one").assertIsDisplayed()
        composeRule.onNodeWithText("Unable to save file").assertIsDisplayed()
        composeRule.onNodeWithText("java.lang.Exception: side panel trace").assertIsDisplayed()

        // The panel's Copy button copies then dismisses; Cancel only dismisses.
        composeRule.onNodeWithText("Copy").performClick()
        assertEquals(1, actions.stackTraceCopies)
        assertEquals(1, actions.stackTraceDismissals)
    }

    @Test
    fun `expanded layout without a trace shows just the log`() {
        composeRule.setContent {
            DynamicTheme {
                LogViewerScreen(
                    state = LogViewerUiState(
                        loading = false,
                        title = "Expanded log",
                        lines = listOf("only line"),
                        kind = LogKind.Logcat,
                        fontSizeSp = 9f,
                        snapshotFileName = "log.txt",
                    ),
                    actions = actions,
                )
            }
        }
        composeRule.onNodeWithText("only line").assertIsDisplayed()
        composeRule.onNodeWithText("Unable to save file").assertDoesNotExist()
    }
}
