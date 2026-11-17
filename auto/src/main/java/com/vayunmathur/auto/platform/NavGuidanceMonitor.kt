package com.vayunmathur.auto.platform

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.vayunmathur.library.log.Log
import com.vayunmathur.auto.protocol.MapsGuidance
import com.vayunmathur.auto.protocol.MapRoutePoint
import com.vayunmathur.auto.protocol.NavSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * One active route overlaid on the mirror, from whoever owns routing.
 *
 * Own data class rather than the maps module's `RouteService.Route`: the
 * auto module must not take a compile dependency on the maps app module
 * (same rule as `MediaPlaybackMonitor`'s string class name for music), and
 * the two APKs do not share a process singleton anyway. Points are
 * [MapRoutePoint] so the monitor can forward them without reshaping.
 *
 * The host path (`CarAppHost`) carries the real route inside the app; this
 * provider feeds only the pre-host banner fallback plus the ch7/ch10 wire
 * values, which still come from phone GPS. The provider must return a stable
 * list instance while the route is unchanged: the monitor forwards the
 * reference untouched. A provider that rebuilds the list per read would
 * churn every GPS fix for nothing.
 */
data class ActiveRoute(
    val points: List<MapRoutePoint>,
    val nextRoad: String? = null,
    val nextTurnDistanceM: Int? = null,
    val maneuver: String? = null,
)

/** Supplies the active route, or null when idle or unknown. */
fun interface RouteProvider {
    fun current(): ActiveRoute?
}

/**
 * Binds phone GPS (plus night state) into [NavSnapshot]s for the pre-host
 * nav banner fallback and the ch7/ch10 live values.
 *
 * Owns location collection the way `MediaPlaybackMonitor` owns its
 * controller: `start()` subscribes on the main thread, fixes arrive on the
 * location listener, and every fix republishes through [snapshots] with the
 * latest route overlay. Sinks must be thread-safe (a `StateFlow` set, a
 * main-posted view update), never work inline.
 *
 * Without `ACCESS_FINE_LOCATION` the monitor seeds from the last-known fix
 * and stays there: a `SecurityException` degrades to last-known rather than
 * failing anything downstream. Route guidance stays idle until a
 * [RouteProvider] is set -- position and puck are live from the first fix,
 * turn fields join when routing does.
 *
 * Seams, all future wiring, none of it here:
 * - ch7: fold `MapsGuidance.toSensorEvents(snapshot)` through
 *   `SensorChannel`'s `onValues` seam (sensors-dev owns the channel).
 * - ch10: forward `MapsGuidance.toNavStatus(snapshot)` to
 *   `NavStatusChannel.postStatus` (sensors-dev owns the channel).
 * - nav banner: `CarNavCardView.update(snapshot)` until `CarAppHost`
 *   connects, then the hosted `NavigationTemplate` takes over.
 */
class NavGuidanceMonitor(
    private val context: Context,
    private val onUpdate: (NavSnapshot) -> Unit = {},
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _snapshots = MutableStateFlow<NavSnapshot?>(null)
    /** Latest snapshot; null until the first fix or last-known seed. */
    val snapshots: StateFlow<NavSnapshot?> = _snapshots.asStateFlow()

    /** Overlays route guidance onto each fix; null means idle map. */
    @Volatile var routeProvider: RouteProvider? = null

    private var locationManager: LocationManager? = null
    private var listener: LocationListener? = null

    /**
     * Subscribes to GPS + network fixes. Safe from any thread; the subscribe
     * hops to main. Seeds from the last-known fix so observers have a
     * position before the next GPS tick.
     */
    fun start() {
        mainHandler.post {
            val manager = context.getSystemService(LocationManager::class.java) ?: run {
                Log.status(TAG, "no location manager; guidance stays empty")
                return@post
            }
            locationManager = manager
            val active = object : LocationListener {
                override fun onLocationChanged(location: Location) = publish(location)

                @Deprecated(
                    "Required by the old LocationListener interface; " +
                        "status callbacks are no longer delivered on API 29+.",
                )
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            listener = active
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.ACCESS_FINE_LOCATION,
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                Log.status(TAG, "no location permission; guidance holds last-known")
                return@post
            }
            runCatching {
                if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, FIX_MIN_MS, 0f, active)
                }
                if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, FIX_MIN_MS, 0f, active)
                }
                val last = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                last?.let { publish(it) }
            }.onFailure {
                // No permission (or no provider): last-known stays, live fixes
                // wait for the permission UX in MainActivity/Navigation/Route.
                Log.status(TAG, "location unavailable; guidance holds last-known", it)
            }
        }
    }

    /** Drops the subscription. Safe from any thread. */
    fun stop() {
        mainHandler.post {
            listener?.let { runCatching { locationManager?.removeUpdates(it) } }
            listener = null
            locationManager = null
        }
    }

    /**
     * Folds one fix plus the current route overlay into a snapshot.
     *
     * Parked is a motion heuristic (no speed, or below walking pace), not a
     * gear reading: the head unit's DRIVING_STATUS stream wins downstream
     * wherever the snapshot is folded into one. Bearing trusts GPS course
     * only above [GPS_COURSE_MIN_SPEED_MPS], matching the maps stack's own
     * rule (`NavigationSessionManager`), and is null otherwise -- null draws
     * the dot without a cone, which is different from pointing it north.
     */
    private fun publish(location: Location) {
        val speed = if (location.hasSpeed()) location.speed.toDouble() else 0.0
        val course = if (location.hasBearing() && location.hasSpeed() &&
            location.speed >= GPS_COURSE_MIN_SPEED_MPS
        ) {
            location.bearing
        } else {
            null
        }
        val route = runCatching { routeProvider?.current() }.getOrNull()
        val snapshot = MapsGuidance.snapshotFromFix(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyM = if (location.hasAccuracy()) location.accuracy.roundToInt() else null,
            fixTimestampMs = location.time,
            speedMps = speed,
            parked = speed < PARKED_MAX_SPEED_MPS,
            isNight = isNightNow(),
        ).copy(
            guidanceActive = route != null,
            nextRoad = route?.nextRoad,
            nextTurnDistanceM = route?.nextTurnDistanceM,
            maneuver = route?.maneuver,
            bearingDeg = course,
            route = route?.points,
        )
        _snapshots.value = snapshot
        onUpdate(snapshot)
    }

    /**
     * The phone's own night state.
     *
     * Duplicates the `ProjectionService.isNightNow` read on purpose: that
     * file is another agent's in-flight work and this must not touch it.
     * Whoever wires the monitor passes this in as the `SensorChannel`
     * `NightSource` so the two agree; dedup when the tree settles.
     */
    private fun isNightNow(): Boolean? = runCatching {
        // Car-dock UiMode first (a phone in a car holder at night reports car
        // mode), then the system night flag. Null only when both are unknown.
        val uiMode = context.getSystemService(UiModeManager::class.java)?.nightMode
            ?: (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        when (uiMode) {
            UiModeManager.MODE_NIGHT_YES -> true
            UiModeManager.MODE_NIGHT_NO -> false
            else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
    }.getOrNull()

    private companion object {
        const val TAG = "MaAuto.Guidance"
        const val FIX_MIN_MS = 1_000L

        /** Below a slow walk the vehicle counts as parked (heuristic, see [publish]). */
        const val PARKED_MAX_SPEED_MPS = 0.5

        /** GPS course is trusted for bearing only above this (maps-stack rule). */
        const val GPS_COURSE_MIN_SPEED_MPS = 1.0f
    }
}
