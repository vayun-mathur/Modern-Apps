package com.vayunmathur.maps.platform

import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.map.GeoBounds
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.MapGeometry
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.wearlink.WearLinkListener
import com.vayunmathur.library.wearlink.WearLinkNode
import com.vayunmathur.library.wearlink.WearLinkState
import com.vayunmathur.maps.data.MapTileCache
import com.vayunmathur.maps.data.PoiIndex
import com.vayunmathur.maps.data.SavedPlaceStore
import com.vayunmathur.maps.data.inViewport
import com.vayunmathur.maps.domain.WearLineKind
import com.vayunmathur.maps.domain.WearMapSnapshot
import com.vayunmathur.maps.domain.WearMapsProtocol
import com.vayunmathur.maps.domain.WearManeuver
import com.vayunmathur.maps.domain.WearPosition
import com.vayunmathur.maps.domain.WearRawLine
import com.vayunmathur.maps.domain.WearRawPoint
import com.vayunmathur.maps.domain.WearRegionHysteresis
import com.vayunmathur.maps.domain.WearRouteState
import com.vayunmathur.maps.domain.WearSavedPlace
import com.vayunmathur.maps.domain.buildWearMapSnapshot
import com.vayunmathur.maps.domain.buildWearRouteState
import com.vayunmathur.maps.domain.toWearRawLines
import com.vayunmathur.maps.domain.wearMissingKinds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlin.math.cos

/**
 * Phone side of the Maps WearLink companion: the phone supplies the
 * region, the watch renders it (see [WearMapsProtocol]).
 *
 * - Location comes from this phone ([FrameworkLocationManager]); the
 *   watch runs no GPS of its own. Every fix publishes a small
 *   [WearPosition]; a fresh region is extracted only when
 *   [WearRegionHysteresis] says the user nears the snapshot edge.
 * - Region geometry: POIs from the offline index
 *   ([PoiIndex.inViewport]), saved places, basemap road/path/water/
 *   building linework from `:library:map`'s [MapGeometry] query over
 *   the downloaded `.mamaps` archive, and — while navigating — the
 *   route. When there is no readable archive the snapshot names the
 *   unsourced kinds in `degraded` (unavailable, not merely empty)
 *   rather than faking them.
 * - Navigation mirrors [NavigationSessionManager]: Navigating publishes
 *   an active [WearRouteState]; Idle/Arrived/Failed publish the explicit
 *   `active=false` clear exactly once per transition.
 */
object WearMapsSync {
    private const val TAG = "WearMapsSync"

    /**
     * Basemap features per region query. The snapshot's own 48 KB cap
     * degrades further from here; this bounds the native extraction to
     * a few street tiles' worth of linework for a 600 m region.
     */
    private const val GEOMETRY_MAX_FEATURES = 320
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var node: WearLinkNode? = null
    @Volatile
    private var lastLocation: GeoPoint? = null
    @Volatile
    private var lastBearingDeg: Int = -1
    @Volatile
    private var lastCenter: GeoPoint? = null
    @Volatile
    private var lastRoutePublishMs = 0L
    @Volatile
    private var routeWasActive = false

    fun start(context: Context) {
        val appContext = context.applicationContext
        if (node != null) return
        synchronized(this) {
            if (node != null) return
            val listener = object : WearLinkListener {
                override fun onMessage(path: String, body: ByteArray) {
                    if (path == WearMapsProtocol.REQUEST_REGION_PATH) {
                        scope.launch { publishRegion(appContext, force = true) }
                    }
                }
            }
            val created = WearLinkNode(appContext, WearMapsProtocol.CAPABILITY, listener)
            node = created
            scope.launch {
                var wasConnected = false
                created.state.collect { linkState ->
                    val connected = linkState is WearLinkState.Connected
                    if (connected && !wasConnected) {
                        publishRegion(appContext, force = true)
                        publishRoute(appContext, force = true)
                    }
                    wasConnected = connected
                }
            }
            created.start()
            startLocation(appContext)
            startNavigationMirror(appContext)
        }
    }

    private fun startLocation(appContext: Context) {
        val manager = FrameworkLocationManager(appContext)
        // Process-lifetime companion feed: deliberately never stopped while
        // this process lives (the sync itself is app-lifecycle-bound).
        manager.startUpdates(onUpdateReceived = { point, heading, _ ->
            lastLocation = point
            if (heading != null) lastBearingDeg = heading.toInt()
            scope.launch {
                publishPosition(appContext, point)
                publishRegion(appContext, force = false)
            }
        })
    }

    private suspend fun publishPosition(appContext: Context, point: GeoPoint) {
        val position = WearPosition(
            latE7 = (point.latitude * 1e7).toInt(),
            lonE7 = (point.longitude * 1e7).toInt(),
            bearingDeg = lastBearingDeg,
            atMillis = System.currentTimeMillis(),
        )
        node?.putData(
            WearMapsProtocol.POSITION_PATH,
            WearMapsProtocol.json.encodeToString(position).encodeToByteArray(),
        )
    }

    /** Extract + publish the region around the last fix; [force] bypasses hysteresis. */
    suspend fun publishRegion(appContext: Context, force: Boolean) {
        val user = lastLocation ?: return
        val center = lastCenter
        if (!force && center != null &&
            !WearRegionHysteresis.shouldRepublish(
                WearRegionHysteresis.distanceM(center, user),
                WearMapsProtocol.REGION_RADIUS_M,
            )
        ) return
        val snapshot = extractRegion(appContext, user)
        val sent = node?.putData(
            WearMapsProtocol.REGION_PATH,
            WearMapsProtocol.json.encodeToString(snapshot).encodeToByteArray(),
        ) ?: false
        if (sent) {
            lastCenter = user
        } else {
            Log.status(TAG, "region not delivered (no live WearLink session)")
        }
    }

    /**
     * Region extraction: offline POIs + saved places, basemap geometry
     * from [MapGeometry] over the downloaded archive, plus the route
     * line while navigating. Runs on the IO scope: the geometry query
     * does file I/O and never touches the render path.
     */
    private fun extractRegion(appContext: Context, center: GeoPoint): WearMapSnapshot {
        val radius = WearMapsProtocol.REGION_RADIUS_M.toDouble()
        val dLat = radius / 110_540.0
        val dLon = radius / (111_320.0 * cos(Math.toRadians(center.latitude)).coerceAtLeast(0.05))
        val bounds = GeoBounds(
            west = center.longitude - dLon, south = center.latitude - dLat,
            east = center.longitude + dLon, north = center.latitude + dLat,
        )
        // No planet downloaded yet (or a partially-written file) reads
        // as a null path here and as unsourced kinds in the result —
        // never as an empty-but-complete map.
        val archiveFile = appContext.getExternalFilesDir(null)
            ?.let { File(it, MapTileCache.BASEMAP_ARCHIVE_FILE) }
            ?.takeIf { it.isFile }
        val geometry = MapGeometry.query(
            archivePath = archiveFile?.absolutePath,
            bounds = bounds,
            maxFeatures = GEOMETRY_MAX_FEATURES,
        )
        val points = runCatching {
            PoiIndex.initialize(appContext)
            PoiIndex.inViewport(
                west = center.longitude - dLon, south = center.latitude - dLat,
                east = center.longitude + dLon, north = center.latitude + dLat,
                cap = 300,
            ).map { WearRawPoint(it.type, it.name, GeoPoint(it.lon, it.lat)) }
        }.getOrDefault(emptyList())
        val savedStore = SavedPlaceStore(DataStoreUtils.getInstance(appContext))
        val saved = (savedStore.savedInitial() + savedStore.listsInitial().values.flatten())
            .distinctBy { it.name to it.lat to it.lon }
            .map {
                WearSavedPlace(it.name, (it.lat * 1e7).toInt(), (it.lon * 1e7).toInt())
            }
        val route = NavigationSessionManager.session.value.route
        val routeLine = if (route != null && route.polyline.size >= 2) {
            listOf(WearRawLine(WearLineKind.ROUTE, route.polyline))
        } else {
            emptyList()
        }
        return buildWearMapSnapshot(
            center = center,
            generatedAtMillis = System.currentTimeMillis(),
            rawLines = routeLine + geometry.toWearRawLines(),
            rawPoints = points,
            saved = saved.take(20),
            userBearingDeg = lastBearingDeg,
            // degraded = unavailable only: kinds the archive was read
            // for but which simply have no features here are not named.
            missingKinds = geometry.wearMissingKinds(),
            sourceTruncated = geometry.truncated,
        )
    }

    private fun startNavigationMirror(appContext: Context) {
        scope.launch {
            NavigationSessionManager.state.collectLatest {
                publishRoute(appContext, force = false)
            }
        }
        scope.launch {
            NavigationSessionManager.session.collectLatest {
                publishRoute(appContext, force = true)
            }
        }
    }

    /** Publish the route overlay, or its explicit clear when not navigating. */
    suspend fun publishRoute(appContext: Context, force: Boolean) {
        val navState = NavigationSessionManager.state.value
        val session = NavigationSessionManager.session.value
        val navigating = navState as? NavigationSessionManager.NavState.Navigating
        val route = session.route
        val now = System.currentTimeMillis()
        if (navigating == null || route == null) {
            if (routeWasActive || force && navState is NavigationSessionManager.NavState.Idle) {
                routeWasActive = false
                node?.putData(
                    WearMapsProtocol.ROUTE_PATH,
                    WearMapsProtocol.json.encodeToString(
                        WearRouteState(active = false, generatedAtMillis = now),
                    ).encodeToByteArray(),
                )
            }
            return
        }
        if (!force && now - lastRoutePublishMs < 5_000L) return
        lastRoutePublishMs = now
        routeWasActive = true
        val progress = navigating.progress
        var cumulative = 0.0
        val maneuvers = route.step.map { step ->
            WearManeuver(
                text = step.navInstruction.instructions,
                kind = step.navInstruction.maneuver.name,
                distanceFromStartM = cumulative.toInt(),
            ).also { cumulative += step.distanceMeters }
        }
        val nextText = route.step.getOrNull(progress.currentStepIndex)
            ?.navInstruction?.instructions.orEmpty()
        val state = buildWearRouteState(
            destinationName = session.destinationName.orEmpty(),
            polyline = route.polyline,
            maneuvers = maneuvers,
            nextText = nextText,
            distanceToNextM = progress.distanceToNextManeuver.toInt(),
            distanceRemainingM = progress.distanceRemaining.toInt(),
            etaEpochMs = progress.etaEpochMs,
            generatedAtMillis = now,
        )
        node?.putData(
            WearMapsProtocol.ROUTE_PATH,
            WearMapsProtocol.json.encodeToString(state).encodeToByteArray(),
        )
        // Route geometry belongs in the region too; refresh it alongside.
        publishRegion(appContext, force = true)
    }
}
