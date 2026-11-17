package com.vayunmathur.measure.domain

import com.vayunmathur.measure.data.model.Anchor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeasureMathTest {

    private fun a(id: Long, x: Double, y: Double, z: Double) = Anchor(id, x, y, z, onPlane = true)

    @Test fun segmentsCarryLengthsAndMidpoints() {
        val anchors = listOf(a(1, 0.0, 0.0, 0.0), a(2, 3.0, 4.0, 0.0), a(3, 3.0, 4.0, 12.0))
        val segments = segmentsOf(anchors)
        assertEquals(2, segments.size)
        assertEquals(5.0, segments[0].lengthM)
        assertEquals(12.0, segments[1].lengthM)
        assertEquals(Vec3(1.5, 2.0, 0.0), segments[0].midpoint)
        assertEquals(17.0, polylineLengthM(anchors))
        assertEquals(12.0, lastSegmentLengthM(anchors))
    }

    @Test fun fewerThanTwoAnchorsHaveNoSegments() {
        assertTrue(segmentsOf(emptyList()).isEmpty())
        assertNull(polylineLengthM(listOf(a(1, 0.0, 0.0, 0.0))))
        assertNull(lastSegmentLengthM(emptyList()))
    }

    @Test fun flatSquareAreaIsTrustworthyBentQuadIsNot() {
        val flat = listOf(
            a(1, 0.0, 0.0, 0.0), a(2, 1.0, 0.0, 0.0),
            a(3, 1.0, 0.0, 1.0), a(4, 0.0, 0.0, 1.0),
        )
        assertTrue(areaIsTrustworthy(flat))
        val bent = flat.mapIndexed { i, anchor ->
            if (i == 3) anchor.copy(y = 0.4) else anchor
        }
        assertFalse(areaIsTrustworthy(bent))
    }
}
