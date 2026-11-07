package com.vayunmathur.maps.ui.map

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.map.CameraPosition
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.maps.data.google.StreetViewDataSource
import com.vayunmathur.maps.data.google.StreetViewPano
import com.vayunmathur.maps.ui.streetview.StreetViewPegman
import com.vayunmathur.maps.ui.streetview.StreetViewScreen
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 1/3 second pass (maps): ElevationChart's draw path, the pegman's
 * drag lifecycle, and StreetViewScreen's unavailable/attribution states.
 */
@RunWith(RobolectricTestRunner::class)
class Slice1Pass2ComposeScreensTest {

    @get:Rule val rule = createComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `elevation chart draws a real profile and hides degenerate ones`() {
        val stage = androidx.compose.runtime.mutableIntStateOf(0)
        rule.setContent {
            when (stage.intValue) {
                0 -> com.vayunmathur.maps.ui.nav.ElevationChart(
                    profile = listOf(
                        com.vayunmathur.maps.util.RouteService.ElevationPoint(0.0, 100.0),
                        com.vayunmathur.maps.util.RouteService.ElevationPoint(500.0, 180.0),
                        com.vayunmathur.maps.util.RouteService.ElevationPoint(1000.0, 130.0),
                    ),
                    ascentMeters = 95.4,
                    descentMeters = 44.6,
                )
                1 -> com.vayunmathur.maps.ui.nav.ElevationChart(
                    profile = listOf(
                        com.vayunmathur.maps.util.RouteService.ElevationPoint(0.0, 100.0),
                    ),
                    ascentMeters = 0.0, descentMeters = 0.0,
                )
                2 -> com.vayunmathur.maps.ui.nav.ElevationChart(
                    profile = listOf(
                        com.vayunmathur.maps.util.RouteService.ElevationPoint(0.0, 50.0),
                        com.vayunmathur.maps.util.RouteService.ElevationPoint(100.0, 50.0),
                    ),
                    ascentMeters = 0.0, descentMeters = 0.0,
                )
                else -> com.vayunmathur.maps.ui.nav.ElevationChart(
                    profile = listOf(
                        com.vayunmathur.maps.util.RouteService.ElevationPoint(0.0, 10.0),
                        com.vayunmathur.maps.util.RouteService.ElevationPoint(0.0, 99.0),
                    ),
                    ascentMeters = 0.0, descentMeters = 0.0,
                )
            }
        }
        rule.onNodeWithText("Elevation").assertIsDisplayed()
        rule.onNodeWithText("95 m", substring = true).assertIsDisplayed()
        // single point, flat, and zero-distance profiles all render nothing
        for (next in 1..3) {
            rule.runOnIdle { stage.intValue = next }
            rule.waitForIdle()
            assertTrue(
                rule.onAllNodesWithText("Elevation").fetchSemanticsNodes().isEmpty(),
            )
        }
    }

    @Test
    fun `pegman drags and drops without a projection`() {
        val camera = CameraState(CameraPosition(GeoPoint(-122.4, 37.7), 12.0))
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                StreetViewPegman(camera = camera)
            }
        }
        rule.onNodeWithContentDescription("Drag onto the map to open Street View")
            .assertIsDisplayed()
        rule.onNodeWithContentDescription("Drag onto the map to open Street View")
            .performTouchInput { swipeLeft() }
        rule.waitForIdle()
        // no projection in the sandbox: the drop resolves to nothing and no
        // viewer dialog opens, but the drag lifecycle must not crash
        assertTrue(
            rule.onAllNodesWithText("Street View is not available here")
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `waypoint list removes an intermediate stop`() {
        fun place(name: String, lon: Double) = com.vayunmathur.maps.data.SpecificFeature.GenericPlace(
            name = name, phone = null, website = null, openingHours = null,
            position = GeoPoint(lon, 37.7),
        )
        val reorders = mutableListOf<com.vayunmathur.maps.data.SpecificFeature.Route>()
        val route = com.vayunmathur.maps.data.SpecificFeature.Route(
            listOf(place("Start", -122.5), place("Mid", -122.4), place("End", -122.3)),
        )
        rule.setContent {
            WaypointList(
                route = route,
                onReorder = { reorders += it },
                onEditWaypoint = {},
            )
        }
        rule.onNodeWithText("Mid").assertIsDisplayed()
        // the middle row's close button is the clickable node sharing its band
        val midNode = rule.onNodeWithText("Mid").fetchSemanticsNode()
        val midCenterY = midNode.boundsInRoot.center.y
        val clickables = rule.onAllNodes(
            androidx.compose.ui.test.hasClickAction(),
        ).fetchSemanticsNodes()
        val closeIndex = clickables.withIndex().first { (_, node) ->
            kotlin.math.abs(node.boundsInRoot.center.y - midCenterY) < 5f &&
                node.boundsInRoot.center.x > midNode.boundsInRoot.center.x &&
                node.size.width < 120
        }.index
        rule.onAllNodes(androidx.compose.ui.test.hasClickAction())[closeIndex].performClick()
        assertTrue(reorders.size == 1)
        assertEquals(2, reorders.first().waypoints.size)
        assertEquals(
            listOf("Start", "End"),
            reorders.first().waypoints.map { (it as? com.vayunmathur.maps.data.SpecificFeature.GenericPlace)?.name },
        )
    }

    @Test
    fun `street view screen shows unavailable when tiles cannot load`() {
        val originalBytes = StreetViewDataSource.httpGetBytes
        StreetViewDataSource.httpGetBytes = { null }
        try {
            var closed = false
            rule.setContent {
                StreetViewScreen(
                    initialPano = StreetViewPano(
                        panoId = "pano-nowhere-${System.nanoTime()}",
                        lat = 37.0, lng = -122.0,
                        copyright = "© Test Imagery",
                        captureYear = 2024, captureMonth = 6,
                    ),
                    onClose = { closed = true },
                )
            }
            val deadline = System.currentTimeMillis() + 10_000
            var shown = false
            while (!shown && System.currentTimeMillis() < deadline) {
                rule.waitForIdle()
                shown = rule.onAllNodesWithText("Street View is not available here")
                    .fetchSemanticsNodes().isNotEmpty()
                if (!shown) Thread.sleep(50)
            }
            assertTrue(shown)
            rule.onNodeWithText("© Test Imagery · 2024-06", substring = true)
                .assertIsDisplayed()
            assertTrue(!closed)
        } finally {
            StreetViewDataSource.httpGetBytes = originalBytes
        }
    }

    @Test
    fun `street view screen renders the sphere when tiles load`() {
        val originalBytes = StreetViewDataSource.httpGetBytes
        StreetViewDataSource.httpGetBytes = { _ ->
            // an 8x8 red PNG tile, decodable by the stitcher
            val bmp = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.RED)
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        try {
            rule.setContent {
                StreetViewScreen(
                    initialPano = StreetViewPano(
                        panoId = "pano-sphere-${System.nanoTime()}",
                        lat = 37.0, lng = -122.0,
                        tileSize = 8,
                        levelDims = listOf(16 to 16),
                        copyright = "© Sphere Test",
                    ),
                    onClose = {},
                )
            }
            // loading resolves into the sphere branch (attribution stays visible)
            val deadline = System.currentTimeMillis() + 10_000
            var ready = false
            while (!ready && System.currentTimeMillis() < deadline) {
                rule.waitForIdle()
                ready = rule.onAllNodesWithText("Street View is not available here")
                    .fetchSemanticsNodes().isEmpty() &&
                    rule.onAllNodesWithText("© Sphere Test", substring = true)
                        .fetchSemanticsNodes().isNotEmpty()
                if (!ready) Thread.sleep(50)
            }
            assertTrue(ready)
        } finally {
            StreetViewDataSource.httpGetBytes = originalBytes
        }
    }

    @Test
    fun `street view attribution falls back to google copyright without a date`() {
        val originalBytes = StreetViewDataSource.httpGetBytes
        StreetViewDataSource.httpGetBytes = { null }
        try {
            rule.setContent {
                StreetViewScreen(
                    initialPano = StreetViewPano(
                        panoId = "pano-nodate-${System.nanoTime()}",
                        lat = 37.0, lng = -122.0,
                    ),
                    onClose = {},
                )
            }
            rule.waitForIdle()
            rule.onNodeWithText("© Google", substring = true).assertIsDisplayed()
        } finally {
            StreetViewDataSource.httpGetBytes = originalBytes
        }
    }
}
