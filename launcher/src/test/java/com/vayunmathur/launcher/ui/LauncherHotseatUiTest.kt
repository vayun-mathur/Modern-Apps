package com.vayunmathur.launcher.ui

import android.content.ComponentName
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
import com.vayunmathur.launcher.platform.IconLoader
import com.vayunmathur.launcher.platform.LocalIconLoader
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

    private val loader = object : IconLoader {
        override fun appIcon(key: ComponentKey): ImageBitmap = ImageBitmap(1, 1)
    }

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
                LocalIconLoader provides loader,
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

    @Test fun slottedAppsRenderIcons() {
        show()
        rule.onNodeWithContentDescription("mail").assertIsDisplayed()
        rule.onNodeWithContentDescription("maps").assertIsDisplayed()
    }

    @Test fun tappingAppLaunchesIt() {
        var launched: WorkspaceItem? = null
        show(onLaunch = { launched = it })
        rule.onNodeWithContentDescription("mail").performClick()
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
        rule.onNodeWithText("Games").performClick()
        assertEquals(9L, opened)
    }

    @Test fun emptyHotseatRendersNoIcons() {
        show(state = state.copy(hotseat = emptyList()))
        assertEquals(
            0,
            rule.onAllNodesWithContentDescription("mail").fetchSemanticsNodes().size,
        )
    }
}
