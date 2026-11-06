package com.vayunmathur.maps.ui.theme

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Slice 1/3 (maps): [MapChromeMetrics] — geometry that two or more call sites
 * have to agree on, pinned so the insets cannot silently drift apart again.
 */
class MapChromeMetricsSlice1Test {

    @Test
    fun `eta clearances derive from the strip height`() {
        assertEquals(72.dp, MapChromeMetrics.etaStripHeight)
        assertEquals(MapChromeMetrics.etaStripHeight + 12.dp, MapChromeMetrics.aboveEtaStrip)
        assertEquals(MapChromeMetrics.etaStripHeight + 4.dp, MapChromeMetrics.aboveEtaStripTight)
    }

    @Test
    fun `chrome margins and touch geometry stay put`() {
        assertEquals(170.dp, MapChromeMetrics.sheetPeekHeight)
        assertEquals(22.dp, MapChromeMetrics.hitSlop)
        assertEquals(16.dp, MapChromeMetrics.chromeMargin)
        assertEquals(12.dp, MapChromeMetrics.fabSpacing)
    }
}
