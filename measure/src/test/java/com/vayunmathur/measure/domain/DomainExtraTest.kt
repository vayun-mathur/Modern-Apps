package com.vayunmathur.measure.domain

import com.vayunmathur.measure.data.model.Anchor
import com.vayunmathur.measure.data.model.MeasurementKind
import com.vayunmathur.measure.data.model.SavedMeasurement
import com.vayunmathur.measure.data.model.UnitSystem
import com.vayunmathur.measure.data.model.distanceTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DomainExtraTest {

    @Test fun metricLengthBands() {
        assertEquals("0.0 mm", Units.formatLength(0.0, UnitSystem.Metric))
        assertEquals("-5.0 mm", Units.formatLength(-0.005, UnitSystem.Metric))
        assertEquals("999 mm", Units.formatLength(0.999, UnitSystem.Metric))
        assertEquals("9.999 m", Units.formatLength(9.999, UnitSystem.Metric))
        assertEquals("10.00 m", Units.formatLength(10.0, UnitSystem.Metric))
    }

    @Test fun imperialLengthDecimalAndFeet() {
        assertEquals("1.00 ft", Units.formatLength(0.3048, UnitSystem.Imperial, fractionalInches = false))
        // Fractional is the default rendering.
        assertEquals("1' 0\"", Units.formatLength(0.3048, UnitSystem.Imperial))
        assertEquals("0 1/16\"", Units.formatFractionalInches(1.0 / 16))
        assertEquals("0 3/16\"", Units.formatFractionalInches(3.0 / 16))
        assertEquals("-1 1/2\"", Units.formatFractionalInches(-1.5))
        assertEquals("0\"", Units.formatFractionalInches(0.03)) // rounds to zero sixteenths
    }

    @Test fun areasInBothSystems() {
        assertEquals("1.00 m²", Units.formatArea(1.0, UnitSystem.Metric))
        assertEquals("0 cm²", Units.formatArea(0.0, UnitSystem.Metric))
        // 1 m² ≈ 10.76 ft²; a small patch reads in square inches.
        assertEquals("10.76 ft²", Units.formatArea(1.0, UnitSystem.Imperial))
        val small = Units.formatArea(0.001, UnitSystem.Imperial)
        assertTrue(small.endsWith("in²"), small)
    }

    @Test fun bearingsAndCardinalsWrap() {
        assertEquals("180°", Units.formatBearing(180.0))
        assertEquals("270°", Units.formatBearing(-90.0))
        assertEquals(0.0, Units.normalizeDegrees(360.0))
        assertEquals(0.0, Units.normalizeDegrees(-360.0))
        assertEquals(180.0, Units.bearingDelta(270.0, 90.0) * -1)
        assertEquals(15, Units.cardinalIndex(348.0))
        assertEquals(1, Units.cardinalIndex(22.5))
        assertEquals("45.0°", Units.formatAngle(45.0))
        assertEquals(25.4, Units.MM_PER_INCH)
        assertEquals(304.8, Units.MM_PER_FOOT)
        assertEquals(16, Units.CARDINAL_POINTS)
    }

    @Test fun tiltAndModels() {
        val tilt = Tilt(1.0, 2.0, 3.0, isFlat = true, orientation = HeldOrientation.Flat, uiRotationDeg = 90f)
        assertEquals(tilt, tilt.copy())
        assertNotEquals(tilt, tilt.copy(pitchDeg = 9.0))
        assertEquals(90f, tilt.uiRotationDeg)
        assertEquals("Flat", tilt.orientation.name)

        val a = Anchor(1, 0.0, 0.0, 0.0, onPlane = true)
        val b = Anchor(2, 0.0, 3.0, 4.0)
        assertEquals(5.0, a.distanceTo(b))
        assertTrue(a.onPlane)
        assertEquals(false, b.onPlane)

        val m = SavedMeasurement(7, "door", MeasurementKind.Height, 2.1, 123L, framePath = "/tmp/f.png")
        assertEquals(MeasurementKind.Height, m.kind)
        assertEquals("/tmp/f.png", m.framePath)
        assertEquals(null, m.copy(framePath = null).framePath)
        assertEquals(listOf("Distance", "Area", "Perimeter", "Angle", "Height"), MeasurementKind.entries.map { it.name })
        assertEquals(listOf("Metric", "Imperial"), UnitSystem.entries.map { it.name })
    }

    @Test fun nativeBridgeDegradesCleanly() {
        val n = MeasureNative
        // Whatever the host provides, the guarded contract holds: a zero
        // handle is always "lost", empty projections, no rays or state.
        assertEquals(MeasureNative.QUALITY_LOST, n.pushFrame(0L, ByteArray(4), 2, 2, 2, 0L))
        assertEquals(null, n.rayToWorld(0L, 1f, 1f))
        assertEquals(null, n.getState(0L))
        assertEquals(0, n.projectPoints(0L, doubleArrayOf(1.0, 2.0, 3.0), 100.0, 100.0).size)
        assertEquals(0, n.projectPoints(1L, DoubleArray(0), 100.0, 100.0).size)
        n.destroySession(0L)
        n.reset(0L)
        n.pushImu(0L, doubleArrayOf(1.0))
        n.pushImu(1L, DoubleArray(0))
        if (n.available) {
            val handle = n.createSession(500.0, 500.0, 320.0, 240.0)
            assertTrue(handle != 0L, "host engine should create a session")
            val frame = ByteArray(640 * 480) { 128.toByte() }
            val quality = n.pushFrame(handle, frame, 640, 480, 640, 1_000_000L)
            assertTrue(quality in 0..3, "quality=$quality")
            n.pushImu(handle, doubleArrayOf(1e9, 0.0, 0.0, 0.0, 0.0, 0.0, 9.81))
            val state = n.getState(handle)
            if (state != null) assertEquals(8, state.size)
            n.reset(handle)
            n.destroySession(handle)
            n.destroySession(handle)
        } else {
            assertEquals(0L, n.createSession(500.0, 500.0, 320.0, 240.0))
        }
    }
}
