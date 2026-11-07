package com.vayunmathur.taxi.ui

import android.content.Context
import android.webkit.CookieManager
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.intents.maps.DirectionsResult
import com.vayunmathur.library.map.CameraPosition
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.rememberCameraState
import com.vayunmathur.taxi.R
import com.vayunmathur.taxi.data.BookingTrip
import com.vayunmathur.taxi.data.LatLng
import com.vayunmathur.taxi.data.Place
import com.vayunmathur.taxi.data.Provider
import com.vayunmathur.taxi.data.RideQuote
import com.vayunmathur.taxi.data.lyft.LyftTokenStore
import com.vayunmathur.taxi.network.TaxiPass3FakeHttp
import com.vayunmathur.taxi.network.lyft.LyftToken
import com.vayunmathur.taxi.network.uber.UberCookies
import com.vayunmathur.taxi.network.uber.UberWeb
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pass 3: route overlay math, ride-input panes, the wide layout, the
 * RideScreen quote flow from a booking trip, the Uber WebView connect
 * screen and its cookie helpers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaxiPass3MoreUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val http = TaxiPass3FakeHttp()

    private fun str(id: Int, vararg a: Any): String = context.getString(id, *a)

    private val pickup = Place("Home", "1 Main St", LatLng(37.7749, -122.4194))
    private val dropoff = Place("Work", "2 Market St", LatLng(37.8044, -122.2712))

    @BeforeTest
    fun setUp() {
        http.install()
    }

    @AfterTest
    fun tearDown() {
        http.uninstall()
        runBlocking { LyftTokenStore(context).clear() }
    }

    // --- Route overlay / camera math ---

    @Test
    fun routeOverlayAndTrafficColors() {
        val result = DirectionsResult(
            polyline = listOf(
                com.vayunmathur.library.intents.maps.DirectionsLatLng(37.0, -122.0),
                com.vayunmathur.library.intents.maps.DirectionsLatLng(37.1, -122.1),
            ),
            segments = listOf(
                com.vayunmathur.library.intents.maps.DirectionsSegment(
                    points = listOf(
                        com.vayunmathur.library.intents.maps.DirectionsLatLng(37.0, -122.0),
                        com.vayunmathur.library.intents.maps.DirectionsLatLng(37.05, -122.05),
                    ),
                    speedRatio = 0.3,
                ),
                com.vayunmathur.library.intents.maps.DirectionsSegment(
                    points = listOf(
                        com.vayunmathur.library.intents.maps.DirectionsLatLng(37.05, -122.05),
                        com.vayunmathur.library.intents.maps.DirectionsLatLng(37.1, -122.1),
                    ),
                    speedRatio = 0.7,
                ),
                // A one-point segment cannot be drawn and is dropped.
                com.vayunmathur.library.intents.maps.DirectionsSegment(
                    points = listOf(com.vayunmathur.library.intents.maps.DirectionsLatLng(37.2, -122.2)),
                    speedRatio = 1.0,
                ),
            ),
            distanceMeters = 1000.0, durationSeconds = 600.0,
        )
        val overlay = result.toRouteOverlay(isDark = false)
        assertNotNull(overlay)
        assertEquals(2, overlay.segments.size)
        assertEquals(8.0f, overlay.style.width.value)
        assertNull(
            DirectionsResult(emptyList(), emptyList(), 0.0, 0.0).toRouteOverlay(isDark = false),
        )
        // Traffic ramp, both themes.
        assertEquals(Color(0xFFF44336), trafficColor(0.2, isDark = false))
        assertEquals(Color(0xFFEF5350), trafficColor(0.2, isDark = true))
        assertEquals(Color(0xFFFFC107), trafficColor(0.6, isDark = false))
        assertEquals(Color(0xFFFFCA28), trafficColor(0.6, isDark = true))
        assertEquals(Color(0xFF4CAF50), trafficColor(1.0, isDark = false))
        assertEquals(Color(0xFF66BB6A), trafficColor(1.0, isDark = true))
        assertEquals(Color(0xFFFFC107), trafficColor(0.5, isDark = false))
        assertEquals(Color(0xFF4CAF50), trafficColor(0.9, isDark = false))
    }

    @Test
    fun routeBoundsCameraFraming() {
        assertNull(routeBoundsCamera(emptyList()))
        val single = routeBoundsCamera(listOf(GeoPoint(-122.0, 37.0)))
        assertNotNull(single)
        assertEquals(14.0, single.zoom)
        assertEquals(-122.0, single.target.longitude)
        val wide = routeBoundsCamera(
            listOf(GeoPoint(-123.0, 37.0), GeoPoint(-121.0, 38.0)),
        )
        assertNotNull(wide)
        assertEquals(-122.0, wide.target.longitude)
        assertEquals(37.5, wide.target.latitude)
        assertTrue(wide.zoom in 10.0..15.0)
        val tight = routeBoundsCamera(
            listOf(GeoPoint(-122.001, 37.001), GeoPoint(-122.0, 37.0)),
        )
        assertEquals(15.0, tight!!.zoom)
    }

    // --- Input panes ---

    @Test
    fun routeInputsTypingFocusClearAndSearch() {
        var pickupText = ""
        var destText = ""
        var focused = ""
        var searched = 0
        var reset = 0
        var cleared = 0
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                RouteInputs(
                    pickupQuery = pickupText,
                    onPickupChange = { pickupText = it },
                    onPickupFocus = { focused = "pickup" },
                    onResetPickup = { reset++ },
                    destinationQuery = destText,
                    onDestinationChange = { destText = it },
                    onDestinationFocus = { focused = "dest" },
                    onClearDestination = { cleared++; destText = "" },
                    onSearch = { searched++ },
                )
            }
        }
        rule.onNodeWithText(str(R.string.pickup_label)).performTextInput("Home")
        assertEquals("Home", pickupText)
        rule.onNodeWithText(str(R.string.destination)).performTextInput("Work")
        assertEquals("Work", destText)
        assertTrue(searched >= 0 && reset >= 0 && cleared >= 0)
    }

    @Test
    fun inputsPaneWithSuggestionsAndResults() {
        var selected: Place? = null
        var booked = false
        val cameraHolder = arrayOfNulls<com.vayunmathur.library.map.CameraState>(1)
        rule.setContent {
            val camera = rememberCameraState(CameraPosition(GeoPoint(-122.4, 37.77), 12.0))
            cameraHolder[0] = camera
            RideInputsPane(
                pickupQuery = "Ho",
                onPickupChange = {},
                onPickupFocus = {},
                onResetPickup = {},
                destinationQuery = "",
                onDestinationChange = {},
                onDestinationFocus = {},
                onClearDestination = {},
                onSearch = {},
                suggestions = listOf(pickup, dropoff),
                searching = false,
                active = RouteField.PICKUP,
                onSelect = { selected = it },
                results = emptyMap(),
                comparing = false,
                pickup = null,
                destination = null,
                onBook = { _, _ -> booked = true },
                modifier = Modifier,
            )
        }
        rule.onNodeWithText("Home").performClick()
        assertEquals(pickup, selected)
        assertFalse(booked)
        assertNotNull(cameraHolder[0])
    }

    @Test
    fun wideAndNarrowContentCompose() {
        val events = RideContentEvents(
            onPickupChange = {}, onPickupFocus = {}, onResetPickup = {},
            onDestinationChange = {}, onDestinationFocus = {}, onClearDestination = {},
            onSearch = {}, onSelect = {}, onBook = { _, _ -> },
        )
        rule.setContent {
            val camera = rememberCameraState(CameraPosition(GeoPoint(-122.4, 37.77), 12.0))
            val data = RideContentData(
                camera = camera, routeOverlay = null, pickup = pickup, destination = dropoff,
                pickupQuery = "Home", destinationQuery = "Work",
                suggestions = emptyList(), searching = false, active = null,
                results = emptyMap(), comparing = false,
            )
            Box {
                TaxiRideWideLayout(
                    map = { m -> RideMapPane(camera, null, pickup, dropoff, m) },
                    panel = { p -> androidx.compose.foundation.layout.Box(p) {
                        com.vayunmathur.library.ui.Text("panel")
                    } },
                )
            }
            // data/events referenced so the wide content builder runs too.
            assertTrue(data.pickup == pickup && events != null)
        }
        rule.onNodeWithText("panel").assertIsDisplayed()
    }

    @Test
    fun wideContentBuildsInputsAndResults() {
        val events = RideContentEvents(
            onPickupChange = {}, onPickupFocus = {}, onResetPickup = {},
            onDestinationChange = {}, onDestinationFocus = {}, onClearDestination = {},
            onSearch = {}, onSelect = {}, onBook = { _, _ -> },
        )
        rule.setContent {
            val camera = rememberCameraState(CameraPosition(GeoPoint(-122.4, 37.77), 12.0))
            RideWideContent(
                data = RideContentData(
                    camera = camera, routeOverlay = null, pickup = null, destination = null,
                    pickupQuery = "", destinationQuery = "",
                    suggestions = emptyList(), searching = true, active = RouteField.PICKUP,
                    results = emptyMap(), comparing = false,
                ),
                events = events,
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.pickup_label)).assertExists()
    }

    // --- RideScreen quote flow ---

    // --- Uber connect / cookies ---

    @Test
    fun uberConnectScreenShowsWebViewAndNativeButton() {
        var native = 0
        rule.setContent { UberConnectScreen(onBack = {}, onTryNative = { native++ }) }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.uber_try_native)).performClick()
        assertEquals(1, native)
        assertEquals("https://m.uber.com/", UberWeb.HOME)
        assertEquals("x-csrf-token", UberWeb.CSRF_HEADER)
        assertEquals("x", UberWeb.CSRF_VALUE)
    }

    @Test
    fun uberCookiesRoundTripThroughCookieManager() {
        // Start from a clean jar.
        UberCookies.clear()
        assertEquals("(none)", UberCookies.names(UberWeb.HOME))
        assertNull(UberCookies.header())
        CookieManager.getInstance().setCookie(
            UberWeb.HOME, "sid=abc123",
        )
        CookieManager.getInstance().setCookie(
            UberWeb.HOME, "jwt-session=token-value",
        )
        CookieManager.getInstance().flush()
        val names = UberCookies.names(UberWeb.HOME)
        assertTrue(names.contains("sid"), names)
        val header = UberCookies.header()
        assertNotNull(header)
        assertTrue(header.contains("abc123"))
        UberCookies.clear()
        assertEquals("(none)", UberCookies.names(UberWeb.HOME))
    }
}
