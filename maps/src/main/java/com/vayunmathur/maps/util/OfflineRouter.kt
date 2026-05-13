package com.vayunmathur.maps.util

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.maps.R
import com.vayunmathur.maps.data.SpecificFeature
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.maplibre.spatialk.geojson.Position

object OfflineRouter {
    private var serverPort = 0
    val trafficTileUrl: String get() = if (serverPort > 0) "http://localhost:$serverPort/traffic/{z}/{x}/{y}" else ""

    init {
        System.loadLibrary("offlinerouter")
        startLocalTileServer()
    }

    private fun startLocalTileServer() {
        Thread {
            try {
                val serverSocket = java.net.ServerSocket(0)
                serverPort = serverSocket.localPort
                Log.d("OFFLINE_ROUTER", "Tile server started on port $serverPort")
                while (true) {
                    val client = serverSocket.accept()
                    handleClient(client)
                }
            } catch (e: Exception) {
                Log.e("OFFLINE_ROUTER", "Tile server error", e)
            }
        }.start()
    }

    private fun handleClient(client: java.net.Socket) {
        try {
            val reader = client.getInputStream().bufferedReader()
            val firstLine = reader.readLine() ?: return
            
            // Expected: GET /traffic/{z}/{x}/{y} HTTP/1.1
            val parts = firstLine.split(" ")
            if (parts.size >= 2 && parts[0] == "GET") {
                val pathParts = parts[1].removePrefix("/traffic/").split("/")
                if (pathParts.size == 3) {
                    val z = pathParts[0].toIntOrNull() ?: 0
                    val x = pathParts[1].toIntOrNull() ?: 0
                    val y = pathParts[2].substringBefore("?").toIntOrNull() ?: 0
                    
                    val bytes = getTrafficTileNative(z, x, y)
                    val output = client.getOutputStream()
                    if (bytes != null) {
                        output.write(("HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/vnd.mapbox-vector-tile\r\n" +
                                "Content-Encoding: gzip\r\n" +
                                "Content-Length: ${bytes.size}\r\n" +
                                "Access-Control-Allow-Origin: *\r\n\r\n").toByteArray())
                        output.write(bytes)
                    } else {
                        output.write("HTTP/1.1 204 No Content\r\n\r\n".toByteArray())
                    }
                    output.flush()
                }
            }
        } catch (e: Exception) {
            Log.e("OFFLINE_ROUTER", "Error handling client", e)
        } finally {
            client.close()
        }
    }

    private external fun init(basePath: String): Boolean
    private external fun findRouteNative(
            sLat: Double,
            sLon: Double,
            eLat: Double,
            eLon: Double,
            mode: Int
    ): Array<RawStep>
    private external fun updateTrafficNative(
            zoneId: Int,
            edgeIds: IntArray,
            speeds: ByteArray,
            packedSquare: Int
    )
    private external fun getTrafficSegmentsNative(): DoubleArray
    private external fun notifyTrafficFetchFinishedNative(packedSquare: Int)
    external fun getTrafficTileNative(z: Int, x: Int, y: Int): ByteArray?

    private val _trafficVersion = kotlinx.coroutines.flow.MutableStateFlow(0)
    val trafficVersion = _trafficVersion.asStateFlow()

    private var cacheDirPath: String? = null

    fun notifyTrafficUpdated() {
        _trafficVersion.value++
    }

    private external fun ensureTrafficLoadedNative(lat: Double, lon: Double)

    private val trafficScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)

    @Keep
    private fun fetchTrafficData(
            minLat: Double,
            minLon: Double,
            maxLat: Double,
            maxLon: Double,
            zoneId: Int,
            packedSquare: Int
    ) {
        Log.d(
                "TRAFFIC_DATA",
                "fetchTrafficData START: bbox ($minLat,$minLon)-($maxLat,$maxLon) zone=$zoneId packed=$packedSquare"
        )
        try {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                val (status, bytes) =
                        NetworkClient.performRequestBytes(
                                url =
                                        "https://api.vayunmathur.com/maps/traffic?min_lat=$minLat&min_lon=$minLon&max_lat=$maxLat&max_lon=$maxLon"
                        )
                Log.d(
                        "TRAFFIC_DATA",
                        "fetchTrafficData NETWORK DONE: status=$status, size=${bytes.size}"
                )
                if (status == 200 && bytes.size >= 5) {
                    val n = bytes.size / 5
                    val edgeIds = IntArray(n)
                    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until n) edgeIds[i] = buffer.int
                    val speeds = ByteArray(n)
                    buffer.get(speeds)
                    Log.d("TRAFFIC_DATA", "fetchTrafficData PROCESSING: $n edges")
                    updateTrafficNative(zoneId, edgeIds, speeds, packedSquare)
                    notifyTrafficUpdated()
                } else {
                    Log.w("TRAFFIC_DATA", "fetchTrafficData NO DATA: status=$status")
                    notifyTrafficFetchFinishedNative(packedSquare)
                }
            }
        } catch (e: Exception) {
            Log.e("TRAFFIC_DATA", "fetchTrafficData ERROR", e)
            notifyTrafficFetchFinishedNative(packedSquare)
        }
        Log.d("TRAFFIC_DATA", "fetchTrafficData END: packed=$packedSquare")
    }

    class RawStep
    @Keep
    constructor(
            val maneuverId: Int,
            val roadName: String,
            val distanceMm: Long,
            val duration10ms: Long,
            val geometry: DoubleArray,
            val speedRatio: Double
    )

    private var isInitialized = false

    fun initialize(context: Context) {
        if (isInitialized) return
        val path = context.getExternalFilesDir(null)?.absolutePath ?: return
        isInitialized = init(path)
        cacheDirPath = context.cacheDir.absolutePath
    }

    suspend fun getRoute(
            context: Context,
            route: SpecificFeature.Route,
            userPosition: Position,
            type: RouteService.TravelMode
    ): RouteService.Route =
            withContext(Dispatchers.Default) {
                val start = route.waypoints.first()?.position ?: userPosition
                val end = route.waypoints.last()?.position ?: userPosition
                return@withContext getRoute(context, start, end, type)
            }

    suspend fun getRoute(
            context: Context,
            start: Position,
            end: Position,
            mode: RouteService.TravelMode
    ): RouteService.Route =
            withContext(Dispatchers.Default) {
                if (!isInitialized) {
                    initialize(context)
                }

                val rawSteps =
                        findRouteNative(
                                start.latitude,
                                start.longitude,
                                end.latitude,
                                end.longitude,
                                mode.ordinal
                        )
                                ?: throw IllegalStateException("No route found")

                val fullPolyline = mutableListOf<Position>()
                val processedSteps =
                        rawSteps.map { raw ->
                            val positions = mutableListOf<Position>()
                            for (i in raw.geometry.indices step 2) {
                                val pos = Position(raw.geometry[i], raw.geometry[i + 1])
                                positions.add(pos)
                                if (fullPolyline.isEmpty() || fullPolyline.last() != pos) {
                                    fullPolyline.add(pos)
                                }
                            }

                            val maneuver =
                                    RouteService.API.Maneuver.entries.getOrElse(raw.maneuverId) {
                                        RouteService.API.Maneuver.MANEUVER_UNSPECIFIED
                                    }
                            val hasName = raw.roadName.isNotBlank()
                            val instructionText =
                                    when (maneuver) {
                                        RouteService.API.Maneuver.DEPART ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_depart,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string.maneuver_depart_unnamed
                                                        )
                                        RouteService.API.Maneuver.STRAIGHT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_straight,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string.maneuver_straight_unnamed
                                                        )
                                        RouteService.API.Maneuver.TURN_LEFT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_turn_left,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string.maneuver_turn_left_unnamed
                                                        )
                                        RouteService.API.Maneuver.TURN_RIGHT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_turn_right,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string.maneuver_turn_right_unnamed
                                                        )
                                        RouteService.API.Maneuver.TURN_SLIGHT_LEFT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_turn_slight_left,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string
                                                                        .maneuver_turn_slight_left_unnamed
                                                        )
                                        RouteService.API.Maneuver.TURN_SLIGHT_RIGHT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_turn_slight_right,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string
                                                                        .maneuver_turn_slight_right_unnamed
                                                        )
                                        RouteService.API.Maneuver.TURN_SHARP_LEFT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_turn_sharp_left,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string
                                                                        .maneuver_turn_sharp_left_unnamed
                                                        )
                                        RouteService.API.Maneuver.TURN_SHARP_RIGHT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_turn_sharp_right,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string
                                                                        .maneuver_turn_sharp_right_unnamed
                                                        )
                                        RouteService.API.Maneuver.UTURN_LEFT,
                                        RouteService.API.Maneuver.UTURN_RIGHT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_uturn,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string.maneuver_uturn_unnamed
                                                        )
                                        RouteService.API.Maneuver.MERGE ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_merge,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string.maneuver_merge_unnamed
                                                        )
                                        RouteService.API.Maneuver.RAMP_LEFT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_ramp_left,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string.maneuver_ramp_left_unnamed
                                                        )
                                        RouteService.API.Maneuver.RAMP_RIGHT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_ramp_right,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string.maneuver_ramp_right_unnamed
                                                        )
                                        RouteService.API.Maneuver.FORK_LEFT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_fork_left,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string.maneuver_fork_left_unnamed
                                                        )
                                        RouteService.API.Maneuver.FORK_RIGHT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_fork_right,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string.maneuver_fork_right_unnamed
                                                        )
                                        RouteService.API.Maneuver.ROUNDABOUT_LEFT,
                                        RouteService.API.Maneuver.ROUNDABOUT_RIGHT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_roundabout,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string.maneuver_roundabout_unnamed
                                                        )
                                        else ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_unspecified,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string
                                                                        .maneuver_unspecified_unnamed
                                                        )
                                    }

                            RouteService.Step(
                                    distanceMeters = raw.distanceMm / 1000.0,
                                    staticDuration = (raw.duration10ms / 100.0).seconds,
                                    polyline = positions,
                                    navInstruction =
                                            RouteService.API.NavInstruction(
                                                    maneuver,
                                                    instructionText
                                            ),
                                    travelMode = mode,
                                    speedRatio = raw.speedRatio
                            )
                        }

                RouteService.Route(
                        duration =
                                processedSteps.sumOf { it.staticDuration.inWholeSeconds }.seconds,
                        distanceMeters = processedSteps.sumOf { it.distanceMeters },
                        polyline = fullPolyline,
                        step = processedSteps
                )
            }
}
