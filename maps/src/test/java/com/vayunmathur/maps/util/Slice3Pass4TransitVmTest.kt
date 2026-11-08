package com.vayunmathur.maps.util

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.maps.data.transit.Departure
import com.vayunmathur.maps.data.transit.TransitStop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Slice 3/3 pass 4: TransitStopsViewModel trip-sheet branches. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Slice3Pass4TransitVmTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun pumpUntil(timeoutMs: Long = 10_000, want: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!want() && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
    }

    private fun departure(tripVehicleId: Long?) = Departure(
        line = "Caltrain", headsign = "San Francisco",
        scheduledMillis = 1_700_000_000_000, realtimeMillis = 1_700_000_000_000,
        delayMinutes = 0, realTime = false, platform = null, mode = "rail",
        routeColor = null, cancelled = false, tripVehicleId = tripVehicleId,
    )

    @Test fun openTripForDepartureWithoutVehicleIdIsNoOp() {
        val vm = TransitStopsViewModel(app)
        vm.openTripForDeparture(departure(null), 37.8, -122.4)
        assertNull(vm.selectedTrip.value)
        assertEquals(TripItineraryState.Idle, vm.tripItinerary.value)
    }

    @Test fun openTripLoadsThenReportsMissingWithoutPack() {
        val vm = TransitStopsViewModel(app)
        vm.openTrip(4242L, 37.8, -122.4)
        assertEquals(TripSelection(4242L, 37.8, -122.4), vm.selectedTrip.value)
        // No transit pack in the sandbox: the fetch resolves to Missing.
        pumpUntil { vm.tripItinerary.value == TripItineraryState.Missing }
        assertEquals(TripItineraryState.Missing, vm.tripItinerary.value)
    }

    @Test fun openTripForDepartureOpensTripSheet() {
        val vm = TransitStopsViewModel(app)
        vm.openTripForDeparture(departure(777L), 37.8, -122.4)
        assertEquals(TripSelection(777L, 37.8, -122.4), vm.selectedTrip.value)
        pumpUntil { vm.tripItinerary.value == TripItineraryState.Missing }
        assertEquals(TripItineraryState.Missing, vm.tripItinerary.value)
    }

    @Test fun closeTripResetsTripState() {
        val vm = TransitStopsViewModel(app)
        vm.openTrip(99L, 37.8, -122.4)
        vm.closeTrip()
        assertNull(vm.selectedTrip.value)
        assertEquals(TripItineraryState.Idle, vm.tripItinerary.value)
    }

    @Test fun openNearestStopFallsBackToCoordinateStop() {
        val vm = TransitStopsViewModel(app)
        vm.openNearestStop(37.8, -122.4, "Palo Alto")
        pumpUntil { vm.selected.value != null }
        val stop: TransitStop? = vm.selected.value
        assertTrue(stop != null, "a stop should open even without a pack-resolved id")
        assertEquals("Palo Alto", stop.name)
        assertEquals(37.8, stop.lat)
        assertEquals(-122.4, stop.lon)
        vm.closeStop()
        assertNull(vm.selected.value)
    }
}
