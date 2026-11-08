package com.vayunmathur.library.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Slice 3/3 round 8: spacing scale + panorama crop value semantics. */
@RunWith(RobolectricTestRunner::class)
class Slice3Round8Test {
    @Test fun spacingScaleIsStrictlyIncreasing() {
        assertEquals(4.dp, Spacing.xs)
        assertEquals(8.dp, Spacing.sm)
        assertEquals(12.dp, Spacing.md)
        assertEquals(16.dp, Spacing.lg)
        assertEquals(24.dp, Spacing.xl)
        assertEquals(32.dp, Spacing.xxl)
        assertTrue(Spacing.xs < Spacing.sm && Spacing.sm < Spacing.md && Spacing.md < Spacing.lg)
    }

    @Test fun panoramaCropPartialValuesAndEquality() {
        val crop = PanoramaCrop(
            fullWidth = 6000, fullHeight = 3000,
            croppedWidth = 4000, croppedHeight = 1000,
            croppedLeft = 500, croppedTop = 1000,
        )
        assertEquals(6000, crop.fullWidth)
        assertEquals(500, crop.croppedLeft)
        assertEquals(crop, crop.copy())
        assertNotEquals(crop, crop.copy(croppedLeft = 501))
        // full() centres nothing: crop equals the whole image at origin.
        assertEquals(
            PanoramaCrop(800, 400, 800, 400, 0, 0),
            PanoramaCrop.full(800, 400),
        )
    }
}
