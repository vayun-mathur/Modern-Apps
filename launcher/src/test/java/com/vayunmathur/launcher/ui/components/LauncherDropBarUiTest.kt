package com.vayunmathur.launcher.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.launcher.domain.LauncherItemType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * Compose tests (Robolectric + createComposeRule) for [DropBar]: hidden
 * when nothing drags, offering Remove + Uninstall for a user app, Remove +
 * App info for a system app, and Remove only for drawer drags and widgets.
 */
@RunWith(AndroidJUnit4::class)
class LauncherDropBarUiTest {

    @get:Rule val rule = createComposeRule()

    private fun payload(type: LauncherItemType, canUninstall: Boolean, itemId: Long? = 1L) =
        DragPayload(
            itemId = itemId,
            type = type,
            label = "Mail",
            canUninstall = canUninstall,
            rect = com.vayunmathur.launcher.domain.CellRect(0, 0),
            sourceBounds = Rect.Zero,
        )

    private fun show(
        controller: LauncherDragController,
        onRemove: (Long) -> Unit = {},
        onUninstall: (Long) -> Unit = {},
        onAppInfo: (Long) -> Unit = {},
    ) {
        rule.setContent {
            CompositionLocalProvider(LocalLauncherDrag provides controller) {
                DropBar(
                    controller = controller,
                    onRemove = onRemove,
                    onUninstall = onUninstall,
                    onAppInfo = onAppInfo,
                )
            }
        }
    }

    @Test fun hiddenWhenIdle() {
        show(LauncherDragController())
        assertEquals(0, rule.onAllNodesWithText("Remove").fetchSemanticsNodes().size)
    }

    @Test fun userAppOffersRemoveAndUninstall() {
        val c = LauncherDragController()
        c.start(payload(LauncherItemType.APPLICATION, canUninstall = true), Offset.Zero)
        show(c)
        rule.onNodeWithText("Remove").assertIsDisplayed()
        rule.onNodeWithText("Uninstall").assertIsDisplayed()
    }

    @Test fun systemAppOffersAppInfoInstead() {
        val c = LauncherDragController()
        c.start(payload(LauncherItemType.APPLICATION, canUninstall = false), Offset.Zero)
        show(c)
        rule.onNodeWithText("Remove").assertIsDisplayed()
        rule.onNodeWithText("App info").assertIsDisplayed()
    }

    @Test fun widgetOffersRemoveOnly() {
        val c = LauncherDragController()
        c.start(payload(LauncherItemType.APPWIDGET, canUninstall = false), Offset.Zero)
        show(c)
        rule.onNodeWithText("Remove").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Uninstall").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("App info").fetchSemanticsNodes().size)
    }

    @Test fun drawerDragOffersRemoveOnly() {
        val c = LauncherDragController()
        c.start(
            payload(LauncherItemType.APPLICATION, canUninstall = true, itemId = null),
            Offset.Zero,
        )
        show(c)
        rule.onNodeWithText("Remove").assertIsDisplayed()
    }
}
