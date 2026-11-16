package com.vayunmathur.photos.data

import android.graphics.Bitmap
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [EditDocument] layer algebra: duplication per layer kind, group
 * bookkeeping, and boundary behaviour of the stack operations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditDocumentAlgebraTest {

    private fun bmp() = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)

    private fun doc(): EditDocument {
        val layers = listOf(
            PixelLayer(BitmapReference(bmp()), name = "pixels"),
            AdjustmentLayer(BasicAdjustment(ImageAdjustments(brightness = 0.1f)), name = "adj"),
            TextLayer(
                TextElement(id = "t", text = "hi", x = 0f, y = 0f, rotation = 0f, color = 0, fontSize = 10f),
                name = "text",
            ),
            DrawingLayer(strokes = emptyList(), sourceWidth = 8f, sourceHeight = 8f, name = "draw"),
        )
        return EditDocument(canvasWidth = 8, canvasHeight = 8, layers = layers, activeLayerIndex = 3)
    }

    @Test fun duplicateEveryLayerKind() {
        var current = doc()
        for (index in current.layers.indices) {
            val before = current.layers.size
            current = current.duplicateLayer(index)
            assertEquals(before + 1, current.layers.size)
            assertEquals(index + 1, current.activeLayerIndex)
            assertNotEquals(current.layers[index].id, current.layers[index + 1].id)
            assertTrue(current.layers[index + 1].name.endsWith("copy"))
        }
        // Out-of-range duplication is a no-op.
        assertEquals(current, current.duplicateLayer(99))
        assertEquals(current, current.duplicateLayer(-1))
    }

    @Test fun stackBoundariesAndGrouping() {
        var current = doc()
        assertEquals(current, current.removeLayer(99))
        // moveLayer coerces the target into range instead of no-oping.
        assertEquals(current.layers.first().id, current.moveLayer(0, 99).layers.last().id)
        // Out-of-range active indices are coerced into the stack.
        assertTrue(current.setActiveLayer(99).activeLayerIndex in current.layers.indices)
        current = current.moveLayer(0, 2)
        assertEquals("adj", current.layers[0].name)
        current = current.updateLayer(0) { it.copyBase(name = "renamed") }
        assertEquals("renamed", current.layers[0].name)
        current = current.updateActiveLayer { it.copyBase(name = "active") }
        assertEquals("active", current.activeLayer?.name)
        assertNull(current.groupInfo(null))
        assertNull(current.groupInfo("missing"))
        // Group active (index 3) with below, rename the group, ungroup.
        current = current.groupActiveWithBelow()
        val group = current.groups.single()
        assertEquals(group, current.groupInfo(group.id))
        current = current.updateGroup(group.copy(name = "combo"))
        assertEquals("combo", current.groupInfo(group.id)?.name)
        current = current.ungroupActive()
        assertTrue(current.groups.isEmpty())
        assertTrue(current.layers.all { it.groupId == null })
        // Adding below the top inserts directly above the active layer.
        current = current.addLayer(PixelLayer(BitmapReference(bmp()), name = "inserted"), onTop = false)
        assertEquals("inserted", current.activeLayer?.name)
    }
}
