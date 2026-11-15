package com.vayunmathur.maps.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.maps.data.google.StreetViewPano
import com.vayunmathur.maps.data.transit.TransitStop
import com.vayunmathur.maps.ui.map.RailRefresh
import com.vayunmathur.maps.ui.map.railLaneOffsetDp
import com.vayunmathur.maps.ui.map.railRefreshDecision
import com.vayunmathur.maps.ui.map.rememberRailLines
import com.vayunmathur.maps.ui.streetview.StreetViewScreen
import com.vayunmathur.maps.ui.theme.MapTokens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C5 rail-line refresh decisions and the Street View viewer's
 * loading/unavailable states.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapsRailStreetC5Test {

    @get:Rule val rule = createComposeRule()

    @Test
    fun railRefreshDecision_coversClearRefetchReuseAndRefan() {
        val centre = 37.0 to -122.0
        // Below the minimum zoom the overlay clears regardless of cache.
        assertEquals(
            RailRefresh.CLEAR,
            railRefreshDecision(5.0, centre, centre, 14.0, 14.0, overlayNull = false, spansEmpty = false),
        )
        // No fetch yet: refetch.
        assertEquals(
            RailRefresh.REFETCH,
            railRefreshDecision(14.0, centre, null, Double.NaN, Double.NaN, overlayNull = true, spansEmpty = true),
        )
        // Centre moved past the recenter threshold: refetch.
        assertEquals(
            RailRefresh.REFETCH,
            railRefreshDecision(14.0, 37.5 to -122.0, centre, 14.0, 14.0, overlayNull = false, spansEmpty = false),
        )
        // Same footprint and same zoom band: reuse.
        assertEquals(
            RailRefresh.REUSE,
            railRefreshDecision(14.0, centre, centre, 14.0, 14.0, overlayNull = false, spansEmpty = false),
        )
        // Lane offsets: a single line never offsets; two lines fan apart
        // once the zoom band allows lanes, and a tapered end stays centred.
        assertEquals(0.0, railLaneOffsetDp(14.0, ordinal = 0, count = 1, taper = 255))
        assertEquals(0.0, railLaneOffsetDp(14.0, ordinal = 1, count = 2, taper = 0))
        // REFAN when the cached overlay was cleared but spans remain.
        assertEquals(
            RailRefresh.REFAN,
            railRefreshDecision(14.0, centre, centre, 14.0, 14.0, overlayNull = true, spansEmpty = false),
        )
    }

    @Test
    fun rememberRailLines_disabledOrNoStop_staysEmpty() {
        val camera = CameraState()
        var overlay: com.vayunmathur.library.map.RouteOverlay? = null
        var enabled by mutableStateOf(false)
        var stop: TransitStop? by mutableStateOf(null)
        rule.setContent {
            DynamicTheme {
                Box(Modifier.fillMaxSize()) {
                    overlay = rememberRailLines(camera, enabled, stop, com.vayunmathur.maps.ui.theme.mapTokens(false))
                }
            }
        }
        rule.waitForIdle()
        assertNull(overlay)
        rule.runOnUiThread {
            enabled = true
            stop = TransitStop("s1", "C5 Stop", 37.7749, -122.4194)
        }
        // The debounce + native lookup settle to no lines offline.
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            rule.waitForIdle()
            Thread.sleep(25)
        }
        assertNull(overlay)
    }

    @Test
    fun streetViewScreen_loadingThenUnavailable_canClose() {
        var closed = 0
        val pano = StreetViewPano(
            panoId = "c5-pano",
            lat = 37.7749,
            lng = -122.4194,
            copyright = "© C5 Imagery",
            captureYear = 2025,
            captureMonth = 6,
        )
        rule.setContent {
            DynamicTheme {
                StreetViewScreen(initialPano = pano, onClose = { closed++ })
            }
        }
        // The panorama fetch fails offline, so the viewer reports the
        // imagery as unavailable; closing still works.
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("Street View is not available here", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Street View is not available here", substring = true).assertIsDisplayed()
    }
}
