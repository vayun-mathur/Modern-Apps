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
import kotlin.test.Test

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
}
