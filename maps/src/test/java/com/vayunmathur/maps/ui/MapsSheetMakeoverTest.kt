package com.vayunmathur.maps.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.maps.domain.RouteService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The Google-Maps-style sheet makeover: route option cards render and select. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapsSheetMakeoverTest {

    @get:Rule val rule = createComposeRule()

    private fun route(minutes: Int, line: String?): RouteService.Route {
        val steps = listOf(
            RouteService.Step(
                distanceMeters = 5_000.0, staticDuration = 10.minutes,
                polyline = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.02, 0.0)),
                navInstruction = RouteService.API.NavInstruction(),
                travelMode = RouteService.TravelMode.TRANSIT,
                transitDetails = line?.let {
                    RouteService.API.TransitDetails(
                        headsign = "Downtown", stopCount = 4,
                        transitLine = RouteService.API.TransitLine(name = it, nameShort = it, color = "#c00"),
                        stopDetails = RouteService.API.StopDetails(
                            arrivalTime = "10:10", departureTime = "10:00",
                            arrivalStop = RouteService.API.Stop("C"),
                            departureStop = RouteService.API.Stop("A"),
                        ),
                    )
                },
            ),
        )
        return RouteService.Route(
            duration = minutes.minutes, distanceMeters = 5_000.0,
            polyline = steps.first().polyline, step = steps, arrivalTime = "10:10",
        )
    }

    @Test
    fun `route option card shows duration arrival and line chip and clicks`() {
        var clicks = 0
        rule.setContent {
            DynamicTheme {
                RouteOptionCard(route = route(14, "Red"), selected = true, onClick = { clicks++ })
            }
        }
        rule.onNodeWithText("Red").assertIsDisplayed()
        rule.onNodeWithText("10:10", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Red").performClick()
        assertEquals(1, clicks)
    }
}
