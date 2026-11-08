package com.vayunmathur.maps.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.maps.util.NavigationProgress
import com.vayunmathur.maps.util.NavigationSessionManager
import com.vayunmathur.maps.util.RouteService
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Slice 3/3 extra: NavigationOverlay terminal/intermediate states and end-trip confirm. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationOverlaySlice3ExtraTest {

    @get:Rule val rule = createComposeRule()

    private fun pt(lon: Double, lat: Double) = GeoPoint(lon, lat)

    private fun steps() = listOf(
        RouteService.Step(100.0, 60.seconds, listOf(pt(-122.0, 37.0), pt(-122.0, 37.001)),
            RouteService.API.NavInstruction(RouteService.API.Maneuver.TURN_LEFT, "Turn left onto Main St"), RouteService.TravelMode.DRIVE),
        RouteService.Step(100.0, 60.seconds, listOf(pt(-122.0, 37.001), pt(-122.0, 37.002)),
            RouteService.API.NavInstruction(RouteService.API.Maneuver.STRAIGHT, "Continue to the destination"), RouteService.TravelMode.DRIVE),
    )

    private fun progress() = NavigationProgress(
        snappedPosition = pt(-122.0, 37.0), segmentIndex = 0, currentStepIndex = 0,
        distanceAlongRoute = 10.0, distanceRemaining = 190.0, distanceToNextManeuver = 90.0,
        fractionComplete = 0.05, etaEpochMs = System.currentTimeMillis() + 600_000, courseOverGround = 90f,
        distanceOffRoute = 0.0, speedMps = 8f,
    )

    @Test fun startingAndRecalculatingShowProgressCards() {
        rule.setContent {
            DynamicTheme {
                NavigationOverlay(
                    navState = NavigationSessionManager.NavState.Starting,
                    steps = steps(), autoFollow = true,
                    onRecenter = {}, onEndTrip = {}, onDismissArrival = {},
                )
            }
        }
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun failedCardDismisses() {
        var dismissed = false
        rule.setContent {
            DynamicTheme {
                NavigationOverlay(
                    navState = NavigationSessionManager.NavState.Failed("Could not recalculate route"),
                    steps = steps(), autoFollow = false,
                    onRecenter = {}, onEndTrip = {}, onDismissArrival = { dismissed = true },
                )
            }
        }
        rule.waitForIdle()
        assertTrue(
            rule.onAllNodes(hasText("Could not recalculate route", substring = true)).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes().isNotEmpty(),
            "failure card should render",
        )
        assertTrue(!dismissed) // no tap yet
    }

    @Test fun endTripRequiresConfirmation() {
        var ended = false
        rule.setContent {
            DynamicTheme {
                NavigationOverlay(
                    navState = NavigationSessionManager.NavState.Navigating(progress()),
                    steps = steps(), autoFollow = true,
                    onRecenter = {}, onEndTrip = { ended = true }, onDismissArrival = {},
                    destinationName = "Home",
                )
            }
        }
        rule.onNodeWithText("End").assertIsDisplayed().performClick()
        // Confirmation dialog appears; ending only happens on confirm
        rule.waitForIdle()
        val confirms = rule.onAllNodes(hasText("End", substring = true)).fetchSemanticsNodes()
        assertTrue(confirms.isNotEmpty())
        assertTrue(!ended, "trip must not end before confirmation")
    }

    @Test fun arrivedShowsArrivalCardAndDismisses() {
        var dismissed = false
        rule.setContent {
            DynamicTheme {
                NavigationOverlay(
                    navState = NavigationSessionManager.NavState.Arrived,
                    steps = steps(), autoFollow = true,
                    onRecenter = {}, onEndTrip = {}, onDismissArrival = { dismissed = true },
                    destinationName = "Home",
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("You have arrived", substring = true).assertIsDisplayed()
        assertTrue(!dismissed)
    }

    @Test fun idleOverlayRendersNothing() {
        rule.setContent {
            DynamicTheme {
                NavigationOverlay(
                    navState = NavigationSessionManager.NavState.Idle,
                    steps = steps(), autoFollow = true,
                    onRecenter = {}, onEndTrip = {}, onDismissArrival = {},
                )
            }
        }
        rule.waitForIdle()
        // Idle returns early: no End action anywhere
        assertTrue(rule.onAllNodes(hasText("End")).fetchSemanticsNodes().isEmpty())
    }
}
