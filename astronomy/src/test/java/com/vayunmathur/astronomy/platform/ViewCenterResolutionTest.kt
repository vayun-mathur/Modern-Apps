package com.vayunmathur.astronomy.platform

import com.vayunmathur.astronomy.domain.engine.toRad
import com.vayunmathur.library.sensor.DeviceOrientation
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals

/** Resolution tests for every CenterOption shape, with and without a device orientation. */
class ViewCenterResolutionTest {

    private fun orientation() = DeviceOrientation(
        azimuthTrueDeg = 90.0,
        azimuthMagDeg = 88.0,
        pitchDeg = 10.0,
        rollDeg = 0.0,
        accuracy = 3,
        pointingAzTrueDeg = 90.0,
        pointingAltDeg = 30.0,
        viewRotationDeg = 15.0,
    )

    @Test
    fun `device pointing follows the orientation when present`() {
        val centre = resolveViewCenter(CenterOption.DevicePointing, orientation())
        assertEquals(PI / 2 to 30.0.toRad(), centre)
        assertEquals(15.0.toRad(), resolveViewRotation(CenterOption.DevicePointing, orientation()))
    }

    @Test
    fun `device pointing without orientation looks north at 45 degrees`() {
        assertEquals(0.0 to 45.0.toRad(), resolveViewCenter(CenterOption.DevicePointing, null))
        assertEquals(0.0, resolveViewRotation(CenterOption.DevicePointing, null))
    }

    @Test
    fun `manual and target centres pass through with no rotation`() {
        assertEquals(2.0 to 1.0, resolveViewCenter(CenterOption.Manual(2.0, 1.0), orientation()))
        assertEquals(
            3.0 to 1.5,
            resolveViewCenter(CenterOption.TargetObject("M31", 3.0, 1.5), orientation()),
        )
        assertEquals(0.0, resolveViewRotation(CenterOption.Manual(2.0, 1.0), orientation()))
        assertEquals(0.0, resolveViewRotation(CenterOption.TargetObject("M31", 3.0, 1.5), null))
    }
}
