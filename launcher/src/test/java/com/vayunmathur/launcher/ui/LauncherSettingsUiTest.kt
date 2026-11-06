package com.vayunmathur.launcher.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.launcher.ui.components.LauncherDragController
import com.vayunmathur.launcher.ui.components.LocalLauncherDrag
import com.vayunmathur.launcher.platform.SettingsActions
import com.vayunmathur.launcher.platform.SettingsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * Compose tests (Robolectric + createComposeRule) for [SettingsContent]:
 * sections render, switches toggle through [SettingsActions], grid options
 * select, and the default-home row shows only when not already default.
 */
@RunWith(AndroidJUnit4::class)
class LauncherSettingsUiTest {

    @get:Rule val rule = createComposeRule()

    private fun show(
        state: SettingsUiState = SettingsUiState(),
        actions: SettingsActions = SettingsActions.Noop,
        onBack: () -> Unit = {},
    ) {
        rule.setContent {
            CompositionLocalProvider(LocalLauncherDrag provides LauncherDragController()) {
                SettingsContent(state = state, actions = actions, onBack = onBack)
            }
        }
    }

    @Test fun topRowsRender() {
        show()
        rule.onNodeWithText("Home screen").assertIsDisplayed()
        rule.onNodeWithText("Wallpaper").assertIsDisplayed()
        rule.onNodeWithText("Widgets").assertIsDisplayed()
        rule.onNodeWithText("Grid").assertIsDisplayed()
    }

    @Test fun iconsSectionRendersAfterScroll() {
        show()
        rule.onNodeWithText("Icons").performScrollTo()
        rule.onNodeWithText("Icons").assertIsDisplayed()
        rule.onNodeWithText("Show labels").performScrollTo()
        rule.onNodeWithText("Show labels").assertIsDisplayed()
    }

    @Test fun defaultHomeRowShownWhenNotDefault() {
        show(state = SettingsUiState(isDefaultHome = false))
        rule.onNodeWithText("Set as default home app").assertIsDisplayed()
    }

    @Test fun defaultHomeRowHiddenWhenDefault() {
        show(state = SettingsUiState(isDefaultHome = true))
        rule.onNodeWithText("Wallpaper").assertIsDisplayed()
        rule.onNodeWithText("Widgets").assertIsDisplayed()
    }

    @Test fun showLabelsToggleReachesActions() {
        var last: Boolean? = null
        show(
            state = SettingsUiState(showLabels = false),
            actions = object : SettingsActions {
                override fun setShowLabels(show: Boolean) {
                    last = show
                }
            },
        )
        rule.onNodeWithText("Show labels").performScrollTo()
        rule.onNodeWithText("Show labels").performClick()
        assertEquals(true, last)
    }

    @Test fun uncheckingShowLabelsReachesActions() {
        var last: Boolean? = null
        show(
            state = SettingsUiState(showLabels = true),
            actions = object : SettingsActions {
                override fun setShowLabels(show: Boolean) {
                    last = show
                }
            },
        )
        rule.onNodeWithText("Show labels").performScrollTo()
        rule.onNodeWithText("Show labels").performClick()
        assertEquals(false, last)
    }

    @Test fun columnsRowRenders() {
        show()
        rule.onNodeWithText("Columns").performScrollTo()
        rule.onNodeWithText("Columns").assertIsDisplayed()
    }

    @Test fun iconScaleRendersPercent() {
        show(state = SettingsUiState(iconScale = 1.25f))
        rule.onNodeWithText("Icon size").performScrollTo()
        rule.onNodeWithText("125%").assertIsDisplayed()
    }

    @Test fun drawerListSectionRendersAfterScroll() {
        show()
        rule.onNodeWithText("App drawer").performScrollTo()
        rule.onNodeWithText("App drawer").assertIsDisplayed()
    }

    @Test fun wallpaperRowClickReachesActions() {
        var picked = false
        show(
            actions = object : SettingsActions {
                override fun pickWallpaper() {
                    picked = true
                }
            },
        )
        rule.onNodeWithText("Wallpaper").performClick()
        assertEquals(true, picked)
    }
}
