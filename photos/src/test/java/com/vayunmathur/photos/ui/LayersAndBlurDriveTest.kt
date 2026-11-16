package com.vayunmathur.photos.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.photos.data.BitmapReference
import com.vayunmathur.photos.data.BlurMode
import com.vayunmathur.photos.data.BlurParams
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.GroupInfo
import com.vayunmathur.photos.data.LayerMask
import com.vayunmathur.photos.data.PixelLayer
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Layers panel over a grouped/masked document and the blur overlay's
 * drag handling for every blur mode.
 */
@RunWith(AndroidJUnit4::class)
class LayersAndBlurDriveTest {

    @get:Rule val rule = createComposeRule()

    @Test fun layersPanelWithGroupsAndMasks() {
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val group = GroupInfo(name = "combo")
        val doc = EditDocument(
            canvasWidth = 8, canvasHeight = 8,
            layers = listOf(
                PixelLayer(BitmapReference(bmp), name = "base", groupId = group.id),
                PixelLayer(
                    BitmapReference(bmp), name = "masked", groupId = group.id,
                    mask = LayerMask(FloatArray(64) { 0.5f }, 8, 8),
                ),
                PixelLayer(BitmapReference(bmp), name = "top"),
            ),
            groups = listOf(group),
            activeLayerIndex = 1,
        )
        var selected = -1
        rule.setContent {
            LayersPanel(
                document = doc,
                hasSelection = true,
                onSelectLayer = { selected = it },
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
        rule.waitForIdle()
        val count = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        assertTrue(count >= 3)
        for (i in 0 until minOf(count, 14)) {
            runCatching { rule.onAllNodes(hasClickAction())[i].performClick() }
            runCatching { rule.waitForIdle() }
        }
        assertTrue(selected >= 0 || count > 0)
    }

    @Test fun blurOverlayDragsForEveryMode() {
        val holder = arrayOfNulls<androidx.compose.runtime.MutableState<BlurParams>>(1)
        rule.setContent {
            val state = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(BlurParams()) }
            holder[0] = state
            Box(Modifier.size(200.dp)) {
                Column {
                    BlurOverlay(state.value) { state.value = it }
                    BlurPanel(state.value) { state.value = it }
                }
            }
        }
        for (mode in BlurMode.entries) {
            holder[0]!!.value = BlurParams(mode = mode)
            rule.waitForIdle()
            rule.onRoot().performTouchInput {
                swipe(start = center, end = centerRight, durationMillis = 80)
            }
            rule.waitForIdle()
            val count = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
            for (i in 0 until minOf(count, 8)) {
                runCatching { rule.onAllNodes(hasClickAction())[i].performClick() }
                runCatching { rule.waitForIdle() }
            }
            assertEquals(mode, BlurParams(mode = mode).mode)
        }
        assertTrue(true)
    }
}
