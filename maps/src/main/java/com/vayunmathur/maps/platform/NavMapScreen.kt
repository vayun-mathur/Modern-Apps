package com.vayunmathur.maps.platform
import com.vayunmathur.maps.service.NavigationService
import com.vayunmathur.maps.data.string

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.vayunmathur.maps.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.maps.domain.NavigationProgress
import com.vayunmathur.maps.platform.NavigationSessionManager
import com.vayunmathur.maps.platform.NavigationSessionManager.NavState
import com.vayunmathur.maps.domain.RouteService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.vayunmathur.library.map.GeoPoint
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * The main Android Auto screen (P12): a full-screen map drawn to the car
 * [android.view.Surface] by [CarMapRenderer], with a [NavigationTemplate] on top.
 *
 * While a navigation session is active (driven by the existing
 * [NavigationSessionManager] — the same singleton the phone uses) the template
 * shows the upcoming maneuver, step cue, distance to the maneuver, lane guidance
 * (P5a) and an ETA/remaining-distance [TravelEstimate], and the camera follows
 * the puck. When idle it shows the map centred on the current location with a
 * Search action.
 *
 * This screen only *renders* nav state and forwards camera/puck to the renderer;
 * all routing, progress, reroute and voice guidance stay in the existing stack.
 */
class NavMapScreen(carContext: CarContext) : Screen(carContext) {

    private val renderer = CarMapRenderer(carContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * The route whose geometry is already on the GPU. Identity, not equality: the route
     * mesh is tessellated once when set, so pushing the same polyline on every GPS fix
     * would re-tessellate a cross-city route a few times a second.
     */
    private var pushedRoute: RouteService.Route? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java)
                    .setSurfaceCallback(renderer)
                // Prime the idle camera before the first nav fix arrives.
                pushCameraAndOverlay(NavigationSessionManager.state.value)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                renderer.destroy()
                scope.cancel()
            }
        })

        scope.launch {
            NavigationSessionManager.state.collect { state ->
                pushCameraAndOverlay(state)
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val state = NavigationSessionManager.state.value
        val builder = NavigationTemplate.Builder()
            .setActionStrip(buildActionStrip(state))

        when (state) {
            is NavState.Navigating -> {
                buildRoutingInfo(state.progress)?.let { builder.setNavigationInfo(it) }
                buildTravelEstimate(state.progress)?.let { builder.setDestinationTravelEstimate(it) }
            }
            NavState.Starting,
            NavState.Recalculating ->
                builder.setNavigationInfo(RoutingInfo.Builder().setLoading(true).build())
            else -> { /* idle / arrived / failed: map + action strip only */ }
        }
        return builder.build()
    }

    // ----------------------------------------------------------------
    // Renderer feeding
    // ----------------------------------------------------------------

    private fun pushCameraAndOverlay(state: NavState) {
        pushRouteIfChanged()
        val progress = (state as? NavState.Navigating)?.progress
        if (progress != null) {
            // Heading-up: courseOverGround is the direction of travel in degrees clockwise
            // from north, and camera bearing is the compass direction that points UP, so it
            // goes across unmodified. The puck's cone rotates with the map, so feeding it
            // the same absolute course leaves it pointing up the screen.
            renderer.setPuck(progress.snappedPosition, progress.courseOverGround)
            renderer.setCamera(
                target = progress.snappedPosition,
                zoom = NAVIGATING_ZOOM,
                bearing = progress.courseOverGround.toDouble(),
            )
        } else {
            val pos = lastKnownPosition()
            if (pos != null) {
                // Idle: north-up, and no cone because there is no heading.
                renderer.setPuck(pos, null)
                renderer.setCamera(pos, IDLE_ZOOM)
            }
        }
    }

    private fun pushRouteIfChanged() {
        val route = NavigationSessionManager.session.value.route
        if (route === pushedRoute) return
        pushedRoute = route
        renderer.setRoute(route?.polyline)
    }

    // ----------------------------------------------------------------
    // Template building
    // ----------------------------------------------------------------

    private fun buildActionStrip(state: NavState): ActionStrip {
        val builder = ActionStrip.Builder()
        builder.addAction(
            Action.Builder()
                .setTitle(carContext.getString(UiR.string.search))
                .setOnClickListener { screenManager.push(CarSearchScreen(carContext)) }
                .build()
        )
        val navigating = state is NavState.Navigating ||
            state is NavState.Recalculating || state is NavState.Starting
        if (navigating) {
            builder.addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.nav_action_end))
                    .setOnClickListener { stopNavigation() }
                    .build()
            )
        }
        return builder.build()
    }

    private fun buildRoutingInfo(progress: NavigationProgress): RoutingInfo? {
        val steps = NavigationSessionManager.session.value.route?.step ?: return null
        val info = RoutingInfo.Builder()
        // The maneuver the driver is approaching is the START of the next step;
        // distanceToNextManeuver counts down to it (matches NavigationTts).
        val upcomingIdx = progress.currentStepIndex + 1
        if (upcomingIdx in steps.indices) {
            info.setCurrentStep(
                carStep(steps[upcomingIdx], withLanes = true),
                metersToDistance(progress.distanceToNextManeuver),
            )
            val nextIdx = upcomingIdx + 1
            if (nextIdx in steps.indices) {
                info.setNextStep(carStep(steps[nextIdx], withLanes = false))
            }
        } else {
            // On the final step: point at the destination.
            val destName = NavigationSessionManager.session.value.destinationName
                ?: carContext.getString(R.string.nav_default_destination)
            val dest = Step.Builder(destName)
                .setManeuver(Maneuver.Builder(Maneuver.TYPE_DESTINATION).build())
                .build()
            info.setCurrentStep(dest, metersToDistance(progress.distanceRemaining))
        }
        return info.build()
    }

    private fun carStep(step: RouteService.Step, withLanes: Boolean): Step {
        val cue = step.navInstruction.instructions
            .ifBlank { carContext.getString(UiR.string.continue_label) }
        val builder = Step.Builder(cue)
            .setManeuver(CarManeuvers.build(step.navInstruction.maneuver))
        if (withLanes && step.lanes.isNotEmpty()) {
            CarManeuvers.lanes(step.lanes).forEach { builder.addLane(it) }
        }
        return builder.build()
    }

    private fun buildTravelEstimate(progress: NavigationProgress): TravelEstimate? {
        val arrival = DateTimeWithZone.create(progress.etaEpochMs, TimeZone.getDefault())
        val remainingSec = ((progress.etaEpochMs - System.currentTimeMillis()) / MILLIS_PER_SECOND)
            .coerceAtLeast(0)
        return TravelEstimate.Builder(metersToDistance(progress.distanceRemaining), arrival)
            .setRemainingTimeSeconds(remainingSec)
            .build()
    }

    private fun metersToDistance(meters: Double): Distance =
        if (meters < METERS_PER_KILOMETER) {
            Distance.create(meters.roundToInt().toDouble(), Distance.UNIT_METERS)
        } else {
            Distance.create(meters / METERS_PER_KILOMETER, Distance.UNIT_KILOMETERS_P1)
        }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private fun stopNavigation() {
        runCatching {
            carContext.startService(
                Intent(carContext, NavigationService::class.java)
                    .apply { action = NavigationService.ACTION_STOP }
            )
        }
    }

    private fun lastKnownPosition(): GeoPoint? {
        if (ContextCompat.checkSelfPermission(
                carContext, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return null
        val lm = carContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val loc: Location? = runCatching {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull()
        return loc?.let { GeoPoint(longitude = it.longitude, latitude = it.latitude) }
    }

    private companion object {
        const val NAVIGATING_ZOOM = 17.0
        const val IDLE_ZOOM = 15.0
        /** Millis per second, for the remaining-time estimate. */
        const val MILLIS_PER_SECOND = 1000L
        /** Metres per kilometre, for the car distance display. */
        const val METERS_PER_KILOMETER = 1000.0
    }
}
