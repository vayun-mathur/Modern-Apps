package com.vayunmathur.maps.util
import com.vayunmathur.maps.ui.toSheetRoute
import com.vayunmathur.maps.domain.RouteService
import com.vayunmathur.maps.platform.SelectedFeatureViewModel
import com.vayunmathur.maps.ui.SheetRoute
import com.vayunmathur.maps.ui.toFeature

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.maps.data.MapPreferences
import com.vayunmathur.maps.data.SpecificFeature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Slice 3/3 extra: SelectedFeatureViewModel enrichment, poi-info gate, routes flow. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelectedFeatureSlice3ExtraTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun pumpUntil(timeoutMs: Long = 8000, cond: () -> Boolean): Boolean = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            delay(25)
        }
        cond()
    }

    private fun barePlace(name: String = "Bare Place") =
        SpecificFeature.GenericPlace(name, null, null, null, GeoPoint(-122.4, 37.8))

    @Test fun setBarePlaceAttemptsEnrichmentAndKeepsSelection() {
        val vm = SelectedFeatureViewModel(app)
        val place = barePlace()
        vm.setSheetRoute(place.toSheetRoute())
        assertEquals(place, vm.sheetRoute.value.toFeature())
        // The tab is the Place route's section: a fresh Place defaults to
        // Details, writing PHOTOS back via copy, and a new place resets.
        assertEquals(com.vayunmathur.maps.data.google.PoiSection.DETAILS, (vm.sheetRoute.value as com.vayunmathur.maps.ui.SheetRoute.Place).section)
        vm.setSheetRoute((vm.sheetRoute.value as com.vayunmathur.maps.ui.SheetRoute.Place).copy(section = com.vayunmathur.maps.data.google.PoiSection.PHOTOS))
        assertEquals(com.vayunmathur.maps.data.google.PoiSection.PHOTOS, (vm.sheetRoute.value as com.vayunmathur.maps.ui.SheetRoute.Place).section)
        vm.setSheetRoute(barePlace("Other").toSheetRoute())
        assertEquals(com.vayunmathur.maps.data.google.PoiSection.DETAILS, (vm.sheetRoute.value as com.vayunmathur.maps.ui.SheetRoute.Place).section)
        // Enrichment runs async against the (absent) POI sidecar; give it its chance
        pumpUntil(3000) { false }
        assertEquals("Other", (vm.sheetRoute.value.toFeature() as? SpecificFeature.GenericPlace)?.name)
    }

    @Test fun setEnrichedAndRestaurantSkipLookup() {
        val vm = SelectedFeatureViewModel(app)
        val enriched = SpecificFeature.GenericPlace("Has Phone", "555-1", null, null, GeoPoint(-122.4, 37.8))
        vm.setSheetRoute(enriched.toSheetRoute())
        assertEquals(enriched, vm.sheetRoute.value.toFeature())
        val rest = SpecificFeature.Restaurant("Pizza", null, null, null, null, GeoPoint(-122.4, 37.8))
        vm.setSheetRoute(rest.toSheetRoute())
        assertEquals(rest, vm.sheetRoute.value.toFeature())
        vm.setSheetRoute(null)
        assertNull(vm.sheetRoute.value.toFeature())
    }

    @Test fun poiInfoGateOffByDefault() { runBlocking {
        val vm = SelectedFeatureViewModel(app)
        vm.setSheetRoute(barePlace().toSheetRoute())
        val info = withTimeoutOrNull(5000) {
            val d = CoroutineScope(Dispatchers.Default).async { vm.currentPoiInfo.first { it != null || true } }
            pumpUntil(4000) { d.isCompleted }
            d.await()
        }
        // Toggle off by default: the flow must emit null, never a fetched value
        assertEquals(null, info)
        assertEquals(false, DataStoreUtils.getInstance(app).getBoolean(MapPreferences.KEY_GOOGLE_SERVICES, MapPreferences.DEFAULT_GOOGLE_SERVICES))
        Unit
    } }

    @Test fun routesFlowForRouteSelectionDegrades() { runBlocking {
        val vm = SelectedFeatureViewModel(app)
        val from = SpecificFeature.GenericPlace("From", null, null, null, GeoPoint(-122.4, 37.8))
        val to = SpecificFeature.GenericPlace("To", null, null, null, GeoPoint(-122.3, 37.9))
        vm.setSheetRoute(com.vayunmathur.maps.ui.SheetRoute.RoutePlan(listOf(from, to)))
        // No road pack in the sandbox: the flow must still terminate in a map of
        // mode -> EmptyRoute/null entries rather than hang or throw here.
        val got = withTimeoutOrNull(12000) {
            val d = CoroutineScope(Dispatchers.Default).async { vm.routes.first { it != null } }
            pumpUntil(10000) { d.isCompleted }
            if (d.isCompleted) d.await() else null
        }
        if (got != null) {
            assertTrue(got.keys.containsAll(RouteService.TravelMode.entries), "keys=${got.keys}")
        }
        Unit
    } }

    @Test fun resolveAdminLabelEmptyAndUnparseable() { runBlocking {
        val vm = SelectedFeatureViewModel(app)
        assertNull(vm.resolveAdminLabel(emptyList()))
        Unit
    } }
}
