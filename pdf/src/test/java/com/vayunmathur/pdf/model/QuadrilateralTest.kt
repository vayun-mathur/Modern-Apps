package com.vayunmathur.pdf.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuadrilateralTest {
    @Test fun defaultIsUnitSquare() {
        val q = Quadrilateral.default()
        assertEquals(Offset(0f, 0f), q.topLeft)
        assertEquals(Offset(1f, 0f), q.topRight)
        assertEquals(Offset(1f, 1f), q.bottomRight)
        assertEquals(Offset(0f, 1f), q.bottomLeft)
        assertEquals(Rect(0f, 0f, 1f, 1f), q.toBoundingRect())
    }

    @Test fun boundingRectUsesExtremeCorners() {
        val q = Quadrilateral(Offset(0.2f, 0.3f), Offset(0.8f, 0.1f), Offset(0.9f, 0.7f), Offset(0.1f, 0.9f))
        assertEquals(Rect(0.1f, 0.1f, 0.9f, 0.9f), q.toBoundingRect())
    }

    @Test fun cornersAndCornerAccessors() {
        val q = Quadrilateral.default()
        assertEquals(listOf(q.topLeft, q.topRight, q.bottomRight, q.bottomLeft), q.corners())
        assertEquals(q.topLeft, q.corner(0)); assertEquals(q.topRight, q.corner(1))
        assertEquals(q.bottomRight, q.corner(2)); assertEquals(q.bottomLeft, q.corner(3))
        assertEquals(q.bottomLeft, q.corner(99))
    }

    @Test fun withCornerReplacesOnlyThatCorner() {
        val q = Quadrilateral.default().withCorner(1, Offset(0.7f, 0.2f))
        assertEquals(Offset(0.7f, 0.2f), q.topRight)
        assertEquals(Offset(0f, 0f), q.topLeft)
        assertEquals(Offset(0.5f, 0.5f), Quadrilateral.default().withCorner(7, Offset(0.5f, 0.5f)).bottomLeft)
    }

    @Test fun toSrcPointsScalesBySize() {
        val pts = Quadrilateral.default().toSrcPoints(200, 100)
        assertTrue(pts.contentEquals(floatArrayOf(0f, 0f, 200f, 0f, 200f, 100f, 0f, 100f)))
    }
}
