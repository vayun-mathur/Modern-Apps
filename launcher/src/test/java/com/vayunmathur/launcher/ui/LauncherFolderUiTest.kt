package com.vayunmathur.launcher.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.launcher.ui.components.LauncherDragController
import com.vayunmathur.launcher.ui.components.LocalLauncherDrag
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.ContainerRef
import com.vayunmathur.launcher.domain.LauncherItemType
import com.vayunmathur.launcher.platform.FolderActions
import com.vayunmathur.launcher.platform.WorkspaceItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * Compose tests (Robolectric + createComposeRule) for [FolderContent]: the
 * folder sheet renders its editable title and children, typing in the title
 * field renames through [FolderActions], and dismiss fires on demand.
 */
@RunWith(AndroidJUnit4::class)
class LauncherFolderUiTest {

    @get:Rule val rule = createComposeRule()

    private fun folder(children: Int = 3) = WorkspaceItem(
        id = 10L,
        type = LauncherItemType.FOLDER,
        label = "Games",
        container = ContainerRef.Desktop,
        children = (1..children).map { i ->
            WorkspaceItem(
                id = 100L + i,
                type = LauncherItemType.APPLICATION,
                label = "App $i",
                container = ContainerRef.Folder(10L),
                rank = i - 1,
            )
        },
    )

    private fun show(
        item: WorkspaceItem = folder(),
        onRename: (Long, String) -> Unit = { _, _ -> },
    ) {
        rule.setContent {
            CompositionLocalProvider(LocalLauncherDrag provides LauncherDragController()) {
            FolderContent(
                folder = item,
                actions = object : FolderActions {
                    override fun rename(id: Long, title: String) = onRename(id, title)
                },
            )
            }
        }
    }

    @Test fun folderNameFieldAndChildrenRender() {
        show()
        rule.onNodeWithText("Folder name").assertIsDisplayed()
        rule.onNodeWithText("App 1").assertIsDisplayed()
        rule.onNodeWithText("App 2").assertIsDisplayed()
        rule.onNodeWithText("App 3").assertIsDisplayed()
    }

    @Test fun typingInNameFieldRenames() {
        val renames = mutableListOf<Pair<Long, String>>()
        show(onRename = { id, title -> renames += id to title })
        // The rename callback fires per keystroke (cursor starts at 0, so "A" prefixes).
        rule.onAllNodesWithText("Games", substring = true)[0].performTextInput("A")
        assertEquals(10L, renames.last().first)
        assertEquals("AGames", renames.last().second)
    }

    @Test fun tappingChildLaunchesAndDismisses() {
        var launched: WorkspaceItem? = null
        var dismissed = false
        rule.setContent {
            CompositionLocalProvider(LocalLauncherDrag provides LauncherDragController()) {
                FolderContent(
                    folder = folder(),
                    actions = object : FolderActions {
                        override fun launchChild(
                            item: WorkspaceItem,
                            left: Int,
                            top: Int,
                            right: Int,
                            bottom: Int,
                        ) {
                            launched = item
                        }
                    },
                    onDismiss = { dismissed = true },
                )
            }
        }
        rule.onNodeWithText("App 1").performClick()
        assertEquals(101L, launched?.id)
        assertEquals(true, dismissed)
    }

    @Test fun droppingSiblingReorders() {
        val controller = LauncherDragController()
        val reorders = mutableListOf<Triple<Long, Long, Int>>()
        rule.setContent {
            CompositionLocalProvider(LocalLauncherDrag provides controller) {
                FolderContent(
                    folder = folder(),
                    actions = object : FolderActions {
                        override fun reorderInFolder(folderId: Long, itemId: Long, rank: Int) {
                            reorders += Triple(folderId, itemId, rank)
                        }
                    },
                )
            }
        }
        rule.waitForIdle()
        // Drop child 101 onto child 102's slot: reorder fires, landing returned.
        controller.start(
            com.vayunmathur.launcher.ui.components.DragPayload(
                itemId = 101L,
                type = LauncherItemType.APPLICATION,
                label = "App 1",
                rect = CellRect(0, 0),
                origin = ContainerRef.Folder(10L),
                sourceBounds = androidx.compose.ui.geometry.Rect.Zero,
            ),
            androidx.compose.ui.geometry.Offset(400f, 300f),
        )
        rule.waitForIdle()
        assertEquals(true, controller.drop())
        assertEquals(listOf(Triple(10L, 101L, 1)), reorders)
    }

    @Test fun singleChildRendersAlone() {
        show(item = folder(children = 1))
        rule.onNodeWithText("App 1").assertIsDisplayed()
    }
}
