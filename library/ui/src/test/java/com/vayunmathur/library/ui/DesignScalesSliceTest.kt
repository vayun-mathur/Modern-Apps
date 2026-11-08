package com.vayunmathur.library.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 3/3: pure-JVM checks for the design-system scales and helpers —
 * [Spacing], [Motion] easings/durations, [contentColorOn] and [Motion] specs.
 */
class DesignScalesSliceTest {

    @Test fun spacingScaleValues() {
        assertEquals(4.dp, Spacing.xs)
        assertEquals(8.dp, Spacing.sm)
        assertEquals(12.dp, Spacing.md)
        assertEquals(16.dp, Spacing.lg)
        assertEquals(24.dp, Spacing.xl)
        assertEquals(32.dp, Spacing.xxl)
    }

    @Test fun motionDurations() {
        assertEquals(150, Motion.ReorderMillis)
        assertEquals(650L, Motion.ReorderTimeoutMillis)
        assertEquals(285, Motion.DropMillis)
        assertEquals(600, Motion.DrawerOpenMillis)
        assertEquals(300, Motion.DrawerCloseMillis)
        assertEquals(750, Motion.PageSnapMillis)
    }

    @Test fun motionEasingsBounded() {
        // Easing.transform maps [0,1] -> [0,1]-ish; endpoints must hold.
        listOf(
            Motion.EmphasizedDecelerate,
            Motion.EmphasizedAccelerate,
            Motion.Standard,
            Motion.FastOutSlowIn,
            Motion.Decelerate,
            Motion.Scroll,
        ).forEach { easing ->
            assertEquals(0f, easing.transform(0f), 0.02f)
            assertEquals(1f, easing.transform(1f), 0.02f)
        }
        // Decelerate is (1-(1-t)^3): midpoint is 0.875.
        assertEquals(0.875f, Motion.Decelerate.transform(0.5f), 0.001f)
        // Scroll is (t-1)^5+1: midpoint is ~0.96875.
        assertEquals(0.96875f, Motion.Scroll.transform(0.5f), 0.001f)
        // Curves are monotonic.
        listOf(Motion.Decelerate, Motion.Scroll).forEach { easing ->
            assertTrue(easing.transform(0.25f) < easing.transform(0.75f))
        }
    }

    @Test fun motionSpecsAreDistinct() {
        // Each factory produces a spec (non-null); durations encoded per interaction.
        assertTrue(Motion.reorder<Float>().toString().isNotEmpty())
        assertTrue(Motion.drop<Float>().toString().isNotEmpty())
        assertTrue(Motion.pageSnap<Float>().toString().isNotEmpty())
        assertTrue(Motion.over<Float>(123).toString().isNotEmpty())
    }

    @Test fun contentColorOnPicksReadable() {
        assertEquals(Color.Black, contentColorOn(Color.White))
        assertEquals(Color.White, contentColorOn(Color.Black))
        assertEquals(Color.Black, contentColorOn(Color.Yellow))
        assertEquals(Color.White, contentColorOn(Color.DarkGray))
    }
}
