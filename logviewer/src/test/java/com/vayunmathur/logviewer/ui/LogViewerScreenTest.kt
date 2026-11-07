package com.vayunmathur.logviewer.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.logviewer.R
import com.vayunmathur.logviewer.domain.LogKind
import com.vayunmathur.logviewer.domain.LogLevel
import com.vayunmathur.logviewer.platform.ExtraAction
import com.vayunmathur.logviewer.platform.LogViewerUiState
import com.vayunmathur.logviewer.platform.LogcatFilters
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class LogViewerScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val actions = FakeLogViewerActions()

    private fun setScreen(state: LogViewerUiState) {
        composeRule.setContent {
            DynamicTheme {
                LogViewerScreen(state = state, actions = actions)
            }
        }
    }

    private fun loadedState(
        kind: LogKind = LogKind.ErrorReport,
        lines: List<String> = listOf("first line", "second line"),
    ) = LogViewerUiState(
        loading = false,
        title = "A title",
        sourcePackage = "com.example.app",
        lines = lines,
        kind = kind,
        fontSizeSp = kind.initialFontSizeSp,
        snapshotFileName = "snapshot.txt",
    )

    @Test
    fun `loading state shows the title and no log lines`() {
        setScreen(LogViewerUiState(loading = true, title = "Loading title"))
        composeRule.onNodeWithText("Loading title").assertIsDisplayed()
        composeRule.onNodeWithText("first line").assertDoesNotExist()
    }

    @Test
    fun `unavailable with a message explains why`() {
        setScreen(
            LogViewerUiState(
                loading = false,
                unavailable = true,
                unavailableMessageRes = R.string.error_system_log_secondary_user,
                title = "System log",
            )
        )
        composeRule.onNodeWithText("The device log is only available to the primary user")
            .assertIsDisplayed()
    }

    @Test
    fun `unavailable without a message renders no explanation`() {
        setScreen(
            LogViewerUiState(
                loading = false,
                unavailable = true,
                unavailableMessageRes = null,
                title = "System log",
            )
        )
        composeRule.onNodeWithText("The device log is only available to the primary user")
            .assertDoesNotExist()
        composeRule.onNodeWithText("first line").assertDoesNotExist()
    }

    @Test
    fun `loaded report shows lines and bottom actions dispatch`() {
        setScreen(
            loadedState().copy(
                showReportButton = true,
                extraActions = listOf(ExtraAction.MoreInfo, ExtraAction.ShowAppLog),
            )
        )
        composeRule.onNodeWithText("first line").assertIsDisplayed()
        composeRule.onNodeWithText("second line").assertIsDisplayed()

        composeRule.onNodeWithText("Report").performClick()
        composeRule.onNodeWithText("More info").performClick()
        composeRule.onNodeWithText("Show log").performClick()
        assertEquals(1, actions.reports)
        assertEquals(listOf(ExtraAction.MoreInfo, ExtraAction.ShowAppLog), actions.performed)
    }

    @Test
    fun `share menu opens and copy and share dispatch`() {
        setScreen(loadedState())
        composeRule.onNodeWithContentDescription("Share").performClick()
        composeRule.onNodeWithText("Copy").assertIsDisplayed()
        composeRule.onNodeWithText("Share").assertIsDisplayed()
        composeRule.onNodeWithText("Save").assertIsDisplayed()

        composeRule.onNodeWithText("Copy").performClick()
        assertEquals(1, actions.copies)

        composeRule.onNodeWithContentDescription("Share").performClick()
        composeRule.onNodeWithText("Share").performClick()
        assertEquals(1, actions.shares)
    }

    @Test
    fun `refreshing keeps the previous lines on screen`() {
        setScreen(loadedState().copy(refreshing = true))
        composeRule.onNodeWithText("first line").assertIsDisplayed()
        composeRule.onNodeWithText("second line").assertIsDisplayed()
    }

    @Test
    fun `logcat lines are displayed trimmed`() {
        setScreen(
            loadedState(
                kind = LogKind.Logcat,
                lines = listOf("  padded\tlog line  ", "plain"),
            )
        )
        composeRule.onNodeWithText("padded log line").assertIsDisplayed()
        composeRule.onNodeWithText("plain").assertIsDisplayed()
    }

    @Test
    fun `logcat controls pick a level and open the filter dialog`() {
        setScreen(
            loadedState(kind = LogKind.Logcat).copy(
                logcat = LogcatFilters(listOf("main"), LogLevel.Error, "initial.*"),
            )
        )
        // Level dropdown: the trigger shows the current letter.
        composeRule.onNodeWithText("E").performClick()
        composeRule.onNodeWithText("Debug").performClick()
        assertEquals(listOf(LogLevel.Debug), actions.levelsSet)

        // Buffers dropdown lists every buffer.
        composeRule.onNodeWithText("M").performClick()
        composeRule.onNodeWithText("radio").assertIsDisplayed()
        composeRule.onNodeWithText("kernel").assertIsDisplayed()
        // Dismiss the menu by choosing nothing and pressing back via the trigger again
        // is not how menus close; use a click on the title area instead.
        composeRule.onNodeWithText("A title").performClick()

        // Filter dialog opens from the search action and applies the edited text.
        composeRule.onNodeWithContentDescription("Search").performClick()
        composeRule.onNodeWithText("Set filter").assertIsDisplayed()
        composeRule.onNodeWithText("initial.*").assertIsDisplayed()
        composeRule.onNodeWithText("Apply").performClick()
        assertEquals(listOf("initial.*"), actions.filtersSet)
    }

    @Test
    fun `filter dialog can be cancelled without applying`() {
        setScreen(
            loadedState(kind = LogKind.Logcat).copy(
                logcat = LogcatFilters(listOf("main"), LogLevel.Verbose, ""),
            )
        )
        composeRule.onNodeWithContentDescription("Search").performClick()
        composeRule.onNodeWithText("Set filter").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithText("Set filter").assertDoesNotExist()
        assertTrue(actions.filtersSet.isEmpty())
    }

    @Test
    fun `stack trace dialog copies and dismisses, or just dismisses`() {
        setScreen(loadedState().copy(stackTrace = "java.lang.Exception: boom\n\tat X.y(X.kt:1)"))
        composeRule.onNodeWithText("Unable to save file").assertIsDisplayed()
        composeRule.onNodeWithText("java.lang.Exception: boom\n\tat X.y(X.kt:1)", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Copy").performClick()
        assertEquals(1, actions.stackTraceCopies)
        assertEquals(1, actions.stackTraceDismissals)
    }

    @Test
    fun `no bottom bar without report button or extra actions`() {
        setScreen(loadedState())
        composeRule.onNodeWithText("Report").assertDoesNotExist()
        composeRule.onNodeWithText("More info").assertDoesNotExist()
        composeRule.onNodeWithText("Show log").assertDoesNotExist()
    }
}
