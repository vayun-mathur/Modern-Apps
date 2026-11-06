package com.vayunmathur.launcher.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.launcher.ui.components.LauncherDragController
import com.vayunmathur.launcher.ui.components.LocalLauncherDrag
import com.vayunmathur.launcher.domain.ContainerRef
import com.vayunmathur.launcher.domain.LauncherItemType
import com.vayunmathur.launcher.platform.ItemMenuActions
import com.vayunmathur.launcher.platform.ItemMenuUiState
import com.vayunmathur.launcher.platform.ShortcutEntry
import com.vayunmathur.launcher.platform.WorkspaceItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compose tests (Robolectric + createComposeRule) for [ItemMenuContent]:
 * the menu shows the item label, shortcut rows, and App info / Uninstall
 * entries; tapping App info and confirming Uninstall reach [ItemMenuActions].
 */
@RunWith(AndroidJUnit4::class)
class LauncherItemMenuUiTest {

    @get:Rule val rule = createComposeRule()

    private val item = WorkspaceItem(
        id = 7L,
        type = LauncherItemType.APPLICATION,
        label = "Mail",
        container = ContainerRef.Desktop,
    )

    private fun state(shortcuts: Boolean = true, canUninstall: Boolean = true) = ItemMenuUiState(
        item = item,
        shortcuts = if (shortcuts) {
            listOf(ShortcutEntry("compose", "Compose", "com.example.mail", 0L))
        } else {
            emptyList()
        },
        canUninstall = canUninstall,
    )

    private fun show(
        state: ItemMenuUiState = state(),
        onAppInfo: (WorkspaceItem) -> Unit = {},
        onUninstall: (WorkspaceItem) -> Unit = {},
        onLaunchShortcut: (ShortcutEntry) -> Unit = {},
    ) {
        rule.setContent {
            CompositionLocalProvider(LocalLauncherDrag provides LauncherDragController()) {
            ItemMenuContent(
                state = state,
                actions = object : ItemMenuActions {
                    override fun openAppInfo(item: WorkspaceItem) = onAppInfo(item)
                    override fun uninstall(item: WorkspaceItem) = onUninstall(item)
                    override fun launchShortcut(entry: ShortcutEntry) = onLaunchShortcut(entry)
                },
            )
            }
        }
    }

    @Test fun labelShortcutsAndRowsRender() {
        show()
        rule.onNodeWithText("Mail").assertIsDisplayed()
        rule.onNodeWithText("Compose").assertIsDisplayed()
        rule.onNodeWithText("App info").assertIsDisplayed()
        rule.onNodeWithText("Uninstall").assertIsDisplayed()
    }

    @Test fun tappingAppInfoReachesActions() {
        var infoed: WorkspaceItem? = null
        show(onAppInfo = { infoed = it })
        rule.onNodeWithText("App info").performClick()
        assertEquals(7L, infoed?.id)
    }

    @Test fun uninstallRowAndDialogBothNameUninstall() {
        var uninstalled: WorkspaceItem? = null
        show(onUninstall = { uninstalled = it })
        // Only the row names Uninstall before the tap.
        assertEquals(1, rule.onAllNodesWithText("Uninstall").fetchSemanticsNodes().size)
        rule.onAllNodesWithText("Uninstall")[0].performClick()
        // The confirm dialog names the app; row + dialog button now share the text.
        rule.onNodeWithText("Uninstall Mail?").assertIsDisplayed()
        assertEquals(2, rule.onAllNodesWithText("Uninstall").fetchSemanticsNodes().size)
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        // Nothing removed before confirming.
        assertEquals(null, uninstalled)
    }

    @Test fun confirmingUninstallRemoves() {
        var uninstalled: WorkspaceItem? = null
        show(onUninstall = { uninstalled = it })
        rule.onAllNodesWithText("Uninstall")[0].performClick()
        rule.onNodeWithText("Uninstall Mail?").assertIsDisplayed()
        rule.onAllNodesWithText("Uninstall")[1].performClick()
        assertEquals(7L, uninstalled?.id)
    }

    @Test fun shortcutTapLaunchesShortcut() {
        var launched: ShortcutEntry? = null
        show(onLaunchShortcut = { launched = it })
        rule.onNodeWithText("Compose").performClick()
        assertEquals("compose", launched?.shortcutId)
    }

    @Test fun systemAppHasNoUninstallRow() {
        show(state = state(canUninstall = false))
        rule.onNodeWithText("App info").assertIsDisplayed()
        assertTrue(runCatching { rule.onNodeWithText("Uninstall").assertIsDisplayed() }.isFailure)
    }

    @Test fun emptyStateRendersNothing() {
        rule.setContent {
            CompositionLocalProvider(LocalLauncherDrag provides LauncherDragController()) {
            ItemMenuContent(state = ItemMenuUiState(), actions = ItemMenuActions.Noop)
            }
        }
    }
}
