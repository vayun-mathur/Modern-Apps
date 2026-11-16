package com.vayunmathur.office.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.odf.OdfFrame
import com.vayunmathur.library.ui.odf.OdfImage
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfShape
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.OdfSpan
import kotlin.io.encoding.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * W4: [FloatingElementLayer] selection chrome — the delete/crop affordances
 * and the move-handle drag commit real bounds changes through the callbacks.
 */
@RunWith(AndroidJUnit4::class)
class W4FloatingHandlesTest {

    @get:Rule val rule = createComposeRule()

    private val png: ByteArray = Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")

    private fun rect(): OdfSlideElement {
        val shape = OdfShape.Rect(
            x = 40f, y = 40f, width = 160f, height = 80f,
            text = listOf(OdfParagraph(listOf(OdfSpan("movable")))),
        )
        return OdfSlideElement.Shape(shape)
    }

    private fun imageFrame(): OdfSlideElement {
        val frame = OdfFrame(
            40f, 160f, 160f, 80f, emptyList(),
            image = OdfImage("Pictures/p.png", png, width = 160f, height = 80f))
        return OdfSlideElement.Frame(frame)
    }

    @Test fun deleteButtonRemovesSelectedElement() {
        var deleted: Int? = null
        rule.setContent {
            FloatingElementLayer(
                elements = listOf(rect()), refW = 400f, refH = 400f,
                editMode = true, selectedIndex = 0, keyPrefix = "w4d",
                onDelete = { deleted = it },
            )
        }
        rule.onNodeWithContentDescription("Delete").performClick()
        assertEquals(0, deleted)
    }

    @Test fun cropButtonFiresForSelectedImageFrame() {
        var cropped: Int? = null
        rule.setContent {
            FloatingElementLayer(
                elements = listOf(imageFrame()), refW = 400f, refH = 400f,
                editMode = true, selectedIndex = 0, keyPrefix = "w4c",
                onCropImage = { cropped = it },
            )
        }
        rule.onNodeWithContentDescription("Crop").performClick()
        assertEquals(0, cropped)
    }

    @Test fun moveHandleDragCommitsNewBounds() {
        var committed: List<Float>? = null
        rule.setContent {
            FloatingElementLayer(
                elements = listOf(rect()), refW = 400f, refH = 400f,
                editMode = true, selectedIndex = 0, keyPrefix = "w4m",
                onBoundsChange = { _, x, y, w, h -> committed = listOf(x, y, w, h) },
            )
        }
        rule.onNodeWithContentDescription("Reorder").performTouchInput {
            swipe(Offset(center.x, center.y), Offset(center.x + 60f, center.y + 30f), 200)
        }
        rule.waitForIdle()
        assertNotNull(committed)
        // Dragging right/down moves the origin but keeps the size.
        assertTrue(committed!![0] > 40f, committed.toString())
        assertTrue(committed!![1] > 40f, committed.toString())
        assertEquals(160f, committed!![2], 1.5f)
        assertEquals(80f, committed!![3], 1.5f)
    }

    @Test fun bodyDragAlsoMovesSelectedShape() {
        var committed: List<Float>? = null
        rule.setContent {
            FloatingElementLayer(
                elements = listOf(rect()), refW = 400f, refH = 400f,
                editMode = true, selectedIndex = 0, keyPrefix = "w4b",
                onBoundsChange = { _, x, y, w, h -> committed = listOf(x, y, w, h) },
            )
        }
        rule.onNodeWithText("movable", substring = true).performTouchInput {
            swipe(Offset(center.x, center.y), Offset(center.x + 50f, center.y), 200)
        }
        rule.waitForIdle()
        assertNotNull(committed)
        assertTrue(committed!![0] > 40f, committed.toString())
    }
}
