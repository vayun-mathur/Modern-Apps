package com.vayunmathur.photos.photoslice2

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.photos.data.BitmapReference
import com.vayunmathur.photos.data.DrawingLayer
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.GroupInfo
import com.vayunmathur.photos.data.LayerMask
import com.vayunmathur.photos.data.LayerStyle
import com.vayunmathur.photos.data.PixelLayer
import com.vayunmathur.photos.data.TextElement
import com.vayunmathur.photos.data.TextLayer
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
class LayerRowsEditingPass3Test {

    @get:Rule val rule = createComposeRule()

    private fun bmp(): Bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF223344.toInt()) }

    private class Rec {
        var moved: Pair<Int, Int>? = null
        var editText: Int? = null
        var resumeDrawing: Int? = null
        var maskFromSelection: Int? = null
        var invertMask: Int? = null
        var deleteMask: Int? = null
        var clip: Pair<Int, Boolean>? = null
        var styleShadow: Boolean? = null
        var lastStyle: com.vayunmathur.photos.data.LayerStyle? = null
        var groupUpdate: GroupInfo? = null
        var selected: Int? = null
        var visibility: Pair<Int, Boolean>? = null
    }

    private fun panel(doc: EditDocument, rec: Rec, hasSelection: Boolean = true) {
        rule.setContent {
            LayersPanel(
                document = doc,
                hasSelection = hasSelection,
                onSelectLayer = { rec.selected = it },
                onToggleVisibility = { i, v -> rec.visibility = i to v },
                onOpacityChange = { _, _ -> },
                onBlendModeChange = { _, _ -> },
                onAddAdjustment = {},
                onAddPixelLayer = {},
                onDuplicate = {},
                onMergeDown = {},
                onDelete = {},
                onFlatten = {},
                onAddMaskFromSelection = { rec.maskFromSelection = it },
                onDeleteMask = { rec.deleteMask = it },
                onInvertMask = { rec.invertMask = it },
                onToggleClip = { i, v -> rec.clip = i to v },
                onSetStyle = { _, s -> rec.styleShadow = s.dropShadow; rec.lastStyle = s },
                onGroupActive = {},
                onUngroup = {},
                onUpdateGroup = { rec.groupUpdate = it },
                onMoveLayer = { f, t -> rec.moved = f to t },
                onEditText = { rec.editText = it },
                onResumeDrawing = { rec.resumeDrawing = it },
            )
        }
    }

    @Test
    fun `text layer row offers edit text and move chips`() {
        val text = TextLayer(TextElement(id = "t1", text = "Hello", x = 0.1f, y = 0.1f, rotation = 0f, color = 0xFF000000.toInt(), fontSize = 0.2f), name = "Title Text")
        val doc = EditDocument(canvasWidth = 16, canvasHeight = 16)
            .addLayer(PixelLayer(BitmapReference(bmp()), name = "Base"))
            .addLayer(text)
        val rec = Rec()
        panel(doc.setActiveLayer(1), rec)
        rule.onNodeWithText("Edit Text").performClick()
        assertEquals(1, rec.editText)
        rule.onNodeWithText("Move ↓").performClick()
        assertEquals(1 to 0, rec.moved)
        // No mask yet: Add Mask is enabled by the selection.
        rule.onNodeWithText("Add Mask").performClick()
        assertEquals(1, rec.maskFromSelection)
        rule.onNodeWithText("Shadow").performClick()
        assertEquals(true, rec.styleShadow)
        rule.onNodeWithText("Clip to below").performClick()
        assertEquals(1 to true, rec.clip)
    }

    @Test
    fun `drawing layer row offers edit strokes`() {
        val drawing = DrawingLayer(strokes = emptyList(), sourceWidth = 16f, sourceHeight = 16f, name = "Sketch")
        val doc = EditDocument(canvasWidth = 16, canvasHeight = 16)
            .addLayer(PixelLayer(BitmapReference(bmp()), name = "Base"))
            .addLayer(drawing)
        val rec = Rec()
        panel(doc.setActiveLayer(1), rec)
        rule.onNodeWithText("Edit Strokes").performClick()
        assertEquals(1, rec.resumeDrawing)
    }

    @Test
    fun `masked layer offers invert and delete mask`() {
        val masked = PixelLayer(BitmapReference(bmp()), name = "Masked", mask = LayerMask(FloatArray(64) { 1f }, 8, 8))
        val doc = EditDocument(canvasWidth = 16, canvasHeight = 16)
            .addLayer(PixelLayer(BitmapReference(bmp()), name = "Base"))
            .addLayer(masked)
        val rec = Rec()
        panel(doc.setActiveLayer(1), rec)
        rule.onNodeWithText("Invert Mask").performClick()
        assertEquals(1, rec.invertMask)
        rule.onNodeWithText("Delete Mask").performClick()
        assertEquals(1, rec.deleteMask)
    }

    @Test
    fun `grouped layers show a group header`() {
        val groupedA = PixelLayer(BitmapReference(bmp()), name = "Grouped A", groupId = "g1")
        val groupedB = PixelLayer(BitmapReference(bmp()), name = "Grouped B", groupId = "g1")
        var doc = EditDocument(canvasWidth = 16, canvasHeight = 16, groups = listOf(GroupInfo(id = "g1", name = "My Group")))
            .addLayer(groupedA)
            .addLayer(groupedB)
        doc = doc.setActiveLayer(1)
        val rec = Rec()
        panel(doc, rec)
        rule.onNodeWithText("My Group", substring = true).assertExists()
        assertNotNull(doc.groupInfo("g1"))
        assertTrue(doc.layers.all { it.groupId == "g1" || it.name == "Base" })
    }

    @Test
    fun `styled active layer shows checked style chips`() {
        val styled = PixelLayer(BitmapReference(bmp()), name = "Styled", style = LayerStyle(dropShadow = true, stroke = true, outerGlow = true))
        val doc = EditDocument(canvasWidth = 16, canvasHeight = 16).addLayer(styled)
        val rec = Rec()
        panel(doc.setActiveLayer(0), rec)
        rule.onNodeWithText("✓ Shadow").assertExists()
        rule.onNodeWithText("✓ Stroke").assertExists()
        rule.onNodeWithText("✓ Glow").performClick()
        assertEquals(false, rec.lastStyle?.outerGlow)
    }
}
