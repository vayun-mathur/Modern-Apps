package com.vayunmathur.pdf.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScrollZoomGlyphTest {
    private fun scrollState(pages: Int = 3, w: Int = 612, h: Int = 800): SafePdfScrollState {
        val s = SafePdfScrollState()
        s.viewportSize = IntSize(w, h); s.pageCount = pages
        return s
    }

    @Test fun scrollNotReadyWithoutPagesOrViewport() {
        val s = SafePdfScrollState(); assertFalse(s.isReady())
        s.pageCount = 2; assertFalse(s.isReady())
        s.viewportSize = IntSize(100, 100); assertTrue(s.isReady())
    }

    @Test fun contentHeightUsesFallbackRatio() {
        val s = scrollState(2, w = 612, h = 800)
        // each page 612 / (612/792) = 792
        assertEquals(1584f, s.contentHeightPx(), 0.5f)
        assertEquals(0f, s.pageTop(0)); assertEquals(792f, s.pageTop(1), 0.5f)
        assertEquals(1584f, s.pageTop(99), 0.5f) // clamped to last entry (content height)
    }

    @Test fun measuredHeightOverridesEstimateAndIgnoresInvalid() {
        val s = scrollState(2)
        s.setMeasuredHeight(0, 400); assertEquals(400f + 792f, s.contentHeightPx(), 0.5f)
        s.setMeasuredHeight(0, 0); s.setMeasuredHeight(-1, 100); s.setMeasuredHeight(5, 100)
        assertEquals(400f + 792f, s.contentHeightPx(), 0.5f)
        s.ratioAt = { 1f }
        // page1 now estimated 612/1=612, page0 still measured
        assertEquals(1012f, s.contentHeightPx(), 0.5f)
    }

    @Test fun scrollClampsAndProgress() {
        val s = scrollState(3)
        s.scrollTo(99999f, 1f); assertEquals(s.range(1f), s.scrollPx)
        assertEquals(1f, s.progress(1f))
        s.scrollTo(-50f, 1f); assertEquals(0f, s.scrollPx); assertEquals(0f, s.progress(1f))
        val consumed = s.scrollByScreen(-100f, 1f) // finger up? dy negative -> scroll forward 100
        assertEquals(100f, s.scrollPx, 0.01f); assertEquals(-100f, consumed, 0.01f)
    }

    @Test fun currentPageAndVisibleRange() {
        val s = scrollState(5)
        assertEquals(0, s.currentPage())
        s.scrollTo(800f, 1f); assertEquals(1, s.currentPage())
        val range = s.visibleRange(1f)
        assertTrue(range.first <= 1 && range.last >= 1)
        assertEquals(0..2, SafePdfScrollState().visibleRange(1f))
    }

    @Test fun scrollToPage() {
        val s = scrollState(4)
        s.scrollToPage(2, 1f); assertEquals(1584f, s.scrollPx, 0.5f)
        s.scrollToPage(99, 1f); assertEquals(s.range(1f), s.scrollPx)
        SafePdfScrollState().scrollToPage(1, 1f) // no pages, no crash
    }

    @Test fun zoomAroundKeepsCentroid() {
        val s = scrollState(5)
        s.scrollTo(500f, 1f)
        s.zoomAround(400f, 1f, 2f)
        // docY=900, new scroll=900-200=700
        assertEquals(700f, s.scrollPx, 0.5f)
    }

    @Test fun zoomStateTransformClamps() {
        val scroll = scrollState(5)
        val z = SafePdfZoomState()
        z.viewportSize = IntSize(1000, 800)
        z.onPageWidth(612f); z.onPageWidth(100f) // monotonic
        assertEquals(612f, z.maxPageWidthPts)
        z.onTransform(Offset(500f, 400f), 100f, Offset(99999f, 0f), scroll)
        assertTrue(z.zoom <= maxZoomFor(612f, 1000) + 0.001f)
        assertEquals(maxPanX(1000, z.zoom), z.pan.x, 0.01f)
        assertEquals(0f, z.pan.y)
        z.setZoomPan(2f, Offset(50f, 99f)); assertEquals(2f, z.zoom); assertEquals(Offset(50f, 0f), z.pan)
        z.onTransform(Offset.Zero, 0.0001f, Offset.Zero, scroll); assertEquals(MIN_ZOOM, z.zoom)
    }

    @Test fun selGlyphBoundsAndCenter() {
        val g = selGlyph("a", 10f, 20f, 30f, 40f)
        assertEquals(10f, g.left); assertEquals(30f, g.right); assertEquals(Offset(20f, 30f), g.center)
        val skewed = SelGlyph("x", Offset(5f, 5f), Offset(15f, 0f), Offset(20f, 10f), Offset(10f, 15f))
        assertEquals(0f, skewed.top); assertEquals(20f, skewed.right)
    }

    @Test fun nearestGlyphAndWordRange() {
        val glyphs = listOf(
            selGlyph("h", 0f, 0f, 10f, 10f), selGlyph("i", 10f, 0f, 20f, 10f),
            selGlyph(" ", 20f, 0f, 30f, 10f), selGlyph("y", 40f, 0f, 50f, 10f),
        )
        assertEquals(1, nearestGlyph(glyphs, Offset(15f, 5f)))
        assertNull(nearestGlyph(glyphs, Offset(500f, 500f), maxDist = 10f))
        assertNull(nearestGlyph(emptyList(), Offset.Zero))
        assertEquals(0..1, wordRangeAt(glyphs, 0))
        assertEquals(0..1, wordRangeAt(glyphs, 1))
        assertEquals(3..3, wordRangeAt(glyphs, 3))
        assertEquals(9..9, wordRangeAt(glyphs, 9))
    }

    @Test fun sameWordAndHandleAt() {
        val a = selGlyph("a", 0f, 0f, 10f, 10f); val b = selGlyph("b", 10f, 0f, 20f, 10f)
        val far = selGlyph("c", 100f, 0f, 110f, 10f)
        assertTrue(sameWord(a, b)); assertFalse(sameWord(a, far))
        val glyphs = listOf(a, b)
        assertEquals(0, handleAt(glyphs, Offset(0f, 10f), 0..1))
        assertEquals(1, handleAt(glyphs, Offset(20f, 10f), 0..1))
        assertNull(handleAt(glyphs, Offset(500f, 500f), 0..1))
        assertNull(handleAt(glyphs, Offset.Zero, 5..9))
    }
}
