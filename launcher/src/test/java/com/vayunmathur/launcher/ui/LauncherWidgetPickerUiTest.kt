package com.vayunmathur.launcher.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.launcher.ui.components.LauncherDragController
import com.vayunmathur.launcher.ui.components.LocalLauncherDrag
import com.vayunmathur.launcher.platform.WidgetEntry
import com.vayunmathur.launcher.platform.WidgetGroup
import com.vayunmathur.launcher.platform.WidgetPickerActions
import com.vayunmathur.launcher.platform.WidgetPickerUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * Compose tests (Robolectric + createComposeRule) for [WidgetPickerContent]:
 * empty/loading states, grouped widget rows with span text, search reaching
 * actions, and tapping a row adding the widget.
 */
@RunWith(AndroidJUnit4::class)
class LauncherWidgetPickerUiTest {

    @get:Rule val rule = createComposeRule()

    private val groups = listOf(
        WidgetGroup(
            appLabel = "Clock",
            widgets = listOf(
                WidgetEntry("com.example/.Analog", "Analog clock", spanX = 2, spanY = 2),
                WidgetEntry("com.example/.Digital", "", description = "Time", spanX = 4, spanY = 1),
            ),
        ),
    )

    private fun show(
        state: WidgetPickerUiState = WidgetPickerUiState(groups = groups),
        onQuery: (String) -> Unit = {},
        onAdd: (WidgetEntry) -> Unit = {},
    ) {
        rule.setContent {
            CompositionLocalProvider(LocalLauncherDrag provides LauncherDragController()) {
            WidgetPickerContent(
                state = state,
                actions = object : WidgetPickerActions {
                    override fun setWidgetQuery(query: String) = onQuery(query)
                    override fun addWidget(entry: WidgetEntry) = onAdd(entry)
                },
            )
            }
        }
    }

    @Test fun groupsAndSpanTextRender() {
        show()
        rule.onNodeWithText("Clock").assertIsDisplayed()
        rule.onNodeWithText("Analog clock").assertIsDisplayed()
        rule.onNodeWithText("2 x 2").assertIsDisplayed()
        rule.onNodeWithText("4 x 1 - Time").assertIsDisplayed()
    }

    @Test fun blankLabelFallsBackToProvider() {
        show()
        rule.onNodeWithText("com.example/.Digital").assertIsDisplayed()
    }

    @Test fun tappingRowAddsWidget() {
        var added: WidgetEntry? = null
        show(onAdd = { added = it })
        rule.onNodeWithText("Analog clock").performClick()
        assertEquals("com.example/.Analog", added?.provider)
    }

    @Test fun searchReachesActions() {
        val queries = mutableListOf<String>()
        show(onQuery = { queries += it })
        rule.onNode(hasSetTextAction()).performTextInput("clo")
        assertEquals(listOf("clo"), queries)
    }

    @Test fun emptyReadsNoWidgets() {
        show(state = WidgetPickerUiState())
        rule.onNodeWithText("No widgets available").assertIsDisplayed()
    }

    @Test fun loadingReadsLoading() {
        show(state = WidgetPickerUiState(loading = true))
        rule.onNodeWithText("Loading widgets").assertIsDisplayed()
    }
}
