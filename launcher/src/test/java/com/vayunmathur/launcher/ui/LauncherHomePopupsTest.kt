package com.vayunmathur.launcher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.launcher.domain.ContainerRef
import com.vayunmathur.launcher.domain.LauncherItemType
import com.vayunmathur.launcher.platform.ItemMenuActions
import com.vayunmathur.launcher.platform.ItemMenuUiState
import com.vayunmathur.launcher.platform.ShortcutEntry
import com.vayunmathur.launcher.platform.WidgetPickerActions
import com.vayunmathur.launcher.platform.WidgetPickerUiState
import com.vayunmathur.launcher.platform.WorkspaceItem
import com.vayunmathur.launcher.ui.components.LauncherDragController
import com.vayunmathur.launcher.ui.components.LocalLauncherDrag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * Compose tests (Robolectric + createComposeRule) for [HomePopups]: the
 * item menu popup renders over its anchor, the wallpaper-options popup
 * renders its rows, and the widget picker sheet opens when asked.
 */
@RunWith(AndroidJUnit4::class)
class LauncherHomePopupsTest {

    @get:Rule val rule = createComposeRule()

    private val item = WorkspaceItem(
        id = 7L,
        type = LauncherItemType.APPLICATION,
        label = "Mail",
        container = ContainerRef.Desktop,
    )

    private fun show(
        menuItemId: Long? = 7L,
        optionsAnchor: Rect? = null,
        widgetPickerOpen: Boolean = false,
    ) {
        val popup = Animatable(1f)
        rule.setContent {
            CompositionLocalProvider(LocalLauncherDrag provides LauncherDragController()) {
                HomePopups(
                    menuItemId = menuItemId,
                    menuAnchor = Rect(0f, 0f, 100f, 100f),
                    itemMenuState = ItemMenuUiState(
                        item = item,
                        shortcuts = listOf(ShortcutEntry("compose", "Compose", "com.example.mail", 0L)),
                        canUninstall = true,
                    ),
                    itemMenuActions = ItemMenuActions.Noop,
                    popup = popup,
                    onClosePopup = {},
                    optionsAnchor = optionsAnchor,
                    onPickWallpaper = {},
                    widgetPickerActions = WidgetPickerActions.Noop,
                    onOpenSettings = {},
                    settleDrawer = {},
                    closePopup = {},
                    widgetPickerState = WidgetPickerUiState(open = widgetPickerOpen),
                )
            }
        }
    }

    @Test fun itemMenuPopupRenders() {
        show()
        rule.onNodeWithText("Mail").assertIsDisplayed()
        rule.onNodeWithText("App info").assertIsDisplayed()
    }

    @Test fun noPopupRendersNothing() {
        show(menuItemId = null)
        assertEquals(0, rule.onAllNodesWithText("App info").fetchSemanticsNodes().size)
    }

    @Test fun optionsPopupRendersRows() {
        show(menuItemId = null, optionsAnchor = Rect(0f, 0f, 100f, 100f))
        rule.onNodeWithText("Wallpaper & style").assertIsDisplayed()
        rule.onNodeWithText("Home settings").assertIsDisplayed()
    }

    @Test fun widgetPickerSheetOpens() {
        show(menuItemId = null, widgetPickerOpen = true)
        rule.onNodeWithText("Search widgets").assertIsDisplayed()
    }


}
