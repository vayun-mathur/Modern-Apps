package com.vayunmathur.games.wordmaker.ui.components

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WheelGeometryTest {
    private fun assertNear(expected: Offset, actual: Offset) {
        assertTrue((expected - actual).getDistance() < 0.01f, "expected $expected, got $actual")
    }

    @Test fun lettersStartAtTheTopAndGoClockwise() {
        val g = WheelGeometry.fit(width = 300f, height = 300f, nominalRadius = 100f, tileRadius = 35f, count = 4)
        assertNear(Offset(150f, 50f), g.centers[0])
        assertNear(Offset(250f, 150f), g.centers[1])
        assertNear(Offset(150f, 250f), g.centers[2])
        assertNear(Offset(50f, 150f), g.centers[3])
    }

    @Test fun ringFollowsTheMeasuredBoxNotTheNominalOne() {
        // Asked for a 130 radius (the Widest spacing) but the box only got 248 px:
        // the ring shrinks so every tile stays inside, and the centre is the measured centre.
        val g = WheelGeometry.fit(width = 248f, height = 248f, nominalRadius = 130f, tileRadius = 35f, count = 6)
        assertEquals(124f - 35f, g.ringRadius)
        assertEquals(Offset(124f, 124f), g.center)
        g.centers.forEach { c ->
            assertTrue(c.x - 35f >= -0.01f && c.x + 35f <= 248.01f, "tile at $c leaves the box")
            assertTrue(c.y - 35f >= -0.01f && c.y + 35f <= 248.01f, "tile at $c leaves the box")
        }
    }

    @Test fun nonSquareBoxUsesItsShortSideAndItsOwnCentre() {
        val g = WheelGeometry.fit(width = 250f, height = 230f, nominalRadius = 85f, tileRadius = 35f, count = 3)
        assertEquals(Offset(125f, 115f), g.center)
        assertEquals(115f - 35f, g.ringRadius)
    }

    @Test fun roomyBoxKeepsTheNominalRadius() {
        val g = WheelGeometry.fit(width = 400f, height = 400f, nominalRadius = 85f, tileRadius = 35f, count = 5)
        assertEquals(85f, g.ringRadius)
    }

    @Test fun hitTestUsesTheTileCircle() {
        val g = WheelGeometry.fit(width = 300f, height = 300f, nominalRadius = 100f, tileRadius = 35f, count = 4)
        assertEquals(0, g.letterAt(Offset(150f, 50f)))
        assertEquals(1, g.letterAt(Offset(250f + 30f, 150f)))
        // The centre of the wheel and the box corners are on no letter.
        assertNull(g.letterAt(Offset(150f, 150f)))
        assertNull(g.letterAt(Offset(2f, 2f)))
        assertNull(g.letterAt(Offset(298f, 298f)))
    }

    @Test fun overlappingTilesPickTheNearestLetter() {
        // Eight letters on a tight ring: neighbouring tiles overlap.
        val g = WheelGeometry.fit(width = 250f, height = 250f, nominalRadius = 85f, tileRadius = 35f, count = 8)
        val between = (g.centers[0] * 0.48f) + (g.centers[1] * 0.52f)
        assertTrue((g.centers[0] - between).getDistance() <= 35f, "both tiles contain the point")
        assertEquals(1, g.letterAt(between))
    }

    @Test fun tilesShrinkOnlyBelowTheDefaultBox() {
        assertEquals(70f, WheelGeometry.tileSize(side = 300f, defaultSide = 250f, nominalTile = 70f))
        assertEquals(70f, WheelGeometry.tileSize(side = 250f, defaultSide = 250f, nominalTile = 70f))
        assertEquals(56f, WheelGeometry.tileSize(side = 200f, defaultSide = 250f, nominalTile = 70f), 0.001f)
    }
}
