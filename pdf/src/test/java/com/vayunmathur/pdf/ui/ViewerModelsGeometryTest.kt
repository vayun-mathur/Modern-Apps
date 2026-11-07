package com.vayunmathur.pdf.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViewerModelsGeometryTest {
    @Test fun shapeKindFillAndGeom() {
        assertTrue(ShapeKind.RECT_FILL.isFill); assertFalse(ShapeKind.RECT_OUTLINE.isFill)
        assertEquals(ShapeGeom.RECT, ShapeKind.RECT_OUTLINE.geom)
        assertEquals(ShapeGeom.POLYGON, ShapeKind.ROUNDRECT_FILL.geom)
        assertEquals(ShapeGeom.OVAL, ShapeKind.OVAL_FILL.geom)
        assertEquals(ShapeGeom.POLYGON, ShapeKind.TRIANGLE_FILL.geom)
        assertEquals(18, ShapeKind.entries.size)
    }

    @Test fun shapeLabels() {
        assertEquals("Rectangle", ShapeKind.RECT_OUTLINE.label())
        assertEquals("Rectangle (filled)", ShapeKind.RECT_FILL.label())
        assertEquals("Rounded rectangle", ShapeKind.ROUNDRECT_OUTLINE.label())
        assertEquals("Ellipse (filled)", ShapeKind.OVAL_FILL.label())
        assertEquals("Triangle", ShapeKind.TRIANGLE_OUTLINE.label())
        assertEquals("Star (filled)", ShapeKind.STAR_FILL.label())
        assertEquals("Arrow", ShapeKind.ARROW_OUTLINE.label())
    }

    @Test fun markupLabels() {
        assertEquals("Highlight", MarkupKind.HIGHLIGHT.label())
        assertEquals("Underline", MarkupKind.UNDERLINE.label())
        assertEquals("Strikeout", MarkupKind.STRIKEOUT.label())
        assertEquals("Squiggly", MarkupKind.SQUIGGLY.label())
    }

    @Test fun unitPolygons() {
        assertEquals(listOf(Offset(0.5f, 0f), Offset(1f, 1f), Offset(0f, 1f)), ShapeKind.TRIANGLE_OUTLINE.unitPolygon())
        assertEquals(4, ShapeKind.DIAMOND_FILL.unitPolygon().size)
        assertEquals(5, ShapeKind.PENTAGON_OUTLINE.unitPolygon().size)
        assertEquals(6, ShapeKind.HEXAGON_FILL.unitPolygon().size)
        assertEquals(10, ShapeKind.STAR_OUTLINE.unitPolygon().size)
        assertEquals(7, ShapeKind.ARROW_FILL.unitPolygon().size)
        assertTrue(ShapeKind.RECT_OUTLINE.unitPolygon().isEmpty())
        assertTrue(ShapeKind.OVAL_FILL.unitPolygon().isEmpty())
        assertTrue(ShapeKind.ROUNDRECT_OUTLINE.unitPolygon().size > 10)
        // All unit points inside unit square
        ShapeKind.entries.forEach { k -> k.unitPolygon().forEach { p -> assertTrue(p.x in -0.01f..1.01f && p.y in -0.01f..1.01f, "$k $p") } }
    }

    @Test fun regularPolygonAndStar() {
        val pent = regularPolygonUnit(5)
        assertEquals(5, pent.size); assertEquals(Offset(0.5f, 0f), pent.first())
        val star = starUnit(5, 0.5f, 0.22f)
        assertEquals(10, star.size); assertEquals(Offset(0.5f, 0f), star.first())
    }

    @Test fun roundRectClampsRadius() {
        val pts = roundRectUnit(2f, 4) // clamped to 0.5
        assertTrue(pts.all { it.x in 0f..1f && it.y in 0f..1f })
        assertEquals(20, pts.size) // 4 corners * (seg+1)
    }

    @Test fun mapUnitScalesIntoRect() {
        val r = Rect(10f, 20f, 110f, 220f)
        assertEquals(Offset(10f, 20f), mapUnit(Offset(0f, 0f), r))
        assertEquals(Offset(60f, 120f), mapUnit(Offset(0.5f, 0.5f), r))
        assertEquals(Offset(110f, 220f), mapUnit(Offset(1f, 1f), r))
    }

    @Test fun flattenSmoothShortInputUnchangedAndLongSmooths() {
        val two = listOf(Offset(0f, 0f), Offset(1f, 1f))
        assertEquals(two, flattenSmooth(two))
        val pts = listOf(Offset(0f, 0f), Offset(10f, 10f), Offset(20f, 0f), Offset(30f, 10f))
        val out = flattenSmooth(pts)
        assertTrue(out.size > pts.size)
        assertEquals(pts.first(), out.first()); assertEquals(pts.last(), out.last())
    }

    @Test fun maxZoomFor() {
        assertEquals(MAX_ZOOM, maxZoomFor(0f, 1000))
        assertEquals(MAX_ZOOM, maxZoomFor(612f, 0))
        assertEquals(MAX_ZOOM, maxZoomFor(100f, 1000)) // 0.4 clamped up to 6
        assertEquals(ABS_MAX_ZOOM, maxZoomFor(100000f, 1000)) // huge clamped to 40
        val mid = maxZoomFor(612f, 100) // 4*612/100=24.48
        assertEquals(24.48f, mid, 0.01f)
    }

    @Test fun maxPanX() {
        assertEquals(0f, maxPanX(1000, 1f)); assertEquals(0f, maxPanX(1000, 0.5f))
        assertEquals(500f, maxPanX(1000, 2f))
    }

    @Test fun editActionAndPolyDraftDefaults() {
        val a = EditAction(2, 42L, EditKind.ADDED)
        assertEquals(null, a.oldRect)
        val d = PolyDraft(1, listOf(Offset.Zero), bezier = true); assertTrue(d.bezier)
    }
}
