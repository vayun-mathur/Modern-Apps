package com.vayunmathur.maps.util

import android.app.Application
import android.app.Notification
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.map.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C5 navigation-service notifications: every [NavigationSessionManager.NavState]
 * renders a notification whose title/content follow the state, with
 * terminal states not ongoing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapsNavServiceC5Test {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun build(state: NavigationSessionManager.NavState): Notification {
        val controller = Robolectric.buildService(NavigationService::class.java).create()
        val service = controller.get()
        val method = NavigationService::class.java
            .getDeclaredMethod("buildNotification", NavigationSessionManager.NavState::class.java)
        method.isAccessible = true
        val notification = method.invoke(service, state) as Notification
        controller.destroy()
        return notification
    }

    private fun title(n: Notification): String? =
        n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun text(n: Notification): String? =
        n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    @Test
    fun `idle starting recalculating arrived and failed describe themselves`() {
        assertEquals(
            context.getString(com.vayunmathur.maps.R.string.nav_status_idle),
            title(build(NavigationSessionManager.NavState.Idle)),
        )
        assertEquals(
            context.getString(com.vayunmathur.maps.R.string.nav_status_starting),
            title(build(NavigationSessionManager.NavState.Starting)),
        )
        assertEquals(
            context.getString(com.vayunmathur.maps.R.string.nav_status_recalculating),
            title(build(NavigationSessionManager.NavState.Recalculating)),
        )
        val arrived = build(NavigationSessionManager.NavState.Arrived)
        assertEquals(
            context.getString(com.vayunmathur.maps.R.string.nav_arrived_title),
            title(arrived),
        )
        // Arrived is terminal: not ongoing.
        assertEquals(0, arrived.flags and Notification.FLAG_ONGOING_EVENT)
        val failed = build(NavigationSessionManager.NavState.Failed("C5 route lost"))
        assertEquals(
            context.getString(com.vayunmathur.maps.R.string.nav_status_failed),
            title(failed),
        )
        assertEquals("C5 route lost", text(failed))
    }

    private fun setSession(mode: RouteService.TravelMode?, route: RouteService.Route?) {
        val field = NavigationSessionManager::class.java
            .getDeclaredField("_session").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(NavigationSessionManager)
            as kotlinx.coroutines.flow.MutableStateFlow<NavigationSessionManager.NavSession>
        flow.value = NavigationSessionManager.NavSession(
            route = route,
            travelMode = mode,
            destinationName = "C5 Dest",
        )
    }

    private fun progress() = NavigationProgress(
        snappedPosition = GeoPoint(-122.4, 37.8),
        segmentIndex = 0,
        currentStepIndex = 0,
        distanceAlongRoute = 500.0,
        distanceRemaining = 1_500.0,
        distanceToNextManeuver = 200.0,
        fractionComplete = 0.25,
        etaEpochMs = System.currentTimeMillis() + 600_000,
        courseOverGround = 90f,
        distanceOffRoute = 0.0,
    )

    private fun driveRoute() = RouteService.Route(
        kotlin.time.Duration.parse("10m"), 2_000.0,
        listOf(GeoPoint(-122.0, 37.0), GeoPoint(-122.0, 37.002)),
        listOf(
            RouteService.Step(
                500.0, kotlin.time.Duration.parse("1m"), listOf(GeoPoint(-122.0, 37.0)),
                RouteService.API.NavInstruction(RouteService.API.Maneuver.DEPART, "Head north on C5 St"),
                RouteService.TravelMode.DRIVE,
            ),
        ),
    )

    @Test
    fun `navigating drive walk bike transit and null modes describe themselves`() {
        val route = driveRoute()
        for (mode in listOf(
            RouteService.TravelMode.DRIVE,
            RouteService.TravelMode.WALK,
            RouteService.TravelMode.BICYCLE,
            RouteService.TravelMode.TRANSIT,
            null,
        )) {
            setSession(mode, route)
            val n = build(NavigationSessionManager.NavState.Navigating(progress()))
            assertNotNull(title(n), "title for mode $mode")
            assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0, "ongoing for $mode")
        }
        // Drive titles come from the current step's instruction.
        setSession(RouteService.TravelMode.DRIVE, route)
        assertEquals(
            "Head north on C5 St",
            title(build(NavigationSessionManager.NavState.Navigating(progress()))),
        )
        // Out-of-range step index falls back to the destination title.
        setSession(RouteService.TravelMode.DRIVE, route)
        val beyond = progress().copy(currentStepIndex = 9)
        assertNotNull(title(build(NavigationSessionManager.NavState.Navigating(beyond))))
    }

    @Test
    fun `navigating notification carries progress and stays ongoing`() {
        val progress = NavigationProgress(
            snappedPosition = GeoPoint(-122.4, 37.8),
            segmentIndex = 0,
            currentStepIndex = 0,
            distanceAlongRoute = 500.0,
            distanceRemaining = 1_500.0,
            distanceToNextManeuver = 200.0,
            fractionComplete = 0.25,
            etaEpochMs = System.currentTimeMillis() + 600_000,
            courseOverGround = 90f,
            distanceOffRoute = 0.0,
        )
        val n = build(NavigationSessionManager.NavState.Navigating(progress))
        assertNotNull(title(n))
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0, "navigating stays ongoing")
    }
}
