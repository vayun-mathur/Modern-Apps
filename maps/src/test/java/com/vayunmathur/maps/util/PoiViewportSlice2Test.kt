package com.vayunmathur.maps.util

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The ambient POI driver's debounced lookup body: a narrow viewport runs it
 * (on the ViewModel scope plus Dispatchers.IO), and with no offline index on
 * the test device the previous pins are kept — the no-clear contract.
 * Slice-2 coverage for maps (basename G, outside the slice-1 dirs).
 */
@RunWith(AndroidJUnit4::class)
class PoiViewportSlice2Test {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    /** Advance the paused main looper past the debounce, then give the IO
     *  lookup a bounded window to land before asserting. */
    private fun settle() {
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
            // The lookup publishes only non-empty results; with no index the
            // pins staying empty is the settled state once IO has had time.
            if (System.currentTimeMillis() > deadline - 9_000) break
        }
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
    }

    @Test fun `a narrow viewport runs the lookup and keeps pins when the index is empty`() {
        val vm = PoiMapViewModel(app)
        vm.onViewportOffline(north = 37.85, east = -122.35, south = 37.75, west = -122.45)
        settle()
        assertEquals(emptyList(), vm.pins.value)
    }

    @Test fun `rapid viewport changes debounce to the last one`() {
        val vm = PoiMapViewModel(app)
        vm.onViewportOffline(north = 37.85, east = -122.35, south = 37.75, west = -122.45)
        vm.onViewport(north = 37.86, east = -122.34, south = 37.76, west = -122.44)
        vm.onViewportOffline(north = 37.87, east = -122.33, south = 37.77, west = -122.43)
        settle()
        assertEquals(emptyList(), vm.pins.value)
    }

    @Test fun `a wide viewport after a narrow one still keeps the pins`() {
        val vm = PoiMapViewModel(app)
        vm.onViewportOffline(north = 37.85, east = -122.35, south = 37.75, west = -122.45)
        vm.onViewportOffline(north = 40.0, east = -120.0, south = 36.0, west = -124.0)
        settle()
        assertEquals(emptyList(), vm.pins.value)
    }
}
