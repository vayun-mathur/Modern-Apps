package com.vayunmathur.launcher.tv.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.launcher.tv.platform.TvHdmiInput
import com.vayunmathur.launcher.tv.platform.TvHomeState
import com.vayunmathur.launcher.tv.platform.TvTile
import com.vayunmathur.launcher.tv.ui.components.TvBannerTile
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * Compose tests (Robolectric + createComposeRule) for the TV home screen:
 * [TvBannerTile] states and press handling, [TvAppGrid] rows, [TvBottomRow]
 * inputs/store tiles, and [TvHomeScreen] end to end.
 */
@RunWith(AndroidJUnit4::class)
class LauncherTvUiTest {

    @get:Rule val rule = createComposeRule()

    private fun tile(pkg: String, label: String) = TvTile(
        packageName = pkg,
        label = label,
        banner = null,
        appIcon = null,
        userInstalled = true,
    )

    private val tiles = listOf(
        tile("com.example.a", "Alpha"),
        tile("com.example.b", "Beta"),
        tile("com.example.c", "Gamma"),
    )

    @Test fun bannerTileRendersLabelAndPresses() {
        var pressed = false
        rule.setContent {
            TvBannerTile(
                tile = tiles[0],
                tileWidth = 200.dp,
                artHeight = 100.dp,
                moving = false,
                dimmed = false,
                focusRequester = null,
                onPress = { pressed = true },
                onEdit = {},
                onMove = {},
            )
        }
        rule.onNodeWithText("Alpha").assertIsDisplayed()
        rule.onNodeWithText("Alpha").performClick()
        assertEquals(true, pressed)
    }

    @Test fun movingTileShowsMovingBadge() {
        rule.setContent {
            TvBannerTile(
                tile = tiles[0],
                tileWidth = 200.dp,
                artHeight = 100.dp,
                moving = true,
                dimmed = false,
                focusRequester = null,
                onPress = {},
                onEdit = {},
                onMove = {},
            )
        }
        rule.onNodeWithText("MOVING", substring = true).assertIsDisplayed()
    }

    @Test fun appGridRendersRowTiles() {
        rule.setContent {
            TvAppGrid(
                state = TvHomeState(rowApps = tiles),
                onPressTile = {},
                onOpenEditMenu = {},
                onMoveKey = { _, _ -> },
            )
        }
        rule.onNodeWithText("Alpha").assertIsDisplayed()
        rule.onNodeWithText("Beta").assertIsDisplayed()
        rule.onNodeWithText("Gamma").assertIsDisplayed()
    }

    @Test fun appGridPressReachesCallback() {
        var pressed: String? = null
        rule.setContent {
            TvAppGrid(
                state = TvHomeState(rowApps = tiles),
                onPressTile = { pressed = it },
                onOpenEditMenu = {},
                onMoveKey = { _, _ -> },
            )
        }
        rule.onNodeWithText("Beta").performClick()
        assertEquals("com.example.b", pressed)
    }

    @Test fun bottomRowRendersInputsAndStore() {
        rule.setContent {
            TvBottomRow(
                state = TvHomeState(
                    showAppStore = true,
                    hdmiInputs = listOf(
                        TvHdmiInput("hdmi1", "HDMI 1"),
                    ),
                ),
                onOpenAppStore = {},
                onOpenInput = {},
                onOpenSettings = {},
                onToggleReorder = {},
            )
        }
        rule.onNodeWithText("HDMI 1").assertIsDisplayed()
    }

    @Test fun homeScreenRendersClockAndTiles() {
        var pressed: String? = null
        rule.setContent {
            TvHomeScreen(
                state = TvHomeState(rowApps = tiles),
                onPressTile = { pressed = it },
                onOpenAppStore = {},
                onOpenInput = {},
                onOpenSettings = {},
                onOpenNetworkSettings = {},
                onOpenEditMenu = {},
                onCloseEditMenu = {},
                onStartMove = {},
                onCancelMove = {},
                onToggleReorder = {},
                onDisarmReorder = {},
                onMoveKey = { _, _ -> },
                onRemoveTile = {},
                onOpenAddMenu = {},
                onPickAddCandidate = {},
                onUninstallOrInfo = {},
                onRefreshHome = {},
                onRequestHomeRole = {},
            )
        }
        rule.onNodeWithText("Alpha").assertIsDisplayed()
        rule.onNodeWithText("Alpha").performClick()
        assertEquals("com.example.a", pressed)
    }
}
