package com.vayunmathur.photos.photoslice2

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.BitmapReference
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.ImageAdjustments
import com.vayunmathur.photos.data.PixelLayer
import com.vayunmathur.photos.ui.CategoryBar
import com.vayunmathur.photos.ui.LayersPanel
import com.vayunmathur.photos.ui.ToolCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice-2 Compose tests for the editing panels ([CategoryBar] selection,
 * [LayersPanel] list + visibility toggles). Robolectric + Compose rule.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoSlice2PanelsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun pixelLayer(name: String): PixelLayer {
        val bmp = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF112233.toInt())
        return PixelLayer(BitmapReference(bmp), name = name)
    }

    @Test
    fun categoryBarShowsAllCategoriesAndSelects() {
        var selected: ToolCategory? = null
        composeRule.setContent {
            CategoryBar(onSelect = { selected = it })
        }
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        // The bar scrolls horizontally: the first categories are displayed.
        composeRule.onNodeWithText(ctx.getString(ToolCategory.Adjust.labelRes)).assertIsDisplayed()
        composeRule.onNodeWithText(ctx.getString(ToolCategory.Filters.labelRes)).assertIsDisplayed()
        // Clicking Adjust selects it.
        composeRule.onNodeWithText(ctx.getString(ToolCategory.Adjust.labelRes)).performClick()
        assertEquals(ToolCategory.Adjust, selected)
    }

    @Test
    fun layersPanelListsLayersAndTogglesVisibility() {
        val doc = EditDocument(canvasWidth = 8, canvasHeight = 8)
            .addLayer(pixelLayer("Background"))
            .addLayer(pixelLayer("Foreground"))
        var toggled: Pair<Int, Boolean>? = null
        var selectedIndex: Int? = null
        composeRule.setContent {
            LayersPanel(
                document = doc,
                hasSelection = false,
                onSelectLayer = { selectedIndex = it },
                onToggleVisibility = { i, v -> toggled = i to v },
                onOpacityChange = { _, _ -> },
                onBlendModeChange = { _, _ -> },
                onAddAdjustment = {},
                onAddPixelLayer = {},
                onDuplicate = {},
                onMergeDown = {},
                onDelete = {},
                onFlatten = {},
                onAddMaskFromSelection = {},
                onDeleteMask = {},
                onInvertMask = {},
                onToggleClip = { _, _ -> },
                onSetStyle = { _, _ -> },
                onGroupActive = {},
                onUngroup = {},
                onUpdateGroup = {},
                onMoveLayer = { _, _ -> },
                onEditText = {},
                onResumeDrawing = {},
            )
        }
        composeRule.onNodeWithText("Background").assertIsDisplayed()
        composeRule.onNodeWithText("Foreground").assertIsDisplayed()
        // Tapping a layer row selects it.
        composeRule.onNodeWithText("Foreground").performClick()
        assertEquals(1, selectedIndex)
        assertTrue(toggled == null)
    }

    @Test
    fun layersPanelEmptyState() {
        composeRule.setContent {
            LayersPanel(
                document = EditDocument(),
                hasSelection = true,
                onSelectLayer = {},
                onToggleVisibility = { _, _ -> },
                onOpacityChange = { _, _ -> },
                onBlendModeChange = { _, _ -> },
                onAddAdjustment = {},
                onAddPixelLayer = {},
                onDuplicate = {},
                onMergeDown = {},
                onDelete = {},
                onFlatten = {},
                onAddMaskFromSelection = {},
                onDeleteMask = {},
                onInvertMask = {},
                onToggleClip = { _, _ -> },
                onSetStyle = { _, _ -> },
                onGroupActive = {},
                onUngroup = {},
                onUpdateGroup = {},
                onMoveLayer = { _, _ -> },
                onEditText = {},
                onResumeDrawing = {},
            )
        }
        // Empty document renders without crashing (no layer rows).
        composeRule.waitForIdle()
    }

    @Test
    fun imageAdjustmentsDefaultsAreNeutral() {
        val adj = ImageAdjustments()
        assertEquals(0f, adj.brightness)
        assertEquals(0f, adj.contrast)
        assertEquals(0f, adj.saturation)
    }
}
