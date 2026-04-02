package com.vayunmathur.maps

import android.content.Context
import android.util.Log
import com.vayunmathur.maps.data.SpecificFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.spatialk.geojson.Position
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

object OfflineRouter {
    private const val TAG = "OfflineRouter"

    init {
        System.loadLibrary("offlinerouter")
    }

    private external fun init(basePath: String): Boolean
    private external fun findRouteNative(sLat: Double, sLon: Double, eLat: Double, eLon: Double, mode: Int): Array<RawStep>

    // Simple container for JNI data transfer
    class RawStep(
        val maneuverId: Int,
        val roadName: String,
        val distanceMm: Long,
        val duration10ms: Long,
        val geometry: DoubleArray // [lon0, lat0, lon1, lat1...]
    )

    private var isInitialized = false

    /**
     * Performs an integrity check on the binary navigation stack files.
     */
    fun checkFiles(context: Context) {
        val dir = context.getExternalFilesDir(null)!!
        val files = listOf(
            "nodes_master.bin" to 13,   // 13 bytes per node (40-bit ptr)
            "nodes_spatial.bin" to 12,  // 12 bytes per node
            "edges.bin" to 13           // 13 bytes per edge
        )

        Log.d(TAG, "--- Binary Stack Health Check ---")

        files.forEach { (name, structSize) ->
            val file = File(dir, name)
            if (file.exists()) {
                val size = file.length()
                // nodes_master has a 5-byte sentinel at the end
                val count = if (name == "nodes_master.bin") (size - 5) / structSize else size / structSize

                Log.d(TAG, "File: $name")
                Log.d(TAG, "  Size: $size bytes")
                Log.d(TAG, "  Implied Count: $count items")

                // Integrity check: file size should be a multiple of the struct size (accounting for sentinel)
                val checkSize = if (name == "nodes_master.bin") size - 5 else size
                if (checkSize % structSize != 0L) {
                    Log.e(TAG, "  WARNING: File size is not a multiple of $structSize. File may be corrupt.")
                }
            } else {
                Log.e(TAG, "File: $name - NOT FOUND at ${file.absolutePath}")
            }
        }
    }

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