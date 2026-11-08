package com.vayunmathur.maps.util

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.maps.data.transit.Departure
import com.vayunmathur.maps.data.transit.TransitStop
import com.vayunmathur.maps.ipc.RideEstimateClient
import com.vayunmathur.maps.ipc.RideHandoffContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import android.os.Looper
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplicationPackageManager

/** Slice 3/3 extra: TransitStopsViewModel board/trip states and RideEstimateClient. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransitAndRideSlice3ExtraTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun stop(id: String = "stop-1") = TransitStop(id, "Central Station", 37.8, -122.4)

    private fun pump() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    /** Poll [cond] while pumping the paused Main looper that viewModelScope runs on. */
    private suspend fun awaitCond(timeoutMs: Long = 15000, cond: () -> Boolean) {
        withTimeout(timeoutMs) { while (!cond()) { pump(); delay(50) } }
    }

    /** Collect a WhileSubscribed flow for [ms] of real time, pumping nothing (IO is real). */
    private suspend fun <T> collectBriefly(flow: kotlinx.coroutines.flow.StateFlow<T>, ms: Long = 4000): Job {
        val job = CoroutineScope(Dispatchers.Default).launch { flow.collect {} }
        delay(ms)
        return job
    }

    @Test fun departuresIdleLoadingLoadedEmpty() { runBlocking {
        val vm = TransitStopsViewModel(app)
        assertEquals(DeparturesState.Idle, vm.departures.value)
        assertNull(vm.selected.value)
        val job = collectBriefly(vm.departures, 300)
        vm.openStop(stop())
        assertEquals(stop(), vm.selected.value)
        // No pack covers the sandbox and there is no validated internet: Loaded with an empty board
        awaitCond { vm.departures.value is DeparturesState.Loaded }
        val loaded = vm.departures.value as DeparturesState.Loaded
        assertEquals(stop(), loaded.stop)
        assertTrue(loaded.departures.isEmpty(), "deps=${loaded.departures}")
        // refresh on an open stop bumps the tick and re-emits (still Loaded)
        vm.refresh()
        repeat(10) { pump(); delay(30) }
        // close returns to selection null (flow falls back toward Idle once unsubscribed/closed)
        vm.closeStop()
        assertNull(vm.selected.value)
        job.cancel()
        Unit
    } }

    @Test fun refreshWithoutStopIsNoOp() {
        val vm = TransitStopsViewModel(app)
        vm.refresh() // no stop selected: must not throw or change state
        assertNull(vm.selected.value)
        vm.closeStop()
        assertEquals(DeparturesState.Idle, vm.departures.value)
    }

    @Test fun openTripResolvesMissingWithoutPack() { runBlocking {
        val vm = TransitStopsViewModel(app)
        vm.openTrip(4242L, 37.8, -122.4)
        assertEquals(TripSelection(4242L, 37.8, -122.4), vm.selectedTrip.value)
        awaitCond {
            vm.tripItinerary.value is TripItineraryState.Missing ||
                vm.tripItinerary.value is TripItineraryState.Loaded
        }
        assertEquals(TripItineraryState.Missing, vm.tripItinerary.value)
        vm.closeTrip()
        assertNull(vm.selectedTrip.value)
        assertEquals(TripItineraryState.Idle, vm.tripItinerary.value)
        Unit
    } }

    @Test fun openTripForDepartureWithoutVehicleIdIsNoOp() {
        val vm = TransitStopsViewModel(app)
        val dep = Departure(
            line = "N", headsign = "Downtown", scheduledMillis = 0, realtimeMillis = 0,
            delayMinutes = 0, realTime = false, platform = null, mode = "BUS",
            routeColor = null, cancelled = false, tripVehicleId = null,
        )
        vm.openTripForDeparture(dep, 37.8, -122.4)
        assertNull(vm.selectedTrip.value)
        // With an id it opens the trip (which then resolves Missing without a pack)
        vm.openTripForDeparture(dep.copy(tripVehicleId = 77L), 37.8, -122.4)
        assertEquals(77L, vm.selectedTrip.value?.vehicleId)
        vm.closeTrip()
    }

    @Test fun openNearestStopFallsBackToCoordinateStop() { runBlocking {
        val vm = TransitStopsViewModel(app)
        vm.openNearestStop(37.8, -122.4, "Palo Alto")
        awaitCond { vm.selected.value != null }
        val selected = vm.selected.value
        assertNotNull(selected)
        // No pack resolves a MOTIS id in the sandbox: the fallback stop carries the POI name
        assertEquals("Palo Alto", selected.name)
        assertEquals(37.8, selected.lat); assertEquals(-122.4, selected.lon)
        vm.closeStop()
        Unit
    } }

    @Test fun rideEstimateAbsentTaxi() { runBlocking {
        val ctx = app as android.content.Context
        assertTrue(!RideEstimateClient.isInstalled(ctx))
        // estimate must degrade to null, not throw, when taxi is missing
        assertNull(RideEstimateClient.estimate(ctx, 37.0, -122.0, 37.1, -122.1, "A", "B"))
        Unit
    } }

    @Test fun rideInstalledCheckWithShadowPackage() {
        val pm = Shadows.shadowOf(app.packageManager) as ShadowApplicationPackageManager
        val pi = PackageInfo().apply {
            packageName = RideHandoffContract.PACKAGE
            applicationInfo = ApplicationInfo().apply { packageName = RideHandoffContract.PACKAGE }
        }
        pm.installPackage(pi)
        assertTrue(RideEstimateClient.isInstalled(app))
    }

    @Test fun bookingDeepLinkFormat() {
        val link = RideHandoffContract.bookingDeepLink(37.0, -122.0, "Home", 37.1, -122.1, "Work")
        val uri: Uri = link.toUri()
        assertEquals("taxi", uri.scheme); assertEquals("book", uri.host)
        assertEquals("37.0", uri.getQueryParameter(RideHandoffContract.PARAM_PICKUP_LAT))
        assertEquals("Home", uri.getQueryParameter(RideHandoffContract.PARAM_PICKUP_LABEL))
        assertEquals("Work", uri.getQueryParameter(RideHandoffContract.PARAM_DEST_LABEL))
        // Blank labels are omitted
        val bare = RideHandoffContract.bookingDeepLink(1.0, 2.0, "  ", 3.0, 4.0, null).toUri()
        assertNull(bare.getQueryParameter(RideHandoffContract.PARAM_PICKUP_LABEL))
        assertNull(bare.getQueryParameter(RideHandoffContract.PARAM_DEST_LABEL))
        assertEquals("com.vayunmathur.taxi", RideHandoffContract.PACKAGE)
    }
}
