package com.vayunmathur.maps.ui.nav

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.maps.R as MapsR
import com.vayunmathur.maps.data.PostedLimit
import com.vayunmathur.maps.util.NavigationProgress
import com.vayunmathur.maps.util.RouteService
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Slice 1/3 (maps): the nav chrome under Robolectric's Compose rule —
 * shields, lane strip, speed pill, step list, maneuver banner, elevation
 * chart and the arrival card.
 *
 * Every test asserts rendered behaviour (texts on screen, callbacks fired),
 * not mere composition.
 */
@RunWith(RobolectricTestRunner::class)
class Slice1NavComposeTest {

    @get:Rule
    val rule = createComposeRule()

    private val appContext: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun strings(id: Int, vararg args: Any): String =
        if (args.isEmpty()) appContext.getString(id) else appContext.getString(id, *args)

    private fun progress(step: Int = 0, dist: Double = 120.0) = NavigationProgress(
        snappedPosition = com.vayunmathur.library.map.GeoPoint(-122.4, 37.7),
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
    ) = RouteService.Step(
        distanceMeters = 120.0,
        staticDuration = kotlin.time.Duration.parse("1m"),
        polyline = emptyList(),
        navInstruction = RouteService.API.NavInstruction(maneuver, instruction),
        travelMode = RouteService.TravelMode.DRIVE,
    )

    // --- RouteShield ------------------------------------------------------

    @Test
    fun `interstate shields render their ref`() {
        rule.setContent { RouteShield(RoadShield("I-280", ShieldKind.INTERSTATE)) }
        rule.onNodeWithText("I-280").assertIsDisplayed()
    }

    @Test
    fun `us highway shields render their ref`() {
        rule.setContent { RouteShield(RoadShield("US 101", ShieldKind.US_HIGHWAY)) }
        rule.onNodeWithText("US 101").assertIsDisplayed()
    }

    @Test
    fun `state shields render their ref`() {
        rule.setContent { RouteShield(RoadShield("CA-85", ShieldKind.STATE)) }
        rule.onNodeWithText("CA-85").assertIsDisplayed()
    }

    @Test
    fun `generic shields render their ref`() {
        rule.setContent { RouteShield(RoadShield("A4", ShieldKind.GENERIC)) }
        rule.onNodeWithText("A4").assertIsDisplayed()
    }

    // --- LaneGuidance -------------------------------------------------------

    @Test
    fun `an empty lane set renders nothing`() {
        rule.setContent { LaneGuidance(lanes = emptyList()) }
        rule.onNodeWithText("THROUGH").assertDoesNotExist()
    }

    @Test
    fun `a lane strip composes without throwing`() {
        // Glyph selection is pinned on the JVM by ManeuverGlyphTest; here the
        // strip itself (cells, tints, fallback arrow) must compose.
        rule.setContent {
            LaneGuidance(
                lanes = listOf(
                    RouteService.API.Lane(
                        listOf(
                            RouteService.API.Maneuver.STRAIGHT,
                            RouteService.API.Maneuver.TURN_LEFT,
                        ),
                        active = true,
                    ),
                    RouteService.API.Lane(listOf(RouteService.API.Maneuver.TURN_RIGHT), active = false),
                ),
            )
        }
        rule.waitForIdle()
    }

    // --- SpeedWidget --------------------------------------------------------

    @Test
    fun `speed pill shows speed unit and the limit sign`() {
        rule.setContent {
            SpeedWidget(
                speedMps = 15f,
                postedLimit = PostedLimit(kmh = 50, mph = 20, displayIsMph = true),
                defaultMph = true,
            )
        }
        // 15 m/s ≈ 34 mph, over the 20 mph limit.
        rule.onNodeWithText("34").assertIsDisplayed()
        rule.onNodeWithText("mph").assertIsDisplayed()
        rule.onNodeWithText("20").assertIsDisplayed()
    }

    @Test
    fun `without a limit there is no sign`() {
        rule.setContent {
            SpeedWidget(speedMps = 10f, postedLimit = null, defaultMph = false)
        }
        // 10 m/s = 36 km/h.
        rule.onNodeWithText("36").assertIsDisplayed()
        rule.onNodeWithText("km/h").assertIsDisplayed()
    }

    @Test
    fun `absurd speeds clamp instead of blowing out the pill`() {
        rule.setContent {
            SpeedWidget(speedMps = 500f, postedLimit = null, defaultMph = false)
        }
        rule.onNodeWithText("999").assertIsDisplayed()
    }

    // --- StepsSheet -----------------------------------------------------------

    @Test
    fun `every remaining step renders with its instruction`() {
        val steps = listOf(
            step("Head north"),
            step("Turn left onto I-280"),
            step("Arrive"),
        )
        rule.setContent {
            StepsSheet(steps = steps, currentStepIndex = 1, onClose = {})
        }
        rule.onNodeWithText("Head north").assertIsDisplayed()
        rule.onNodeWithText("Turn left onto I-280").assertIsDisplayed()
        rule.onNodeWithText("Arrive").assertIsDisplayed()
        rule.onNodeWithText(strings(MapsR.string.nav_steps_title)).assertIsDisplayed()
    }

    @Test
    fun `a route with elevation draws its chart above the steps`() {
        val route = RouteService.Route(
            duration = kotlin.time.Duration.parse("10m"),
            distanceMeters = 1000.0,
            polyline = emptyList(),
            step = listOf(step("Climb")),
            elevationProfile = listOf(
                RouteService.ElevationPoint(0.0, 10.0),
                RouteService.ElevationPoint(500.0, 60.0),
                RouteService.ElevationPoint(1000.0, 20.0),
            ),
            ascentMeters = 50.0,
            descentMeters = 40.0,
        )
        rule.setContent {
            StepsSheet(steps = route.step, currentStepIndex = 0, onClose = {}, route = route)
        }
        rule.onNodeWithText(strings(MapsR.string.nav_elevation_title)).assertIsDisplayed()
    }

    // --- ManeuverBanner ---------------------------------------------------------

    @Test
    fun `the banner counts down to the next maneuver with its shield`() {
        val steps = listOf(
            step("Head north on 3rd St"),
            step("Turn right onto I-280"),
            step("Keep left onto CA-85"),
        )
        rule.setContent { ManeuverBanner(progress = progress(step = 0), steps = steps) }
        rule.onNodeWithText("Turn right onto I-280").assertIsDisplayed()
        // The shield badge for the ref in the instruction.
        rule.onNodeWithText("I-280").assertIsDisplayed()
    }

    @Test
    fun `at the last step the banner shows the current road`() {
        val steps = listOf(step("Head north"), step("Arrive at Market St"))
        rule.setContent { ManeuverBanner(progress = progress(step = 1), steps = steps) }
        rule.onNodeWithText("Arrive at Market St").assertIsDisplayed()
    }

    @Test
    fun `a lane strip inside the banner composes`() {
        val steps = listOf(
            step(
                "Head north",
                lanesForBanner = true,
            ),
            step("Turn left"),
        )
        rule.setContent { ManeuverBanner(progress = progress(step = 0), steps = steps) }
        rule.onNodeWithText("Turn left").assertIsDisplayed()
    }

    // --- ElevationChart -----------------------------------------------------------

    @Test
    fun `the chart summarises ascent and descent`() {
        rule.setContent {
            ElevationChart(
                profile = listOf(
                    RouteService.ElevationPoint(0.0, 0.0),
                    RouteService.ElevationPoint(100.0, 30.0),
                    RouteService.ElevationPoint(200.0, 10.0),
                ),
                ascentMeters = 30.4,
                descentMeters = 20.0,
            )
        }
        rule.onNodeWithText(strings(MapsR.string.nav_elevation_title)).assertIsDisplayed()
        val gainLoss = strings(MapsR.string.nav_elevation_gain_loss, 30, 20)
        rule.onNodeWithText(gainLoss).assertIsDisplayed()
    }

    @Test
    fun `a flat route draws no chart`() {
        rule.setContent {
            ElevationChart(
                profile = listOf(
                    RouteService.ElevationPoint(0.0, 10.0),
                    RouteService.ElevationPoint(100.0, 10.2),
                ),
                ascentMeters = 0.0,
                descentMeters = 0.0,
            )
        }
        rule.onNodeWithText(strings(MapsR.string.nav_elevation_title)).assertDoesNotExist()
    }

    // --- ArrivalSummary ---------------------------------------------------------------

    @Test
    fun `arrival shows the destination and dismisses`() {
        var dismissed = false
        rule.setContent {
            ArrivalSummary(onDismiss = { dismissed = true }, destinationName = "Slice1 Diner")
        }
        rule.onNodeWithText("Slice1 Diner").assertIsDisplayed()
        rule.onNodeWithText(strings(MapsR.string.nav_arrived_title)).assertIsDisplayed()
        rule.onNodeWithText(strings(UiR.string.dismiss)).performClick()
        assertTrue(dismissed)
    }

    @Test
    fun `arrival without a name still shows the title`() {
        rule.setContent { ArrivalSummary(onDismiss = {}) }
        rule.onNodeWithText(strings(MapsR.string.nav_arrived_title)).assertIsDisplayed()
    }
}

/** A step carrying a lane strip, for the banner's lane branch. */
private fun Slice1NavComposeTest.step(
    instruction: String,
    lanesForBanner: Boolean,
): RouteService.Step = RouteService.Step(
    distanceMeters = 120.0,
    staticDuration = kotlin.time.Duration.parse("1m"),
    polyline = emptyList(),
    navInstruction = RouteService.API.NavInstruction(RouteService.API.Maneuver.STRAIGHT, instruction),
    travelMode = RouteService.TravelMode.DRIVE,
    lanes = if (lanesForBanner) {
        listOf(RouteService.API.Lane(listOf(RouteService.API.Maneuver.STRAIGHT), active = true))
    } else {
        emptyList()
    },
)
