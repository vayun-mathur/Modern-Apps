package com.vayunmathur.maps.platform
import com.vayunmathur.maps.domain.RouteService
import com.vayunmathur.maps.data.OfflineRouterRoadRoutes
import com.vayunmathur.maps.domain.NavigationProgress
import com.vayunmathur.maps.domain.PolylineIndex
import com.vayunmathur.maps.domain.deriveProgress
import com.vayunmathur.maps.service.NavigationService

import java.util.concurrent.atomic.AtomicBoolean
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import com.vayunmathur.library.log.Log
import androidx.core.content.ContextCompat
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.domain.RouteService.TravelMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.vayunmathur.library.map.GeoPoint

/**
 * Process-global state machine for an in-progress navigation session.
 *
 * Mirrors the pattern used by `findfamily/.../UwbSessionManager`: a singleton
 * `object` owns the entire lifecycle, exposes a [StateFlow] for any observer
 * (UI, foreground service notification), and is fed by [NavigationService]
 * which keeps the process alive while the user navigates.
 *
 * Why a singleton (not a ViewModel-scoped owner): the user expects driving
 * navigation to continue when the app is backgrounded or the screen turns off
 * (foreground service requirement), and to survive activity recreation
 * (rotation, system-initiated kills). A ViewModel can't do either.
 */
object NavigationSessionManager {

    private const val TAG = "NavSession"

    /** Distance (m) off the polyline before we start considering "off-route". */
    private const val OFF_ROUTE_THRESHOLD_M = 50.0

    /** How long we must remain off-route before auto-recalculating (ms). */
    private const val OFF_ROUTE_DEBOUNCE_MS = 5_000L

    /** Minimum gap between consecutive recalc attempts (ms). */
    private const val RECALC_COOLDOWN_MS = 15_000L

    /** Distance (m) below which we consider the user "arrived". */
    private const val ARRIVAL_RADIUS_M = 30.0

    /** Speed (m/s) above which we trust GPS course-over-ground for bearing. */
    private const val GPS_COURSE_MIN_SPEED_MPS = 1.0f

    /** Fastest fix cadence asked of either provider. */
    private const val LOCATION_MIN_TIME_MS = 1000L
    /** No distance floor: the off-route policy filters, not the OS. */
    private const val LOCATION_MIN_DISTANCE_M = 0f
    /** Full compass circle (degrees), for normalising the fused bearing. */
    private const val FULL_CIRCLE_DEG = 360f
    /** Max recalculation attempts before the session fails. */
    private const val MAX_RECALC_ATTEMPTS = 3

    /** UI / notification state for the navigation session. */
    sealed interface NavState {
        /** Singleton holding idle state and operations. */
        data object Idle : NavState
        /** Singleton holding starting state and operations. */
        data object Starting : NavState
        /** Immutable value describing navigating; carries progress. */
        data class Navigating(val progress: NavigationProgress) : NavState
        /** Singleton holding recalculating state and operations. */
        data object Recalculating : NavState
        /** Singleton holding arrived state and operations. */
        data object Arrived : NavState
        /** Immutable value describing failed; carries reason. */
        data class Failed(val reason: String) : NavState
    }

    private val _state: MutableStateFlow<NavState> = MutableStateFlow(NavState.Idle)
    val state: StateFlow<NavState> = _state.asStateFlow()

    /**
     * What is being navigated, as opposed to how it is going.
     *
     * Deliberately a sibling of [NavState] rather than fields on its variants: route, mode and
     * destination are orthogonal to the state machine, so folding them in would make every
     * transition re-copy them — and a transition that forgets to is exactly the bug this fixes.
     *
     * A [StateFlow] because these were plain `@Volatile var`s read straight from composition,
     * which Compose cannot observe. A post-recalculation route swap therefore did not
     * recompose, and the UI kept drawing the OLD route's steps against the new progress.
     *
     * No Compose or lifecycle dependency, so the process-global lifetime this object
     * deliberately has is unchanged, and it stays safe to read from the background service.
     */
    data class NavSession(
        val route: RouteService.Route? = null,
        val travelMode: TravelMode? = null,
        val destinationName: String? = null,
    )

    private val _session = MutableStateFlow(NavSession())
    val session: StateFlow<NavSession> = _session.asStateFlow()

    /** Travel mode for the active session (or last session). Used by UI/notification. */
    @Deprecated("Read session.value.travelMode, or collect session for a snapshot-correct read.")
    val travelMode: TravelMode? get() = _session.value.travelMode

    /** Human-readable destination shown in the notification. */
    @Deprecated("Read session.value.destinationName, or collect session.")
    val destinationName: String? get() = _session.value.destinationName

    /** The active route (snapshot used by UI for step list / polyline split). */
    @Deprecated("Read session.value.route, or collect session.")
    val currentRoute: RouteService.Route? get() = _session.value.route

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var appContext: Context

    private var polylineIndex: PolylineIndex? = null
    private var destination: GeoPoint? = null
    private var lastSegmentIndex: Int = 0
    private var offRouteSinceMs: Long? = null
    private var lastRecalcMs: Long = 0L
    private var recalcAttempts: Int = 0
    private var locationJob: Job? = null
    private var recalcJob: Job? = null

    // Location/compass source. We don't reuse FrameworkLocationManager because
    // navigation needs the raw Location for speed-based bearing selection,
    // which FrameworkLocationManager doesn't expose.
    private var locationManager: LocationManager? = null
    private var sensorManager: SensorManager? = null
    private var registeredLocationListener: LocationListener? = null
    private var registeredSensorListener: SensorEventListener? = null

    /** Compass-derived heading (from sensor fusion). */
    @Volatile private var compassBearing: Float = 0f

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        Log.status(TAG, "init")
    }

    /**
     * Begin navigating along [route] in [mode]. [destination] is used for
     * off-route recalculation. [destinationLabel] populates the notification.
     */
    fun start(
        route: RouteService.Route,
        mode: TravelMode,
        destination: GeoPoint,
        destinationLabel: String,
    ) {
        if (!initialized.get()) {
            Log.status(TAG, "start() called before init()")
            return
        }
        if (_state.value !is NavState.Idle && _state.value !is NavState.Failed) {
            Log.status(TAG, "start() called while already in state ${_state.value}; ignoring")
            return
        }
        Log.status(
            TAG,
            "start(mode=$mode, label=$destinationLabel, " +
                "steps=${route.step.size}, dist=${route.distanceMeters})",
        )
        // One emission, so an observer never sees a route from this session paired with a
        // destination from the last one.
        _session.value = NavSession(route = route, travelMode = mode, destinationName = destinationLabel)
        this.destination = destination
        polylineIndex = PolylineIndex(route)
        lastSegmentIndex = 0
        offRouteSinceMs = null
        recalcAttempts = 0
        _state.value = NavState.Starting
        startLocationCollection()
    }

    /** End the navigation session. Cancels jobs, stops location, resets state. */
    fun stop() {
        Log.status(TAG, "stop")
        locationJob?.cancel()
        locationJob = null
        recalcJob?.cancel()
        recalcJob = null
        stopLocationCollection()
        // Drop TTS bookkeeping so a follow-up start() doesn't suppress
        // threshold cues whose stepIndex happens to collide with the old
        // route's. NavigationService.shutdown() also fully tears down TTS
        // when the service is destroyed; this is the in-process equivalent
        // for stop-without-service-teardown paths.
        NavigationTts.reset()
        polylineIndex = null
        _session.value = NavSession()
        destination = null
        lastSegmentIndex = 0
        offRouteSinceMs = null
        _state.value = NavState.Idle
    }

    // ----------------------------------------------------------------
    // Location / sensor wiring
    // ----------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startLocationCollection() {
        // Hard gate on ACCESS_FINE_LOCATION — without it requestLocationUpdates
        // throws SecurityException.
        if (ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            _state.value = NavState.Failed("Location permission not granted")
            return
        }

        if (locationManager == null) {
            locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        }
        if (sensorManager == null) {
            sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        }

        val lm = locationManager!!
        val sm = sensorManager!!

        val locListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                handleLocation(location)
            }
            // Required by old LocationListener interface: status callbacks are
            // no longer delivered on API 29+.
            @Deprecated("Unused; kept for the old LocationListener interface.")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        registeredLocationListener = locListener
        runCatching {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    LOCATION_MIN_TIME_MS,
                    LOCATION_MIN_DISTANCE_M,
                    locListener,
                )
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    LOCATION_MIN_TIME_MS,
                    LOCATION_MIN_DISTANCE_M,
                    locListener,
                )
            }
        }.onFailure { Log.error(TAG, "requestLocationUpdates failed", it) }

        // Compass fusion for the stationary case (start-of-route, traffic stop).
        val sensorListener = object : SensorEventListener {
            private val accel = FloatArray(3)
            private val mag = FloatArray(3)
            private val rot = FloatArray(9)
            private val angles = FloatArray(3)
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> event.values.copyInto(accel)
                    Sensor.TYPE_MAGNETIC_FIELD -> event.values.copyInto(mag)
                }
                if (SensorManager.getRotationMatrix(rot, null, accel, mag)) {
                    SensorManager.getOrientation(rot, angles)
                    val az = Math.toDegrees(angles[0].toDouble()).toFloat()
                    compassBearing = (az + FULL_CIRCLE_DEG) % FULL_CIRCLE_DEG
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        registeredSensorListener = sensorListener
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
            sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also {
            sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }

        // Seed an initial fix from the last known location so the UI doesn't
        // sit on Starting until the next GPS tick.
        runCatching {
            val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            last?.let { handleLocation(it) }
        }
    }

    private fun stopLocationCollection() {
        registeredLocationListener?.let { listener ->
            runCatching { locationManager?.removeUpdates(listener) }
        }
        registeredLocationListener = null
        registeredSensorListener?.let { listener ->
            runCatching { sensorManager?.unregisterListener(listener) }
        }
        registeredSensorListener = null
    }

    // ----------------------------------------------------------------
    // Per-fix processing
    // ----------------------------------------------------------------

    private fun handleLocation(location: Location) {
        // Once we've Arrived (or hit a terminal Failed state) we want fixes
        // to stop driving the state machine — otherwise normal GPS jitter
        // (±10 m around the destination) flicks the user back into
        // Navigating and re-enqueues the service's 8s self-stop timer.
        if (_state.value is NavState.Arrived || _state.value is NavState.Failed) return

        val index = polylineIndex ?: return
        val position = GeoPoint(longitude = location.longitude, latitude = location.latitude)
        val snap = index.snap(position, lastSegmentIndex)
        lastSegmentIndex = snap.segmentIndex

        val bearing = chooseBearing(location)
        val progress = index.deriveProgress(
            snap,
            courseOverGround = bearing,
            speedMps = if (location.hasSpeed()) location.speed else 0f,
        )

        // Arrival check first — once arrived, stop processing and let the UI
        // show its end-of-trip card before being torn down.
        if (progress.distanceRemaining <= ARRIVAL_RADIUS_M) {
            _state.value = NavState.Arrived
            // Drop the location subscription so subsequent fixes can't
            // un-arrive us. The service's auto-cleanup timer takes over.
            stopLocationCollection()
            return
        }

        // Off-route detection + debounced recalc. Skip for transit — a
        // train/bus rider is genuinely "off" the polyline most of the
        // trip (tunnels, GPS drift inside a vehicle), and a recalc would
        // replace the planned transit route with a walking route mid-trip.
        val now = System.currentTimeMillis()
        if (_session.value.travelMode != TravelMode.TRANSIT && snap.distanceOffRoute > OFF_ROUTE_THRESHOLD_M) {
            val since = offRouteSinceMs ?: run {
                offRouteSinceMs = now
                now
            }
            val elapsed = now - since
            val canRecalc = (now - lastRecalcMs) >= RECALC_COOLDOWN_MS && recalcAttempts < MAX_RECALC_ATTEMPTS
            if (elapsed >= OFF_ROUTE_DEBOUNCE_MS && canRecalc && _state.value !is NavState.Recalculating) {
                triggerRecalculate(position)
                return
            }
        } else {
            offRouteSinceMs = null
        }

        _state.value = NavState.Navigating(progress)
    }

    private fun chooseBearing(location: Location): Float = when {
        location.hasBearing() && location.hasSpeed() && location.speed >= GPS_COURSE_MIN_SPEED_MPS ->
            location.bearing
        else -> compassBearing
    }

    // ----------------------------------------------------------------
    // Off-route recalculation
    // ----------------------------------------------------------------

    private fun triggerRecalculate(from: GeoPoint) {
        val dest = destination ?: return
        val mode = _session.value.travelMode ?: return
        Log.status(TAG, "off-route; recalculating from $from to $dest (attempt ${recalcAttempts + 1})")
        _state.value = NavState.Recalculating
        recalcAttempts++
        lastRecalcMs = System.currentTimeMillis()
        recalcJob?.cancel()
        recalcJob = scope.launch {
            val newRoute = runCatching {
                // Wrap the existing destination + current pos into a Route
                // request the same way the bottom sheet does.
                val fromFeature = SpecificFeature.GenericPlace(
                    name = "From",
                    phone = null,
                    website = null,
                    openingHours = null,
                    position = from,
                )
                val toFeature = SpecificFeature.GenericPlace(
                    name = _session.value.destinationName ?: "Destination",
                    phone = null,
                    website = null,
                    openingHours = null,
                    position = dest,
                )
                val routeFeature = SpecificFeature.Route(listOf(fromFeature, toFeature))
                OfflineRouterRoadRoutes.getRouteMulti(appContext, routeFeature, from, mode)
            }.getOrNull()

            if (newRoute == null) {
                Log.status(TAG, "recalc failed")
                if (recalcAttempts >= MAX_RECALC_ATTEMPTS) {
                    _state.value = NavState.Failed("Could not recalculate route")
                } else {
                    // Don't immediately rip the user back to Failed — drop
                    // back into Navigating on the old route; the next off-route
                    // window may succeed.
                    offRouteSinceMs = null
                }
                return@launch
            }

            // If the user reached the destination while the recalc was in
            // flight, drop the recalc result on the floor — otherwise we'd
            // overwrite the (still-valid) Arrived state with a fresh,
            // longer route and the user would be "un-arrived".
            if (_state.value is NavState.Arrived) {
                Log.status(TAG, "recalc completed but user already arrived; discarding new route")
                return@launch
            }

            // Swap in the new polyline. Reset state for the new shape.
            _session.value = _session.value.copy(route = newRoute)
            polylineIndex = PolylineIndex(newRoute)
            lastSegmentIndex = 0
            offRouteSinceMs = null
            recalcAttempts = 0
            NavigationTts.reset()
            // The location listener is still running; the next fix will move
            // us into Navigating on the new route.
            Log.status(TAG, "recalc succeeded; new route ${newRoute.distanceMeters}m / ${newRoute.step.size} steps")
        }
    }
}
