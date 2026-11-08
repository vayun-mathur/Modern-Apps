package com.vayunmathur.photos.photoslice2

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.photos.data.BitmapReference
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.LayerAdjustment
import com.vayunmathur.photos.data.LayerBlendMode
import com.vayunmathur.photos.data.PixelLayer
import com.vayunmathur.photos.ui.LayersPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h1400dp")
class LayersPanelEditingPass3Test {

    @get:Rule val rule = createComposeRule()

    private fun pixelLayer(name: String): PixelLayer {
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF112233.toInt())
        return PixelLayer(BitmapReference(bmp), name = name)
    }

    private class Actions {
        var addedAdjustment: LayerAdjustment? = null
        var addedPixel = 0
        var duplicated: Int? = null
        var merged: Int? = null
        var deleted: Int? = null
        var flattened = 0
        var blend: Pair<Int, LayerBlendMode>? = null
        var opacity: Pair<Int, Float>? = null
    }

    private fun panel(doc: EditDocument, actions: Actions, selected: Int = 0) {
        // LayersPanel reads the active layer from the document itself.
        val withActive = doc.setActiveLayer(selected)
        rule.setContent {
            LayersPanel(
                document = withActive,
                hasSelection = true,
                onSelectLayer = {},
                onToggleVisibility = { _, _ -> },
                onOpacityChange = { i, v -> actions.opacity = i to v },
                onBlendModeChange = { i, m -> actions.blend = i to m },
                onAddAdjustment = { actions.addedAdjustment = it },
                onAddPixelLayer = { actions.addedPixel++ },
                onDuplicate = { actions.duplicated = it },
                onMergeDown = { actions.merged = it },
                onDelete = { actions.deleted = it },
                onFlatten = { actions.flattened++ },
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
    }

    @Test
    fun `adjustment menu offers and emits an adjustment`() {
        val doc = EditDocument(canvasWidth = 16, canvasHeight = 16).addLayer(pixelLayer("Base Layer"))
        val actions = Actions()
        panel(doc, actions)
        rule.onNodeWithText("+ Adjustment").performClick()
        rule.waitForIdle()
        // First adjustment entry in the menu (Basic adjustments).
        val nodes = rule.onAllNodesWithText("Brightness", substring = true).fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty(), "adjustment menu entries shown")
        rule.onAllNodesWithText("Brightness", substring = true)[0].performClick()
        assertNotNull(actions.addedAdjustment)
    }

    @Test
    fun `blend mode menu emits the chosen mode`() {
        val doc = EditDocument(canvasWidth = 16, canvasHeight = 16)
            .addLayer(pixelLayer("Base Layer"))
            .addLayer(pixelLayer("Top Layer"))
        val actions = Actions()
        panel(doc, actions, selected = 1)
        rule.onAllNodesWithText("Normal", substring = false)[0].performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Multiply", substring = true).performClick()
        assertEquals(LayerBlendMode.Multiply, actions.blend?.second)
    }

    @Test
    fun `action chips emit duplicate merge flatten delete and add layer`() {
        val doc = EditDocument(canvasWidth = 16, canvasHeight = 16)
            .addLayer(pixelLayer("Chip Base"))
            .addLayer(pixelLayer("Chip Top"))
        val actions = Actions()
        panel(doc, actions, selected = 1)
        rule.onNodeWithText("Duplicate").performClick()
        assertEquals(1, actions.duplicated)
        rule.onNodeWithText("Merge ↓").performClick()
        assertEquals(1, actions.merged)
        rule.onNodeWithText("Flatten").performClick()
        assertEquals(1, actions.flattened)
        rule.onNodeWithText("Delete").performClick()
        assertEquals(1, actions.deleted)
        rule.onNodeWithText("+ Layer").performClick()
        assertEquals(1, actions.addedPixel)
    }

    @Test
    fun `panel shows active layer opacity percent`() {
        val doc = EditDocument(canvasWidth = 16, canvasHeight = 16)
            .addLayer(pixelLayer("Opacity Layer").copy(opacity = 0.5f))
        val actions = Actions()
        panel(doc, actions)
        rule.onAllNodesWithText("50", substring = true)[0].assertIsDisplayed()
        assertEquals("Opacity Layer", doc.layers[0].name)
    }
}
