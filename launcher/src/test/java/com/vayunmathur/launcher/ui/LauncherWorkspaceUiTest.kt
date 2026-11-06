package com.vayunmathur.launcher.ui

import android.appwidget.AppWidgetHostView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.ContainerRef
import com.vayunmathur.launcher.domain.GridSpec
import com.vayunmathur.launcher.domain.LauncherItemType
import com.vayunmathur.launcher.platform.HomeActions
import com.vayunmathur.launcher.platform.HomeUiState
import com.vayunmathur.launcher.platform.WorkspaceItem
import com.vayunmathur.launcher.ui.components.LauncherDragController
import com.vayunmathur.launcher.ui.components.LocalLauncherDrag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * Compose tests (Robolectric + createComposeRule) for [WorkspacePage]: app
 * labels render on the grid, tapping launches, folders open, and widget
 * slots with no host view render their placeholder.
 */
@RunWith(AndroidJUnit4::class)
class LauncherWorkspaceUiTest {

    @get:Rule val rule = createComposeRule()

    private fun app(id: Long, label: String, x: Int, y: Int) = WorkspaceItem(
        id = id,
        type = LauncherItemType.APPLICATION,
        label = label,
        container = ContainerRef.Desktop,
        screen = 0,
        rect = CellRect(x, y),
    )

    private val state = HomeUiState(
        loading = false,
        grid = GridSpec(columns = 4, rows = 4, hotseatSlots = 4),
    )

    private fun show(
        items: List<WorkspaceItem>,
        onLaunch: (WorkspaceItem) -> Unit = {},
        onOpenFolder: (Long) -> Unit = {},
    ) {
        rule.setContent {
            CompositionLocalProvider(LocalLauncherDrag provides LauncherDragController()) {
                WorkspacePage(
                    state = state,
                    actions = object : HomeActions {
                        override fun launch(
                            item: WorkspaceItem,
                            left: Int,
                            top: Int,
                            right: Int,
                            bottom: Int,
                        ) {
                            onLaunch(item)
                        }
                    },
                    page = 0,
                    items = items,
                    resizingId = null,
                    draggable = false,
                    onOpenFolder = { id, _ -> onOpenFolder(id) },
                    onEndResize = {},
                    onWidgetDropped = {},
                    widgetView = { _: Int -> null },
                    updateWidgetSize = { _, _, _ -> },
                )
            }
        }
    }

    @Test fun appLabelsRenderOnGrid() {
        show(listOf(app(1, "Mail", 0, 0), app(2, "Maps", 1, 0)))
        rule.onNodeWithText("Mail").assertIsDisplayed()
        rule.onNodeWithText("Maps").assertIsDisplayed()
    }

    @Test fun tappingAppLaunchesIt() {
        var launched: WorkspaceItem? = null
        show(listOf(app(1, "Mail", 0, 0)), onLaunch = { launched = it })
        rule.onNodeWithText("Mail").performClick()
        assertEquals(1L, launched?.id)
    }

    @Test fun tappingFolderOpensIt() {
        var opened: Long? = null
        show(
            listOf(
                WorkspaceItem(
                    id = 9L,
                    type = LauncherItemType.FOLDER,
                    label = "Games",
                    container = ContainerRef.Desktop,
                    screen = 0,
                    rect = CellRect(0, 0),
                    children = listOf(app(3, "Chess", 0, 0)),
                ),
            ),
            onOpenFolder = { opened = it },
        )
        rule.onNodeWithText("Games").performClick()
        assertEquals(9L, opened)
    }

    @Test fun unboundWidgetRendersPlaceholder() {
        show(
            listOf(
                WorkspaceItem(
                    id = 5L,
                    type = LauncherItemType.APPWIDGET,
                    label = "Clock widget",
                    container = ContainerRef.Desktop,
                    screen = 0,
                    rect = CellRect(0, 0, 2, 2),
                    appWidgetId = null,
                    appWidgetProvider = "com.example/.Widget",
                ),
            ),
        )
        // No widget id at all, so the missing-widget placeholder shows the label.
        rule.onNodeWithText("Clock widget").assertIsDisplayed()
    }

    @Test fun emptyPageShowsNoLabels() {
        show(emptyList())
        assertEquals(0, rule.onAllNodesWithText("Mail").fetchSemanticsNodes().size)
    }
}
