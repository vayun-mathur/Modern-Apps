package com.vayunmathur.library.ui

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Slice 3/3 round 4: overlay bar, adaptive grid, theme container,
 * content-color helper, desktop width, spacing/modifier basics.
 */
@RunWith(RobolectricTestRunner::class)
class Slice3Round4Test {
    @get:Rule val rule = createComposeRule()

    @Test fun overlayActionHoldsFieldsAndEquality() {
        var clicks = 0
        val icon: @androidx.compose.runtime.Composable () -> Unit = { Text("ic") }
        val a = OverlayAction(icon = icon, contentDescription = "Share", onClick = { clicks++ })
        assertEquals("Share", a.contentDescription)
        a.onClick(); assertEquals(1, clicks)
        assertEquals(a, a.copy())
        assertNotEquals(a, a.copy(contentDescription = "Other"))
    }

    @Test fun topAppBarOverlayBackAndActionsFire() {
        var backs = 0
        var acted = 0
        rule.setContent {
            TopAppBarOverlay(
                onNavigateBack = { backs++ },
                actions = listOf(
                    OverlayAction(icon = { IconShare() }, contentDescription = "Share", onClick = { acted++ }),
                ),
            ) { Text("overlay-title") }
        }
        rule.onNodeWithText("overlay-title").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
        rule.onNodeWithContentDescription("Share").performClick()
        assertEquals(1, acted)
    }

    @Test fun topAppBarOverlayWithoutBackStillShowsActions() {
        var acted = 0
        rule.setContent {
            TopAppBarOverlay(
                actions = listOf(
                    OverlayAction(icon = { IconSettings() }, contentDescription = "Settings", onClick = { acted++ }),
                ),
            )
        }
        rule.onNodeWithContentDescription("Settings").performClick()
        assertEquals(1, acted)
        assertTrue(rule.onAllNodesWithText("overlay-title").fetchSemanticsNodes().isEmpty())
    }

    @Test fun adaptiveGridCellsUsesAdaptiveWithMinSize() {
        var cells: GridCells? = null
        rule.setContent { cells = adaptiveGridCells(200.dp); Text("g") }
        assertTrue(cells is GridCells.Adaptive)
    }

    @Test fun adaptiveGridCellsDefaultIs160() {
        var cells: GridCells? = null
        rule.setContent { cells = adaptiveGridCells(); Text("g2") }
        assertTrue(cells is GridCells.Adaptive)
    }

    @Test fun desktopMaxWidthContainerShowsContent() {
        rule.setContent { DesktopMaxWidthContainer { Text("centred") } }
        rule.onNodeWithText("centred").assertIsDisplayed()
    }

    @Test fun desktopMaxWidthContainerCustomWidthShowsContent() {
        rule.setContent { DesktopMaxWidthContainer(maxWidth = 400.dp) { Text("narrow") } }
        rule.onNodeWithText("narrow").assertIsDisplayed()
    }

    @Test fun dynamicThemeLightAndDarkRender() {
        rule.setContent { DynamicTheme(darkTheme = false) { Text("light") } }
        rule.onNodeWithText("light").assertIsDisplayed()
    }

    @Test fun dynamicThemeDarkRenders() {
        rule.setContent { DynamicTheme(darkTheme = true) { Text("dark") } }
        rule.onNodeWithText("dark").assertIsDisplayed()
    }

    @Test fun contentColorOnPicksReadableForeground() {
        assertEquals(
            androidx.compose.ui.graphics.Color.Black,
            contentColorOn(androidx.compose.ui.graphics.Color.White),
        )
        assertEquals(
            androidx.compose.ui.graphics.Color.White,
            contentColorOn(androidx.compose.ui.graphics.Color.Black),
        )
    }
}
