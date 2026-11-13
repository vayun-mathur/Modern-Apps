package com.vayunmathur.games.logicgate.ui.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LabelSizingTest {
    @Test
    fun smallLabelsAreLiftedToTheReadableFloorAtFitZoom() {
        val s = LabelSizing(dpPerWorld = 1f, fontScale = 1f)
        assertEquals(LabelSizing.MIN_LABEL_DP, s.drawn(9f)!!, 0.001f, "9-unit labels used to be 9 dp")
        assertEquals(14f, s.drawn(14f)!!, 0.001f, "already readable labels keep their size")
    }

    @Test
    fun zoomedInLabelsKeepTheirDesignSize() {
        assertEquals(9f, LabelSizing(dpPerWorld = 2f, fontScale = 1f).drawn(9f)!!, 0.001f)
    }

    @Test
    fun labelsTooSmallToReadAreHiddenNotDrawn() {
        assertNull(LabelSizing(dpPerWorld = 0.3f, fontScale = 1f).drawn(9f))
        assertNull(LabelSizing(dpPerWorld = 0.3f, fontScale = 1f).drawn(14f))
        assertNotNull(LabelSizing(dpPerWorld = 0.5f, fontScale = 1f).drawn(14f), "big labels survive half zoom")
        assertNull(LabelSizing(dpPerWorld = 0f, fontScale = 1f).drawn(14f))
    }

    @Test
    fun labelsFollowTheFontScale() {
        val big = LabelSizing(dpPerWorld = 2f, fontScale = 1.3f)
        assertEquals(9f * 1.3f, big.measured(9f), 0.001f)
        assertEquals(9f * 1.3f, big.drawn(9f)!!, 0.001f)
        val huge = LabelSizing(dpPerWorld = 1f, fontScale = 2f)
        assertEquals(22f, huge.drawn(14f)!!, 0.001f, "floor is 11 dp x 2")
        assertNull(huge.drawn(9f), "a pin name cannot reach 22 dp inside its gate at 1x; zoom in to read it")
        assertNotNull(LabelSizing(dpPerWorld = 1.6f, fontScale = 2f).drawn(9f))
    }
}
