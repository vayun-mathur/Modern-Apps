package com.vayunmathur.launcher.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.ContainerRef
import com.vayunmathur.launcher.domain.GridSpec
import com.vayunmathur.launcher.domain.LauncherItemType
import com.vayunmathur.launcher.platform.DrawerUiState
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
 * Compose tests (Robolectric + createComposeRule) for [HomeContent]: the
 * workspace page renders its icons, the drawer opens to the app list when
 * started open, and a folder opens to its children when started open.
 */
@RunWith(AndroidJUnit4::class)
class LauncherHomeContentUiTest {

    @get:Rule val rule = createComposeRule()

    private fun app(id: Long, label: String, x: Int, y: Int) = WorkspaceItem(
        id = id,
        type = LauncherItemType.APPLICATION,
        label = label,
        container = ContainerRef.Desktop,
        screen = 0,
        rect = CellRect(x, y),
    )

    private val home = HomeUiState(
        loading = false,
        grid = GridSpec(columns = 4, rows = 4, hotseatSlots = 4),
        pages = mapOf(0 to listOf(app(1, "Mail", 0, 0), app(2, "Maps", 1, 0))),
    )

    private fun show(
        state: HomeUiState = home,
        drawer: DrawerUiState = DrawerUiState(),
        initialDrawerOpen: Boolean = false,
        initialOpenFolderId: Long? = null,
    ) {
        rule.setContent {
            CompositionLocalProvider(LocalLauncherDrag provides LauncherDragController()) {
                HomeContent(
                    state = state,
                    actions = HomeActions.Noop,
                    drawerState = drawer,
                    initialDrawerOpen = initialDrawerOpen,
                    initialOpenFolderId = initialOpenFolderId,
                )
            }
        }
    }

    @Test fun workspaceIconsRender() {
        show()
        rule.onNodeWithText("Mail").assertIsDisplayed()
        rule.onNodeWithText("Maps").assertIsDisplayed()
    }

    @Test fun openDrawerShowsAppList() {
        show(
            drawer = DrawerUiState(
                apps = listOf(
                    com.vayunmathur.launcher.platform.DrawerApp(
                        key = com.vayunmathur.launcher.platform.ComponentKey(
                            android.content.ComponentName("com.example.mail", "com.example.mail.Main"),
                            0L,
                        ),
                        label = "Drawer Mail",
                    ),
                ),
            ),
            initialDrawerOpen = true,
        )
        rule.onNodeWithText("Drawer Mail").assertIsDisplayed()
    }

    @Test fun openFolderShowsChildren() {
        val folderState = home.copy(
            pages = mapOf(
                0 to listOf(
                    WorkspaceItem(
                        id = 9L,
                        type = LauncherItemType.FOLDER,
                        label = "Games",
                        container = ContainerRef.Desktop,
                        screen = 0,
                        rect = CellRect(0, 0),
                        children = listOf(
                            WorkspaceItem(
                                id = 11L,
                                type = LauncherItemType.APPLICATION,
                                label = "Folder Chess",
                                container = ContainerRef.Folder(9L),
                            ),
                        ),
                    ),
                ),
            ),
        )
        show(state = folderState, initialOpenFolderId = 9L)
        rule.onNodeWithText("Folder Chess").assertIsDisplayed()
    }

    @Test fun tappingFolderIconOpensIt() {
        val folderState = home.copy(
            pages = mapOf(
                0 to listOf(
                    WorkspaceItem(
                        id = 9L,
                        type = LauncherItemType.FOLDER,
                        label = "Games",
                        container = ContainerRef.Desktop,
                        screen = 0,
                        rect = CellRect(0, 0),
                        children = listOf(
                            WorkspaceItem(
                                id = 11L,
                                type = LauncherItemType.APPLICATION,
                                label = "Folder Chess",
                                container = ContainerRef.Folder(9L),
                            ),
                        ),
                    ),
                ),
            ),
        )
        show(state = folderState)
        rule.onNodeWithText("Games").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Folder Chess").assertIsDisplayed()
    }

    @Test fun longPressEmptyOpensOptions() {
        show(state = home.copy(pages = emptyMap()))
        rule.onRoot().performTouchInput { longClick(durationMillis = 1000) }
        rule.waitForIdle()
        rule.onNodeWithText("Wallpaper & style").assertIsDisplayed()
    }

    @Test fun longPressIconOpensItemMenu() {
        val opened = mutableListOf<Long>()
        rule.setContent {
            CompositionLocalProvider(LocalLauncherDrag provides LauncherDragController()) {
                HomeContent(
                    state = home,
                    actions = HomeActions.Noop,
                    onOpenItemMenu = { opened += it },
                    itemMenuState = com.vayunmathur.launcher.platform.ItemMenuUiState(
                        item = app(1, "Mail", 0, 0),
                    ),
                )
            }
        }
        rule.onNodeWithText("Mail").performTouchInput { longClick(durationMillis = 1000) }
        rule.waitForIdle()
        assertEquals(listOf(1L), opened)
        rule.onNodeWithText("App info").assertIsDisplayed()
    }

    @Test fun optionsPopupDismissesOnOutsideTap() {
        show(state = home.copy(pages = emptyMap()))
        rule.onRoot().performTouchInput { longClick(durationMillis = 1000) }
        rule.waitForIdle()
        rule.onNodeWithText("Wallpaper & style").assertIsDisplayed()
        // Two roots exist while the popup is up; tap the main one, far
        // outside the popup's anchor.
        rule.onAllNodes(androidx.compose.ui.test.isRoot())[0].performTouchInput {
            click(androidx.compose.ui.geometry.Offset(5f, 5f))
        }
        rule.waitForIdle()
        rule.onNodeWithText("Wallpaper & style").assertDoesNotExist()
    }

    @Test fun openFolderDismissesOnBackdropTap() {
        val folderState = home.copy(
            pages = mapOf(
                0 to listOf(
                    WorkspaceItem(
                        id = 9L,
                        type = LauncherItemType.FOLDER,
                        label = "Games",
                        container = ContainerRef.Desktop,
                        screen = 0,
                        rect = CellRect(0, 0),
                        children = listOf(
                            WorkspaceItem(
                                id = 11L,
                                type = LauncherItemType.APPLICATION,
                                label = "Folder Chess",
                                container = ContainerRef.Folder(9L),
                            ),
                        ),
                    ),
                ),
            ),
        )
        show(state = folderState)
        rule.onNodeWithText("Games").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Folder Chess").assertIsDisplayed()
        // Tap the backdrop outside the folder sheet to dismiss it.
        rule.onRoot().performTouchInput {
            click(androidx.compose.ui.geometry.Offset(5f, 5f))
        }
        rule.waitForIdle()
        rule.onNodeWithText("Folder Chess").assertDoesNotExist()
        rule.onNodeWithText("Games").assertIsDisplayed()
    }

    @Test fun emptyWorkspaceRendersNoIcons() {
        show(state = home.copy(pages = emptyMap()))
        assertEquals(0, rule.onAllNodesWithText("Mail").fetchSemanticsNodes().size)
    }
}
