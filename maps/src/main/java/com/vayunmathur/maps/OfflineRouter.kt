package com.vayunmathur.maps

import android.content.Context
import android.util.Log
import com.vayunmathur.maps.data.SpecificFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.spatialk.geojson.Position
import java.io.File
import kotlin.time.Duration.Companion.minutes

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
                Log.d(TAG, "  Size: ${size} bytes")
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
    private external fun findShortestRouteNative(sLat: Double, sLon: Double, eLat: Double, eLon: Double): DoubleArray

    private var isInitialized = false

    suspend fun getRoute(context: Context, route: SpecificFeature.Route, userPosition: Position): RouteService.Route = withContext(Dispatchers.Default) {
        val start = route.waypoints.first()?.position ?: userPosition
        val end = route.waypoints.last()?.position ?: userPosition
        return@withContext getRoute(context, start, end)
    }

    suspend fun getRoute(context: Context, start: Position, end: Position): RouteService.Route = withContext(Dispatchers.Default) {
        if (!isInitialized) {
            isInitialized = init(context.getExternalFilesDir(null)!!.absolutePath)
        }

        // Get raw doubles: [lat0, lon0, lat1, lon1...]
        val rawCoords = findShortestRouteNative(start.latitude, start.longitude, end.latitude, end.longitude)

        // Chunk the array by 2 to create Position objects
        val positions = mutableListOf<Position>()
        for (i in rawCoords.indices step 2) {
            positions.add(Position(rawCoords[i], rawCoords[i + 1]))
        }

        RouteService.Route(
            duration = 5.minutes, // You could calculate this in C++ and return as part of the array
            distanceMeters = 100.0,
            polyline = positions,
            step = listOf(RouteService.Step(
                100.0, 5.minutes, positions, RouteService.API.NavInstruction(), RouteService.TravelMode.WALK)
            )
        )
    }
}