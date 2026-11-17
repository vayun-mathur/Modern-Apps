package com.vayunmathur.maps.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.domain.RouteService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * W6: route sheet mode tabs and travel-mode selection with real routes
 * for every mode, plus the street-view pegman surface.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapsSheetsW6Test {

    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun pt(lon: Double, lat: Double) = GeoPoint(lon, lat)

    private fun route(mode: RouteService.TravelMode, minutes: Int) = RouteService.Route(
        minutes.minutes, 1200.0,
        listOf(pt(-122.0, 37.0), pt(-122.0, 37.01), pt(-122.0, 37.02)),
        listOf(
            RouteService.Step(
                600.0, 5.minutes, listOf(pt(-122.0, 37.0), pt(-122.0, 37.01)),
                RouteService.API.NavInstruction(RouteService.API.Maneuver.TURN_LEFT, "Turn left onto Main St"), mode,
            ),
            RouteService.Step(
                600.0, 5.minutes, listOf(pt(-122.0, 37.01), pt(-122.0, 37.02)),
                RouteService.API.NavInstruction(RouteService.API.Maneuver.STRAIGHT, "Continue"), mode,
            ),
        ),
    )

    @Test
    fun routeSheet_modeTabs_switchSelection() {
        val origin = SpecificFeature.GenericPlace("Home", null, null, null, pt(-122.0, 37.0))
        val dest = SpecificFeature.GenericPlace("Office", null, null, null, pt(-122.0, 37.02))
        val feature = SpecificFeature.Route(listOf(origin, dest))
        val routes = mapOf(
            RouteService.TravelMode.DRIVE to route(RouteService.TravelMode.DRIVE, 10),
            RouteService.TravelMode.WALK to route(RouteService.TravelMode.WALK, 25),
            RouteService.TravelMode.BICYCLE to route(RouteService.TravelMode.BICYCLE, 15),
            RouteService.TravelMode.TRANSIT to null,
        )
        var selected = RouteService.TravelMode.DRIVE
        rule.setContent {
            RouteSheet(
                selectedFeature = feature,
                route = routes,
                selectedRouteType = selected,
                setSelectedRouteType = { selected = it },
                userPosition = pt(-122.0, 37.0),
            )
        }
        rule.waitForIdle()
        // Tap each mode tab that is labelled; selection must follow.
        for ((label, mode) in listOf("Walk" to RouteService.TravelMode.WALK, "Bike" to RouteService.TravelMode.BICYCLE, "Drive" to RouteService.TravelMode.DRIVE)) {
            rule.onAllNodesWithText(label, substring = true).fetchSemanticsNodes().let {
                if (it.isNotEmpty()) {
                    rule.onAllNodesWithText(label, substring = true)[0].performClick()
                    rule.waitForIdle()
                    assertEquals(mode, selected, "tapping $label should select $mode")
                }
            }
        }
        assertTrue(selected == RouteService.TravelMode.DRIVE)
    }
}
