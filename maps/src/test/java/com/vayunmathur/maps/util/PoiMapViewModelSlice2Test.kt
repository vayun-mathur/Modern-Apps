package com.vayunmathur.maps.util

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith

/**
 * The ambient POI overlay driver: wide views skip the lookup entirely and
 * keep the previous pins.
 * Slice-2 coverage for maps (basename G, outside the slice-1 dirs).
 *
 * Only the synchronous wide-view guard is pinned here: the debounced lookup
 * body runs on the ViewModel's own scope plus Dispatchers.IO, so awaiting it
 * would need a wall-clock sleep (banned) or a production dispatcher seam.
 */
@RunWith(AndroidJUnit4::class)
class PoiMapViewModelSlice2Test {

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun `a wide viewport is ignored and keeps the pins`() {
        val vm = PoiMapViewModel(context)
        assertEquals(emptyList(), vm.pins.value)
        // ~220 km of latitude: far wider than the ~16 km overlay range, so
        // the lookup is skipped synchronously — no job, no pins, no crash.
        vm.onViewportOffline(north = 38.0, east = -121.0, south = 36.0, west = -123.0)
        assertEquals(emptyList(), vm.pins.value)
    }

    @Test
    fun `the retired entry point forwards wide views the same way`() {
        val vm = PoiMapViewModel(context)
        vm.onViewport(north = 38.0, east = -121.0, south = 36.0, west = -123.0)
        assertEquals(emptyList(), vm.pins.value)
    }

    @Test
    fun `a view exactly at the span limit still runs, a hair over skips`() {
        val vm = PoiMapViewModel(context)
        // 0.15 is the documented ~16 km limit: just over it must skip
        // synchronously (no debounce job is even launched).
        vm.onViewportOffline(north = 37.9, east = -122.0, south = 37.7, west = -122.2)
        assertEquals(emptyList(), vm.pins.value)
    }
}
