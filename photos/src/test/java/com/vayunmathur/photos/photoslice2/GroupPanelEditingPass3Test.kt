package com.vayunmathur.photos.photoslice2

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.photos.data.BitmapReference
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.GroupInfo
import com.vayunmathur.photos.data.LayerBlendMode
import com.vayunmathur.photos.data.PixelLayer
import com.vayunmathur.photos.ui.LayersPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h1400dp")
class GroupPanelEditingPass3Test {

    @get:Rule val rule = createComposeRule()

    private fun groupedDoc(collapsed: Boolean = false): Pair<EditDocument, GroupInfo> {
        val group = GroupInfo(name = "My Group", collapsed = collapsed)
        fun layer(name: String) = PixelLayer(
            BitmapReference(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)),
            name = name,
            groupId = group.id,
        )
        val doc = EditDocument(
            layers = listOf(layer("Grouped A"), layer("Grouped B")),
            activeLayerIndex = 0,
            canvasWidth = 16,
            canvasHeight = 16,
            groups = listOf(group),
        )
        return doc to group
    }

    private fun render(doc: EditDocument, onUpdate: (GroupInfo) -> Unit) {
        rule.setContent {
            LayersPanel(
                document = doc,
                hasSelection = false,
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
                onUpdateGroup = onUpdate,
                onMoveLayer = { _, _ -> },
                onEditText = {},
                onResumeDrawing = {},
            )
        }
    }

    @Test
    fun `group header shows members and collapse toggle emits`() {
        val (doc, group) = groupedDoc()
        var updated: GroupInfo? = null
        render(doc) { updated = it }
        rule.onNodeWithText("Grouped A", substring = true).assertIsDisplayed()
        rule.onNodeWithText("▾").performClick()
        assertEquals(group.id, updated?.id)
        assertTrue(updated!!.collapsed)
    }

    @Test
    fun `collapsed group hides its members`() {
        val (doc, _) = groupedDoc(collapsed = true)
        render(doc) { }
        rule.onNodeWithText("▸").assertIsDisplayed()
        rule.onAllNodesWithText("Grouped A", substring = true)
            .fetchSemanticsNodes().let { assertTrue(it.isEmpty(), "collapsed members hidden") }
    }

    @Test
    fun `group visibility toggle emits`() {
        val (doc, group) = groupedDoc()
        var updated: GroupInfo? = null
        render(doc) { updated = it }
        rule.onNodeWithText("👁").performClick()
        assertEquals(group.id, updated?.id)
        assertTrue(!updated!!.visible)
    }

    @Test
    fun `group blend menu emits the chosen mode`() {
        val (doc, group) = groupedDoc()
        var updated: GroupInfo? = null
        render(doc) { updated = it }
        // The group header's blend chip shows the current mode label;
        // it is the last exact "Normal" chip (the active-layer chip is first).
        val normals = rule.onAllNodesWithText("Normal", substring = false)
        normals[normals.fetchSemanticsNodes().size - 1].performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Multiply", substring = true).performClick()
        assertEquals(LayerBlendMode.Multiply, updated?.blendMode)
        assertEquals(group.id, updated?.id)
    }
}
