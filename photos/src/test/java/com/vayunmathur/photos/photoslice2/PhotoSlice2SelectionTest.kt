package com.vayunmathur.photos.photoslice2

import com.vayunmathur.photos.data.LayerMask
import com.vayunmathur.photos.data.Selection
import com.vayunmathur.photos.data.SelectionCombine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Slice-2 tests for [Selection] (mask rasterization, feather, combine) and
 * [LayerMask] (invert, full). Pure float-array math — no framework needed.
 */
class PhotoSlice2SelectionTest {

    @Test
    fun emptySelectionDetects() {
        val sel = Selection(FloatArray(16), 4, 4)
        assertTrue(sel.isEmpty())
        val full = Selection(FloatArray(16) { 1f }, 4, 4)
        assertFalse(full.isEmpty())
    }

    @Test
    fun invertFlips() {
        val sel = Selection(floatArrayOf(0f, 0.25f, 0.75f, 1f), 2, 2)
        val inv = sel.invert()
        assertEquals(listOf(1f, 0.75f, 0.25f, 0f), inv.mask.toList())
        assertEquals(2, inv.width)
        assertEquals(2, inv.height)
    }

    @Test
    fun featherZeroCopies() {
        val sel = Selection(floatArrayOf(0f, 1f, 1f, 0f), 2, 2)
        val feathered = sel.applyFeather(0f)
        assertEquals(sel.mask.toList(), feathered.mask.toList())
        assertEquals(0f, feathered.featherRadius)
    }

    @Test
    fun featherBlursHardEdge() {
        // 8x1 hard edge: left half 0, right half 1. Feather radius 1 softens.
        val mask = FloatArray(8) { if (it < 4) 0f else 1f }
        val sel = Selection(mask, 8, 1)
        val feathered = sel.applyFeather(1f)
        // Interior far from edge unchanged-ish; edge pixels become fractional.
        assertTrue(feathered.mask[0] < 0.05f)
        assertTrue(feathered.mask[7] > 0.95f)
        assertTrue(feathered.mask[3] in 0.2f..0.5f, "left edge=${feathered.mask[3]}")
        assertTrue(feathered.mask[4] in 0.5f..0.8f, "right edge=${feathered.mask[4]}")
    }

    @Test
    fun toLayerMaskCopies() {
        val sel = Selection(floatArrayOf(0.5f, 0.5f), 2, 1)
        val mask = sel.toLayerMask()
        assertEquals(2, mask.width)
        assertEquals(listOf(0.5f, 0.5f), mask.alphaData.toList())
    }

    @Test
    fun combineNewReturnsOther() {
        val a = Selection(floatArrayOf(1f, 0f), 2, 1)
        val b = Selection(floatArrayOf(0f, 1f), 2, 1)
        assertEquals(b, a.combine(b, SelectionCombine.New))
    }

    @Test
    fun combineAddTakesMax() {
        val a = Selection(floatArrayOf(0.2f, 0.8f), 2, 1)
        val b = Selection(floatArrayOf(0.6f, 0.3f), 2, 1)
        val out = a.combine(b, SelectionCombine.Add)
        assertEquals(listOf(0.6f, 0.8f), out.mask.toList())
    }

    @Test
    fun combineSubtractAndIntersect() {
        val a = Selection(floatArrayOf(1f, 1f), 2, 1)
        val b = Selection(floatArrayOf(1f, 0f), 2, 1)
        assertEquals(listOf(0f, 1f), a.combine(b, SelectionCombine.Subtract).mask.toList())
        assertEquals(listOf(1f, 0f), a.combine(b, SelectionCombine.Intersect).mask.toList())
    }

    @Test
    fun combineResamplesDifferentSizes() {
        val big = Selection(FloatArray(16) { 1f }, 4, 4)
        val small = Selection(floatArrayOf(0f), 1, 1)
        val out = big.combine(small, SelectionCombine.Intersect)
        assertEquals(16, out.mask.size)
        assertTrue(out.mask.all { it == 0f })
    }

    @Test
    fun rectangleRasterizes() {
        val sel = Selection.rectangle(4, 4, 0.25f, 0.25f, 0.75f, 0.75f)
        // Center 2x2 set.
        assertEquals(1f, sel.mask[1 * 4 + 1])
        assertEquals(1f, sel.mask[2 * 4 + 2])
        assertEquals(0f, sel.mask[0])
        assertEquals(0f, sel.mask[15])
        assertFalse(sel.isEmpty())
    }

    @Test
    fun ellipseRasterizes() {
        val sel = Selection.ellipse(8, 8, 0.5f, 0.5f, 0.5f, 0.5f)
        assertEquals(1f, sel.mask[4 * 8 + 4])
        assertEquals(0f, sel.mask[0])
        // Degenerate radii select nothing.
        assertTrue(Selection.ellipse(4, 4, 0.5f, 0.5f, 0f, 0f).isEmpty())
    }

    @Test
    fun polygonRasterizesTriangle() {
        val sel = Selection.polygon(
            8, 8,
            listOf(0f to 0f, 1f to 0f, 0.5f to 1f),
        )
        // Top row mostly inside, bottom corners outside.
        assertEquals(1f, sel.mask[1 * 8 + 4])
        assertEquals(0f, sel.mask[7 * 8 + 0])
        assertEquals(0f, sel.mask[7 * 8 + 7])
        // Too few vertices selects nothing.
        assertTrue(Selection.polygon(4, 4, listOf(0f to 0f, 1f to 1f)).isEmpty())
    }

    @Test
    fun selectionEqualityIsContentBased() {
        val a = Selection(floatArrayOf(1f, 0f), 2, 1)
        val b = Selection(floatArrayOf(1f, 0f), 2, 1)
        val c = Selection(floatArrayOf(0f, 1f), 2, 1)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == c)
        assertFalse(a.equals("not a selection"))
    }

    @Test
    fun layerMaskInvertAndFull() {
        val mask = LayerMask(floatArrayOf(0f, 0.5f, 1f), 3, 1)
        val inv = mask.invert()
        assertEquals(listOf(1f, 0.5f, 0f), inv.alphaData.toList())
        // Invert gets a fresh id (cache invalidation).
        assertFalse(inv.id == mask.id)
        val full = LayerMask.full(2, 2)
        assertTrue(full.alphaData.all { it == 1f })
        assertEquals(2, full.width)
    }

    @Test
    fun layerMaskEqualityIsIdBased() {
        val shared = FloatArray(4) { 1f }
        val a = LayerMask(shared, 2, 2, id = "same")
        val b = LayerMask(shared, 2, 2, id = "same")
        val c = LayerMask(shared, 2, 2, id = "different")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == c)
    }
}
