package com.vayunmathur.maps.util
import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.maps.R
import com.vayunmathur.maps.data.SpecificFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.spatialk.geojson.Position
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.time.Duration.Companion.seconds

object OfflineRouter {
    init {
        System.loadLibrary("offlinerouter")
    }

    private external fun init(basePath: String): Boolean
    private external fun findRouteNative(sLat: Double, sLon: Double, eLat: Double, eLon: Double, mode: Int): Array<RawStep>
    private external fun updateTrafficNative(zoneId: Int, edgeIds: IntArray, speeds: ByteArray)

    private val trafficScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)

    @Keep
    private fun fetchTrafficData(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, zoneId: Int) {
        Log.d("TRAFFIC_DATA", "fetchTrafficData for bbox ($minLat,$minLon)-($maxLat,$maxLon) zone=$zoneId")
        trafficScope.launch {
            try {
                val (status, bytes) = NetworkClient.performRequestBytes(
                    url = "https://api.vayunmathur.com/maps/traffic?min_lat=$minLat&min_lon=$minLon&max_lat=$maxLat&max_lon=$maxLon"
                )
                if (status == 200 && bytes.size >= 5) {
                    val n = bytes.size / 5
                    val edgeIds = IntArray(n)
                    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until n) edgeIds[i] = buffer.int
                    val speeds = ByteArray(n)
                    buffer.get(speeds)
                    updateTrafficNative(zoneId, edgeIds, speeds)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    class RawStep @Keep constructor(
        val maneuverId: Int,
        val roadName: String,
        val distanceMm: Long,
        val duration10ms: Long,
        val geometry: DoubleArray
    )

    private var isInitialized = false

    suspend fun getRoute(
        context: Context,
        route: SpecificFeature.Route,
        userPosition: Position,
        type: RouteService.TravelMode
    ): RouteService.Route = withContext(Dispatchers.Default) {
        val start = route.waypoints.first()?.position ?: userPosition
        val end = route.waypoints.last()?.position ?: userPosition
        return@withContext getRoute(context, start, end, type)
    }

    suspend fun getRoute(
        context: Context,
        start: Position,
        end: Position,
        mode: RouteService.TravelMode
    ): RouteService.Route = withContext(Dispatchers.Default) {
        if (!isInitialized) {
            isInitialized = init(context.getExternalFilesDir(null)!!.absolutePath)
        }

        val rawSteps = findRouteNative(start.latitude, start.longitude, end.latitude, end.longitude, mode.ordinal)
            ?: throw IllegalStateException("No route found")

        val fullPolyline = mutableListOf<Position>()
        val processedSteps = rawSteps.map { raw ->
            val positions = mutableListOf<Position>()
            for (i in raw.geometry.indices step 2) {
                val pos = Position(raw.geometry[i], raw.geometry[i + 1])
                positions.add(pos)
                if (fullPolyline.isEmpty() || fullPolyline.last() != pos) {
                    fullPolyline.add(pos)
                }
            }

            val maneuver = RouteService.API.Maneuver.entries.getOrElse(raw.maneuverId) { RouteService.API.Maneuver.MANEUVER_UNSPECIFIED }
            val hasName = raw.roadName.isNotBlank()
            val instructionText = when (maneuver) {
                RouteService.API.Maneuver.DEPART -> if (hasName) context.getString(R.string.maneuver_depart, raw.roadName) else context.getString(R.string.maneuver_depart_unnamed)
                RouteService.API.Maneuver.STRAIGHT -> if (hasName) context.getString(R.string.maneuver_straight, raw.roadName) else context.getString(R.string.maneuver_straight_unnamed)
                RouteService.API.Maneuver.TURN_LEFT -> if (hasName) context.getString(R.string.maneuver_turn_left, raw.roadName) else context.getString(R.string.maneuver_turn_left_unnamed)
                RouteService.API.Maneuver.TURN_RIGHT -> if (hasName) context.getString(R.string.maneuver_turn_right, raw.roadName) else context.getString(R.string.maneuver_turn_right_unnamed)
                RouteService.API.Maneuver.TURN_SLIGHT_LEFT -> if (hasName) context.getString(R.string.maneuver_turn_slight_left, raw.roadName) else context.getString(R.string.maneuver_turn_slight_left_unnamed)
                RouteService.API.Maneuver.TURN_SLIGHT_RIGHT -> if (hasName) context.getString(R.string.maneuver_turn_slight_right, raw.roadName) else context.getString(R.string.maneuver_turn_slight_right_unnamed)
                RouteService.API.Maneuver.TURN_SHARP_LEFT -> if (hasName) context.getString(R.string.maneuver_turn_sharp_left, raw.roadName) else context.getString(R.string.maneuver_turn_sharp_left_unnamed)
                RouteService.API.Maneuver.TURN_SHARP_RIGHT -> if (hasName) context.getString(R.string.maneuver_turn_sharp_right, raw.roadName) else context.getString(R.string.maneuver_turn_sharp_right_unnamed)
                RouteService.API.Maneuver.UTURN_LEFT, RouteService.API.Maneuver.UTURN_RIGHT -> if (hasName) context.getString(R.string.maneuver_uturn, raw.roadName) else context.getString(R.string.maneuver_uturn_unnamed)
                RouteService.API.Maneuver.MERGE -> if (hasName) context.getString(R.string.maneuver_merge, raw.roadName) else context.getString(R.string.maneuver_merge_unnamed)
                RouteService.API.Maneuver.RAMP_LEFT -> if (hasName) context.getString(R.string.maneuver_ramp_left, raw.roadName) else context.getString(R.string.maneuver_ramp_left_unnamed)
                RouteService.API.Maneuver.RAMP_RIGHT -> if (hasName) context.getString(R.string.maneuver_ramp_right, raw.roadName) else context.getString(R.string.maneuver_ramp_right_unnamed)
                RouteService.API.Maneuver.FORK_LEFT -> if (hasName) context.getString(R.string.maneuver_fork_left, raw.roadName) else context.getString(R.string.maneuver_fork_left_unnamed)
                RouteService.API.Maneuver.FORK_RIGHT -> if (hasName) context.getString(R.string.maneuver_fork_right, raw.roadName) else context.getString(R.string.maneuver_fork_right_unnamed)
                RouteService.API.Maneuver.ROUNDABOUT_LEFT, RouteService.API.Maneuver.ROUNDABOUT_RIGHT -> if (hasName) context.getString(R.string.maneuver_roundabout, raw.roadName) else context.getString(R.string.maneuver_roundabout_unnamed)
                else -> if (hasName) context.getString(R.string.maneuver_unspecified, raw.roadName) else context.getString(R.string.maneuver_unspecified_unnamed)
            }

            RouteService.Step(
                distanceMeters = raw.distanceMm / 1000.0,
                staticDuration = (raw.duration10ms / 100.0).seconds,
                polyline = positions,
                navInstruction = RouteService.API.NavInstruction(maneuver, instructionText),
                travelMode = mode
            )
        }

        RouteService.Route(
            duration = processedSteps.sumOf { it.staticDuration.inWholeSeconds }.seconds,
            distanceMeters = processedSteps.sumOf { it.distanceMeters },
            polyline = fullPolyline,
            step = processedSteps
        )
    }
}
