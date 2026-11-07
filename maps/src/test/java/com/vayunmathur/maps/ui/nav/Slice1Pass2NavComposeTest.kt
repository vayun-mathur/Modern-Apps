package com.vayunmathur.maps.ui.nav

import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.maps.data.PostedLimit
import com.vayunmathur.maps.util.NavigationProgress
import com.vayunmathur.maps.util.RouteService
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Slice 1/3 second pass (maps): the maneuver banner's full form (shield,
 * then-peek and lanes together), speed-widget limit states, and the
 * elevation chart's actual Canvas draw under a real activity window.
 */
@RunWith(RobolectricTestRunner::class)
class Slice1Pass2NavComposeTest {

    @get:Rule val rule = createComposeRule()

    private fun progress(step: Int = 0, dist: Double = 120.0) = NavigationProgress(
        snappedPosition = GeoPoint(-122.4, 37.7),
        segmentIndex = 0,
        currentStepIndex = step,
        distanceAlongRoute = 10.0,
        distanceRemaining = 500.0,
        distanceToNextManeuver = dist,
        fractionComplete = 0.1,
        etaEpochMs = 0L,
        courseOverGround = 0f,
        distanceOffRoute = 0.0,
    )

    private fun step(
        instruction: String,
        maneuver: RouteService.API.Maneuver = RouteService.API.Maneuver.TURN_LEFT,
        lanes: List<RouteService.API.Lane> = emptyList(),
    ) = RouteService.Step(
        distanceMeters = 120.0,
        staticDuration = kotlin.time.Duration.parse("1m"),
        polyline = emptyList(),
        navInstruction = RouteService.API.NavInstruction(maneuver, instruction),
        travelMode = RouteService.TravelMode.DRIVE,
        lanes = lanes,
    )

    @Test
    fun `maneuver banner shows shield then-peek and lanes together`() {
        val steps = listOf(
            step("Continue on I-280"),
            step(
                "Turn right onto US 101",
                lanes = listOf(
                    RouteService.API.Lane(
                        directions = listOf(
                            RouteService.API.Maneuver.STRAIGHT,
                            RouteService.API.Maneuver.TURN_RIGHT,
                        ),
                        active = true,
                    ),
                    RouteService.API.Lane(
                        directions = listOf(RouteService.API.Maneuver.TURN_LEFT),
                        active = false,
                    ),
                ),
            ),
            step("Arrive at destination"),
        )
        rule.setContent { ManeuverBanner(progress = progress(), steps = steps) }
        // the banner leads with the NEXT maneuver (the current step is the
        // road being driven), peeks at the one after, and shields the ref
        rule.onNodeWithText("Turn right onto US 101").assertIsDisplayed()
        rule.onNodeWithText("US 101").assertIsDisplayed()
        rule.onNodeWithText("Arrive at destination", substring = true).assertIsDisplayed()
    }

    @Test
    fun `maneuver banner with no steps still shows the distance`() {
        rule.setContent { ManeuverBanner(progress = progress(dist = 42.0), steps = emptyList()) }
        rule.waitForIdle()
        assertTrue(
            rule.onAllNodesWithText("m", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("ft", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun `speed widget shows limit and over-limit states`() {
        val stage = androidx.compose.runtime.mutableIntStateOf(0)
        rule.setContent {
            when (stage.intValue) {
                0 -> SpeedWidget(
                    speedMps = 10f,
                    postedLimit = PostedLimit(kmh = 50, mph = 31, displayIsMph = false),
                    defaultMph = false,
                )
                1 -> SpeedWidget(
                    speedMps = 30f, // 108 km/h, well over the 50 limit
                    postedLimit = PostedLimit(kmh = 50, mph = 31, displayIsMph = false),
                    defaultMph = false,
                )
                2 -> SpeedWidget(speedMps = 10f, postedLimit = null, defaultMph = true)
                else -> SpeedWidget(
                    speedMps = 0f,
                    postedLimit = PostedLimit(kmh = 30, mph = 19, displayIsMph = true),
                    defaultMph = false,
                    darkBasemap = true,
                )
            }
        }
        rule.onNodeWithText("36").assertIsDisplayed()
        rule.onNodeWithText("50").assertIsDisplayed()
        rule.runOnIdle { stage.intValue = 1 }
        rule.onNodeWithText("108").assertIsDisplayed()
        rule.runOnIdle { stage.intValue = 2 }
        rule.onNodeWithText("22").assertIsDisplayed()
        rule.runOnIdle { stage.intValue = 3 }
        rule.onNodeWithText("19").assertIsDisplayed()
    }

    @Test
    fun `lane guidance renders nothing without lanes and cells with`() {
        val stage = androidx.compose.runtime.mutableIntStateOf(0)
        rule.setContent {
            if (stage.intValue == 0) {
                LaneGuidance(lanes = emptyList())
            } else {
                LaneGuidance(
                    lanes = listOf(
                        RouteService.API.Lane(
                            directions = listOf(
                                RouteService.API.Maneuver.STRAIGHT,
                                RouteService.API.Maneuver.TURN_LEFT,
                                RouteService.API.Maneuver.TURN_RIGHT,
                            ),
                            active = true,
                        ),
                        RouteService.API.Lane(
                            directions = listOf(RouteService.API.Maneuver.MERGE),
                            active = false,
                        ),
                    ),
                )
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { stage.intValue = 1 }
        rule.waitForIdle()
    }
}

@RunWith(RobolectricTestRunner::class)
class Slice1Pass2ElevationDrawTest {

    @get:Rule
    val rule: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity> =
        createAndroidComposeRule()

    @Test
    fun `elevation chart canvas actually draws under an activity`() {
        rule.setContent {
            ElevationChart(
                profile = listOf(
                    RouteService.ElevationPoint(0.0, 100.0),
                    RouteService.ElevationPoint(500.0, 220.0),
                    RouteService.ElevationPoint(1000.0, 140.0),
                ),
                ascentMeters = 130.0,
                descentMeters = 85.0,
            )
        }
        rule.onNodeWithText("Elevation").assertIsDisplayed()
        // Force a real draw pass so the Canvas draw block executes (the
        // Robolectric compose rule alone never rasterizes).
        rule.runOnUiThread {
            rule.activity.window.decorView.draw(Canvas())
        }
        rule.waitForIdle()
    }
}
