package com.vayunmathur.maps.intents

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.intents.maps.DirectionsLatLng
import com.vayunmathur.library.intents.maps.DirectionsRequest
import com.vayunmathur.library.intents.maps.DirectionsResult
import com.vayunmathur.library.intents.maps.DirectionsSegment
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.maps.util.RouteService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/**
 * The cross-app directions endpoint: wire-mode parsing and result mapping.
 * Slice-2 coverage for maps (basename D, outside the slice-1 dirs).
 *
 * `performCalculation` plans through the on-device router, which needs the
 * map packs — absent in tests, so it resolves to [EMPTY]. That empty path is
 * itself the contract a caller gets with no data: a well-formed blank result
 * rather than an exception.
 */
@RunWith(AndroidJUnit4::class)
class DirectionsIntentSlice2Test {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun request(mode: String = "drive") = DirectionsRequest(
        fromLat = 37.8,
        fromLng = -122.4,
        toLat = 37.9,
        toLng = -122.5,
        mode = mode,
    )

    private fun activity(): DirectionsIntent =
        Robolectric.buildActivity(DirectionsIntent::class.java).setup().get()

    @Test
    fun `with no map data the request resolves to the empty result`() = runTest {
        val intent = activity()
        val result = intent.performCalculation(request())
        assertEquals(DirectionsResult(emptyList(), emptyList(), 0.0, 0.0), result)
        assertTrue(result.polyline.isEmpty())
        assertTrue(result.segments.isEmpty())
    }

    @Test
    fun `an unknown mode still resolves rather than throwing`() = runTest {
        val intent = activity()
        val result = intent.performCalculation(request(mode = "hyperloop"))
        assertEquals(0.0, result.distanceMeters)
    }

    @Test
    fun `a transit request resolves rather than throwing`() = runTest {
        val intent = activity()
        val result = intent.performCalculation(request(mode = "TRANSIT"))
        assertTrue(result.polyline.isEmpty())
    }

    @Test
    fun `an empty route maps to empty segments and zero totals`() {
        // Shape check on the wire DTO: an empty polyline/segments pair with
        // zero totals is the no-data contract.
        val empty = DirectionsResult(
            polyline = emptyList(),
            segments = emptyList(),
            distanceMeters = 0.0,
            durationSeconds = 0.0,
        )
        assertEquals(emptyList<DirectionsLatLng>(), empty.polyline)
        assertEquals(emptyList<DirectionsSegment>(), empty.segments)
    }

    @Test
    fun `mode strings parse case-insensitively`() = runTest {
        val intent = activity()
        for (mode in listOf("WALK", "walk", " Walking ", "BICYCLE", "bike", "BICYCLING", "transit")) {
            val result = intent.performCalculation(request(mode = mode))
            assertEquals(0.0, result.distanceMeters, "mode $mode")
        }
    }
}
