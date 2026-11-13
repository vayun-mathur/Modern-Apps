package com.vayunmathur.games.voxels.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h400dp-land")
class InventoryOverlayTest {
    @get:Rule val rule = createComposeRule()

    private fun show() = rule.setContent {
        InventoryOverlay(
            inventoryJson = """{"selected":0,"slots":[]}""", recipesJson = "[]",
            blessingsJson = """{"slots":[]}""", blessingCatalogJson = "[]", onClose = {},
        )
    }

    // Only three of the eight catalog tabs fit the column; the rest used to be unreachable.
    @Test fun `every catalog tab can be scrolled to and opened`() {
        show()
        for (tab in listOf("Natural", "Ores", "Ocean", "Items", "Gear", "Music", "Build")) {
            rule.onNodeWithText(tab).performScrollTo().assertIsDisplayed().performClick()
        }
    }

    // Nine slots plus gaps must fit whatever width the grid gets, within a usable size range.
    @Test fun `inventory slots shrink to fit narrow panels`() {
        assertEquals(46.dp, inventorySlotSize(600.dp), "wide panels keep the full-size slot")
        val narrow = inventorySlotSize(300.dp)
        assertTrue(narrow * 9 + 4.dp * 8 <= 300.dp, "9 slots overflow a 300 dp grid: $narrow")
        assertTrue(narrow >= 26.dp)
    }
}
