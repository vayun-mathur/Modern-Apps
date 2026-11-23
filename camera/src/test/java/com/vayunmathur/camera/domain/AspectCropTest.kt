package com.vayunmathur.camera.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Still aspect-ratio centre crop: the saved frame must match the
 * preview box for every historical option (4:3, 3:2, 16:9, 1:1) on
 * both phone sensor orientations, in sensor-pixel coordinates.
 */
class AspectCropTest {

    private fun crop(
        w: Int, h: Int, rotation: Int, ratioW: Int, ratioH: Int,
    ) = AspectCrop.centerCropRect(w, h, rotation, ratioW, ratioH)

    @Test fun nativeFourThreeIsFullFrame() {
        // 4080x3060 sensor, quarter-turned: displays 3060x4080 = 3:4.
        val rect = assertNotNull(crop(4080, 3060, 90, 3, 4))
        assertTrue(rect.isFullFrame(4080, 3060))
    }

    @Test fun sixteenNineCropsSensorLongAxis() {
        // Displayed target 9:16 (ratioW=9, ratioH=16 in portrait terms).
        val rect = assertNotNull(crop(4080, 3060, 90, 9, 16))
        // Display crop 2295x4080 -> sensor crop 4080x2295, centred.
        assertEquals(4080, rect.width)
        assertEquals(2295, rect.height)
        assertEquals(0, rect.left)
        assertEquals((3060 - 2295) / 2, rect.top)
    }

    @Test fun squareCropsToDisplayedSquare() {
        val rect = assertNotNull(crop(4080, 3060, 90, 1, 1))
        // Displayed 3060x4080 -> 3060x3060 square -> sensor 3060x3060.
        assertEquals(3060, rect.width)
        assertEquals(3060, rect.height)
        assertEquals((4080 - 3060) / 2, rect.left)
        assertEquals(0, rect.top)
    }

    @Test fun frontSensor270CropsLikeBack90() {
        // Centre crop is direction-independent: 270 swaps axes like 90.
        val back = assertNotNull(crop(4080, 3060, 90, 2, 3))
        val front = assertNotNull(crop(4080, 3060, 270, 2, 3))
        assertEquals(back, front)
    }

    @Test fun noRotationUsesDisplayAxesDirectly() {
        val rect = assertNotNull(crop(1920, 1080, 0, 1, 1))
        assertEquals(1080, rect.width)
        assertEquals(1080, rect.height)
        assertEquals((1920 - 1080) / 2, rect.left)
        assertEquals(0, rect.top)
    }

    @Test fun threeTwoBetweenFourThreeAndSixteenNine() {
        val rect = assertNotNull(crop(4080, 3060, 90, 2, 3))
        // Displayed 3060x4080 at 2:3 -> 2720x4080 -> sensor 4080x2720.
        assertEquals(4080, rect.width)
        assertEquals(2720, rect.height)
    }

    @Test fun degenerateInputHasNoCrop() {
        assertNull(crop(0, 3060, 90, 3, 4))
        assertNull(crop(4080, 3060, 90, 0, 4))
    }
}
