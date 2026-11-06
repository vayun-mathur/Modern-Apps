package com.vayunmathur.launcher.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.launcher.ui.components.LauncherDragController
import com.vayunmathur.launcher.ui.components.LocalLauncherDrag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * Compose tests (Robolectric + createComposeRule) for
 * [WorkspaceOptionsContent]: the long-press-on-wallpaper menu offers
 * Wallpaper, Widgets, Apps list and Settings, each reaching its callback.
 */
@RunWith(AndroidJUnit4::class)
class LauncherWorkspaceOptionsTest {

    @get:Rule val rule = createComposeRule()

    private fun show(
        onPickWallpaper: () -> Unit = {},
        onOpenWidgets: () -> Unit = {},
        onOpenAppsList: () -> Unit = {},
        onOpenSettings: () -> Unit = {},
    ) {
        rule.setContent {
            CompositionLocalProvider(LocalLauncherDrag provides LauncherDragController()) {
                WorkspaceOptionsContent(
                    onPickWallpaper = onPickWallpaper,
                    onOpenWidgets = onOpenWidgets,
                    onOpenAppsList = onOpenAppsList,
                    onOpenSettings = onOpenSettings,
                )
            }
        }
    }

    @Test fun allRowsRender() {
        show()
        rule.onNodeWithText("Wallpaper & style").assertIsDisplayed()
        rule.onNodeWithText("Widgets").assertIsDisplayed()
        rule.onNodeWithText("Apps list").assertIsDisplayed()
        rule.onNodeWithText("Home settings").assertIsDisplayed()
    }

    @Test fun wallpaperRowReachesCallback() {
        var picked = false
        show(onPickWallpaper = { picked = true })
        rule.onNodeWithText("Wallpaper & style").performClick()
        assertEquals(true, picked)
    }

    @Test fun widgetsRowReachesCallback() {
        var opened = false
        show(onOpenWidgets = { opened = true })
        rule.onNodeWithText("Widgets").performClick()
        assertEquals(true, opened)
    }

    @Test fun appsListRowReachesCallback() {
        var opened = false
        show(onOpenAppsList = { opened = true })
        rule.onNodeWithText("Apps list").performClick()
        assertEquals(true, opened)
    }

    @Test fun settingsRowReachesCallback() {
        var opened = false
        show(onOpenSettings = { opened = true })
        rule.onNodeWithText("Home settings").performClick()
        assertEquals(true, opened)
    }
}
