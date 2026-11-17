package com.vayunmathur.maps.util
import com.vayunmathur.maps.data.Feature
import com.vayunmathur.maps.data.Geometry
import com.vayunmathur.maps.data.Point
import com.vayunmathur.maps.ui.toSheetRoute
import com.vayunmathur.maps.data.MappedPoiBytes
import com.vayunmathur.maps.domain.RouteService
import com.vayunmathur.maps.platform.SelectedFeatureViewModel
import com.vayunmathur.maps.ui.toFeature

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.maps.data.SpecificFeature
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * C5 selected-feature view-model: route planning fan-out, admin-label
 * resolution over rendered candidates, and focus/consume plumbing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapsSelectedFeatureC5Test {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun pt(lon: Double, lat: Double) = GeoPoint(lon, lat)

    @Test
    fun `route feature plans every travel mode offline`() {
        val vm = SelectedFeatureViewModel(app)
        val routeFeature = SpecificFeature.Route(
            listOf(
                SpecificFeature.GenericPlace("From", null, null, null, pt(-122.0, 37.0)),
                SpecificFeature.GenericPlace("To", null, null, null, pt(-122.0, 37.002)),
            ),
        )
        vm.setSheetRoute(routeFeature.toSheetRoute())
        // stateIn(WhileSubscribed) only runs while collected.
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
        val collectJob = scope.launch {
            vm.routes.collect {}
        }
        var planned: Map<RouteService.TravelMode, RouteService.RouteType?>? = null
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(50, java.util.concurrent.TimeUnit.MILLISECONDS)
            val routes = vm.routes.value
            if (routes != null && routes.keys.containsAll(RouteService.TravelMode.entries)) {
                planned = routes
                break
            }
            Thread.sleep(10)
        }
        assertTrue(planned != null, "all modes planned, was ${vm.routes.value}")
        // No native graph is loaded: every mode resolves to an empty
        // route or a null plan slot, never a real route.
        assertTrue(
            planned!!.values.all { it == null || it is RouteService.EmptyRoute },
            "offline plans carry no real route: $planned",
        )
        collectJob.cancel()
    }

    @Test
    fun `clearing the selection empties planned routes`() {
        val vm = SelectedFeatureViewModel(app)
        vm.setSheetRoute(null)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertNull(vm.routes.value)
    }

    @Test
    fun `resolveAdminLabel parses the first matching candidate`() = runBlocking {
        val vm = SelectedFeatureViewModel(app)
        val feature = com.vayunmathur.maps.data.Feature<com.vayunmathur.maps.data.Geometry, kotlinx.serialization.json.JsonObject?>(
            geometry = com.vayunmathur.maps.data.Point(pt(-122.4, 37.8)),
            properties = kotlinx.serialization.json.buildJsonObject {
                put("kind", kotlinx.serialization.json.JsonPrimitive("locality"))
                put("name", kotlinx.serialization.json.JsonPrimitive("San Francisco"))
            },
        )
        val resolved = vm.resolveAdminLabel(listOf(feature))
        assertTrue(resolved is SpecificFeature.Admin2Label, "was $resolved")
        assertEquals("San Francisco", (resolved as SpecificFeature.Admin2Label).name)
        assertNull(vm.resolveAdminLabel(emptyList()))
    }

    @Test
    fun `select and focus only applies to routable features`() {
        val vm = SelectedFeatureViewModel(app)
        val place = SpecificFeature.GenericPlace("C5 Town", null, null, null, pt(-122.4, 37.8))
        vm.selectAndFocus(place, zoom = 15.0)
        assertEquals(place, vm.pendingPlace.value)
        assertEquals(15.0, vm.pendingFocus.value?.zoom)
        vm.consumeFocus()
        assertNull(vm.pendingFocus.value)
    }

    @Test
    fun `mapped section reads are little endian`() {
        val bytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        bytes.putInt(0x11223344)
        bytes.putLong(0x0102030405060708L)
        bytes.putShort(0x1234)
        val mapped = MappedPoiBytes(ByteBuffer.wrap(bytes.array()).order(ByteOrder.LITTLE_ENDIAN))
        assertEquals(0x11223344, mapped.getInt(0))
        assertEquals(0x0102030405060708L, mapped.getLong(4))
        assertEquals(0x1234, mapped.getShort(12).toInt())
        assertEquals(16, mapped.size)
    }
}
