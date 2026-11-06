package com.vayunmathur.launcher.ui

import android.content.ComponentName
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.ContainerRef
import com.vayunmathur.launcher.domain.GridSpec
import com.vayunmathur.launcher.domain.LauncherItemType
import com.vayunmathur.launcher.platform.ComponentKey
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
 * Compose tests (Robolectric + createComposeRule) for [Hotseat]: slotted
 * apps render their icons, tapping an app launches it, tapping a folder
 * opens it, and an empty hotseat renders nothing.
 *
 * A fake [IconLoader] serves 1x1 bitmaps so icon cells resolve synchronously
 * to labelled images instead of the placeholder glyph.
 */
@RunWith(AndroidJUnit4::class)
class LauncherHotseatUiTest {

    @get:Rule val rule = createComposeRule()

    private fun app(id: Long, label: String, slot: Int) = WorkspaceItem(
        id = id,
        type = LauncherItemType.APPLICATION,
        label = label,
        container = ContainerRef.Hotseat,
        rank = slot,
        key = ComponentKey(ComponentName("com.example.$label", "com.example.$label.Main"), 0L),
        rect = CellRect(slot, 0),
    )

    private val state = HomeUiState(
        loading = false,
        grid = GridSpec(columns = 4, rows = 4, hotseatSlots = 4),
        hotseat = listOf(app(1, "mail", 0), app(2, "maps", 1)),
    )

    private fun show(
        state: HomeUiState = this.state,
        onLaunch: (WorkspaceItem) -> Unit = {},
        onOpenFolder: (Long) -> Unit = {},
    ) {
        rule.setContent {
            CompositionLocalProvider(
                LocalLauncherDrag provides LauncherDragController(),
            ) {
                Hotseat(
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
                    draggable = false,
                    onOpenFolder = { id, _ -> onOpenFolder(id) },
                )
            }
        }
    }

    @Test fun slottedAppsAreClickable() {
        show()
        // One clickable cell per slotted item; empty slots are inert boxes.
        assertEquals(2, rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
    }

    @Test fun tappingAppLaunchesIt() {
        var launched: WorkspaceItem? = null
        show(onLaunch = { launched = it })
        rule.onAllNodes(hasClickAction())[0].performClick()
        assertEquals(1L, launched?.id)
    }

    @Test fun tappingFolderOpensIt() {
        var opened: Long? = null
        val folderState = state.copy(
            hotseat = listOf(
                WorkspaceItem(
                    id = 9L,
                    type = LauncherItemType.FOLDER,
                    label = "Games",
                    container = ContainerRef.Hotseat,
                    rank = 0,
                    children = listOf(app(3, "chess", 0)),
                ),
            ),
        )
        show(state = folderState, onOpenFolder = { opened = it })
        rule.onAllNodes(hasClickAction())[0].performClick()
        assertEquals(9L, opened)
    }

    @Test fun droppingAppCommitsMove() {
        var move: Quad? = null
        val controller = LauncherDragController()
        rule.setContent {
            CompositionLocalProvider(LocalLauncherDrag provides controller) {
                Hotseat(
                    state = state,
                    actions = object : HomeActions {
                        override fun commitMove(
                            id: Long,
                            container: ContainerRef,
                            screen: Int,
                            rect: CellRect,
                            rank: Int,
                            displaced: Map<Long, CellRect>,
                        ) {
                            move = Quad(id, container, screen, rect, rank)
                        }
                    },
                    draggable = false,
                    onOpenFolder = { _, _ -> },
                )
            }
        }
        rule.waitForIdle()
        // Drop app 2 onto the fourth slot's centre: commitMove to Hotseat rank 3.
        val at = rule.onAllNodes(hasClickAction())[3].fetchSemanticsNode().boundsInRoot.center
        controller.start(
            DragPayload(
                itemId = 2L,
                type = LauncherItemType.APPLICATION,
                label = "maps",
                key = ComponentKey(
                    ComponentName("com.example.maps", "com.example.maps.Main"),
                    0L,
                ),
                rect = CellRect(1, 0),
                origin = ContainerRef.Desktop,
                sourceBounds = androidx.compose.ui.geometry.Rect.Zero,
            ),
            at,
        )
        rule.waitForIdle()
        assertEquals(true, controller.drop())
        assertEquals(Quad(2L, ContainerRef.Hotseat, 0, CellRect(3, 0), 3), move)
    }

    private data class Quad(
        val id: Long,
        val container: ContainerRef,
        val screen: Int,
        val rect: CellRect,
        val rank: Int,
    )

    @Test fun emptyHotseatHasNoClickables() {
        show(state = state.copy(hotseat = emptyList()))
        assertEquals(0, rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
    }
}
