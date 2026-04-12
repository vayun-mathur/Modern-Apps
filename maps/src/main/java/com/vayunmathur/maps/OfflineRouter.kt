package com.vayunmathur.maps

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import com.vayunmathur.maps.data.SpecificFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.spatialk.geojson.Position
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

object OfflineRouter {
    init {
        System.loadLibrary("offlinerouter")
    }

    private external fun init(basePath: String): Boolean
    private external fun findRouteNative(sLat: Double, sLon: Double, eLat: Double, eLon: Double, mode: Int): Array<RawStep>

    // Simple container for JNI data transfer
    class RawStep @Keep constructor(
        val maneuverId: Int,
        val roadName: String,
        val distanceMm: Long,
        val duration10ms: Long,
        val geometry: DoubleArray // [lon0, lat0, lon1, lat1...]
    )

    private var isInitialized = false

    /**
     * Overload to get route from a SpecificFeature.Route object.
     */
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

    /**
     * Primary routing function using the native A* implementation.
     */
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
            val instructionText = when (maneuver) {
                RouteService.API.Maneuver.DEPART -> "Head toward ${raw.roadName}"
                RouteService.API.Maneuver.STRAIGHT -> "Continue on ${raw.roadName}"
                else -> "Turn ${maneuver.name.replace("TURN_", "").lowercase().replace("_", " ")} onto ${raw.roadName}"
            }

            RouteService.Step(
                distanceMeters = raw.distanceMm / 1000.0,
                staticDuration = (raw.duration10ms * 10).milliseconds,
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