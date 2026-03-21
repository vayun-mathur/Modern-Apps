package com.vayunmathur.maps

import android.content.Context
import android.util.Log
import com.vayunmathur.maps.data.SpecificFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.spatialk.geojson.Position
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.seconds

object OfflineRouter {
    init {
        System.loadLibrary("offlinerouter")
    }

    private const val TAG = "OfflineRouter"

    fun checkFiles(context: Context) {
        val dir = context.getExternalFilesDir(null)!!
        val files = listOf(
            "nodes_lookup.bin" to 8,    // 8 bytes per node
            "nodes_spatial.bin" to 12,  // 12 bytes per node
            "edge_index.bin" to 8,     // 8 bytes per node (+1 sentinel)
            "edges.bin" to 13          // 13 bytes per edge
        )

        Log.d(TAG, "--- Binary Stack Health Check ---")

        files.forEach { (name, structSize) ->
            val file = File(dir, name)
            if (file.exists()) {
                val size = file.length()
                val count = if (name == "edge_index.bin") (size / structSize) - 1 else size / structSize

                Log.d(TAG, "File: $name")
                Log.d(TAG, "  Size: $size bytes")
                Log.d(TAG, "  Implied Count: $count items")

                // Integrity check: file size should be a multiple of the struct size
                if (name != "edge_index.bin" && size % structSize != 0L) {
                    Log.e(TAG, "  WARNING: File size is not a multiple of $structSize. File may be corrupt.")
                }
            } else {
                Log.e(TAG, "File: $name - NOT FOUND at ${file.absolutePath}")
            }
        }
    }

    private external fun init(basePath: String): Boolean
    private external fun findShortestRouteNative(sLat: Double, sLon: Double, eLat: Double, eLon: Double, mode: Int): DoubleArray

    private var isInitialized = false

    suspend fun getRoute(context: Context, route: SpecificFeature.Route, userPosition: Position, type: RouteService.TravelMode): RouteService.Route = withContext(Dispatchers.Default) {
        val start = route.waypoints.first()?.position ?: userPosition
        val end = route.waypoints.last()?.position ?: userPosition
        return@withContext getRoute(context, start, end, type)
    }

    suspend fun getRoute(context: Context, start: Position, end: Position, type: RouteService.TravelMode): RouteService.Route = withContext(Dispatchers.Default) {
        if (!isInitialized) {
            isInitialized = init(context.getExternalFilesDir(null)!!.absolutePath)
        }

        // Get raw doubles: [total_time (s), lat0, lon0, lat1, lon1...]
        val rawData = findShortestRouteNative(start.latitude, start.longitude, end.latitude, end.longitude, type.ordinal)
        val (time, rawCoords) = rawData.first() to rawData.drop(1)

        // Chunk the array by 2 to create Position objects
        val positions = mutableListOf<Position>()
        for (i in rawCoords.indices step 2) {
            positions.add(Position(rawCoords[i], rawCoords[i + 1]))
        }

        val distance = positions.zipWithNext().sumOf {
            haversine(it.first, it.second)
        }

        RouteService.Route(
            duration = time.seconds,
            distanceMeters = distance,
            polyline = positions,
            step = listOf(RouteService.Step(
                distance, time.seconds, positions, RouteService.API.NavInstruction(), RouteService.TravelMode.WALK)
            )
        )
    }

    fun haversine(pos1: Position, pos2: Position): Double {
        val r = 6371_000.0

        val lat1 = Math.toRadians(pos1.latitude)
        val lon1 = Math.toRadians(pos1.longitude)
        val lat2 = Math.toRadians(pos2.latitude)
        val lon2 = Math.toRadians(pos2.longitude)

        val dLat = lat2 - lat1
        val dLon = lon2 - lon1

        // The Haversine formula
        val a = sin(dLat / 2).pow(2.0) +
                cos(lat1) * cos(lat2) *
                sin(dLon / 2).pow(2.0)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return r * c
    }
}