package com.vayunmathur.maps.data
import com.vayunmathur.maps.util.OfflineRouter

import com.vayunmathur.library.log.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Live-traffic display state, extracted from [OfflineRouter] to keep that
 * file under the length limit.
 *
 * The native side owns routing speeds entirely ([OfflineRouter.updateTrafficNative]);
 * this object owns only what the renderer draws: the per-component colour
 * table plus the version counter the prefetch path bumps. [OfflineRouter]
 * keeps the JNI surface (native methods, `fetchTrafficData` reverse
 * callback) — native code resolves those by class and member name — and
 * delegates here for everything pure Kotlin.
 */
internal object OfflineRouterTraffic {
    /**
     * Live per-component traffic for **display**, as a flat id→ratio table.
     *
     * Separate from the big-edge speeds that feed the native updater
     * (routing/ETA, which stay entirely native): these component-level values
     * are pushed to the renderer as an id→colour table by the map layer,
     * which resolves the colour from the theme. [ids] and [ratioPct] are
     * parallel; [ratioPct] is the wire's `u8 = round(speedRatio*100)`, where
     * `0` means "no data" (the consumer skips those). The renderer draws a
     * colour only for ids present here and present in a resident tile, so ids
     * for squares that scrolled off are harmless — which is why this is an
     * accumulating session cache rather than something pruned on every pan
     * (see [trafficComponents]).
     */
    class TrafficComponents(val ids: LongArray, val ratioPct: ByteArray) {
        companion object {
            val EMPTY = TrafficComponents(LongArray(0), ByteArray(0))
        }
    }

    /**
     * Component traffic keyed by the packed 1° square it was fetched for. The
     * native prefetch dedups squares for the whole session, so a square is
     * fetched once and kept: dropping it here would leave a re-panned area
     * permanently uncoloured with no way to refetch. Cleared only on
     * [onReload] (a graph swap can renumber ids).
     */
    private val componentBySquare =
            java.util.concurrent.ConcurrentHashMap<Int, TrafficComponents>()

    /**
     * Last published merge, for the unchanged-content skip in [republish].
     * Volatile (not confined): fetches complete on IO threads, so the
     * read-modify-publish must tolerate races — the worst case is one
     * redundant publish, never a missed one.
     */
    @Volatile
    private var lastPublished: TrafficComponents? = null

    private val _trafficComponents = MutableStateFlow(TrafficComponents.EMPTY)

    /**
     * The merged component table across every fetched square, republished
     * whenever a fetch lands. The map layer collects this, converts each
     * ratio to an ARGB colour against the current palette, and pushes
     * `id→colour` to the renderer.
     */
    val trafficComponents = _trafficComponents.asStateFlow()

    private val _trafficVersion = MutableStateFlow(0)
    val trafficVersion = _trafficVersion.asStateFlow()

    private var trafficUpdateJob: kotlinx.coroutines.Job? = null
    private val trafficScope = CoroutineScope(Dispatchers.IO)

    /** Run [block] on the traffic IO scope (fetch completions live here). */
    fun launch(block: suspend () -> Unit) {
        trafficScope.launch { block() }
    }

    /** Store one square's component table and republish the merge. */
    fun storeSquare(packedSquare: Int, components: TrafficComponents) {
        componentBySquare[packedSquare] = components
        republish()
    }

    /** Concatenate the per-square tables into one flat snapshot and publish it. */
    private fun republish() {
        val squares = componentBySquare.values.toList()
        val total = squares.sumOf { it.ids.size }
        val ids = LongArray(total)
        val ratios = ByteArray(total)
        var off = 0
        for (s in squares) {
            System.arraycopy(s.ids, 0, ids, off, s.ids.size)
            System.arraycopy(s.ratioPct, 0, ratios, off, s.ratioPct.size)
            off += s.ids.size
        }
        // Skip the publish when the merged content is unchanged: every fetch
        // lands here, and a fresh object wakes the trafficComponents
        // collectors (table rebuild + renderer re-push) even when a refetch
        // returned identical bytes. Content-compare is O(n) against the same
        // concat that already ran, far cheaper than the downstream rebuild.
        val last = lastPublished
        if (last != null && last.ids.contentEquals(ids) && last.ratioPct.contentEquals(ratios)) {
            return
        }
        val snapshot = TrafficComponents(ids, ratios)
        lastPublished = snapshot
        _trafficComponents.value = snapshot
    }

    fun notifyTrafficUpdated() {
        trafficUpdateJob?.cancel()
        trafficUpdateJob = trafficScope.launch {
            _trafficVersion.value++
        }
    }

    /** Drops the display cache; called by [OfflineRouter.reload]. */
    fun onReload() {
        componentBySquare.clear()
        lastPublished = null
        _trafficComponents.value = TrafficComponents.EMPTY
    }

    /**
     * Loopback-only tile server for `/traffic/{z}/{x}/{y}`, extracted from
     * [OfflineRouter] to keep that file under the length limit. Started once
     * from [OfflineRouter]'s init; the tile bytes still come from
     * [OfflineRouter.getTrafficTileNative].
     */
    private var serverPort = 0

    /** Socket read timeout (ms) so a half-open client cannot hold a worker forever. */
    private const val CLIENT_SO_TIMEOUT_MS = 5_000
    /** Minimal HTTP request line (`GET /path`). */
    private const val MIN_REQUEST_PARTS = 2
    private const val GET_METHOD = "GET"
    /** Tile path prefix and its three z/x/y segments. */
    private const val TRAFFIC_PREFIX = "/traffic/"
    private const val TILE_PATH_PARTS = 3
    private const val OK_HEADERS =
        "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/vnd.mapbox-vector-tile\r\n" +
            "Content-Encoding: gzip\r\n" +
            "Content-Length: {len}\r\n" +
            "Access-Control-Allow-Origin: *\r\n\r\n"
    private const val NO_CONTENT = "HTTP/1.1 204 No Content\r\n\r\n"

    internal fun startLocalTileServer() {
        Thread {
            try {
                // Bind to loopback ONLY. The previous `ServerSocket(0)` defaulted
                // to 0.0.0.0 which let any app on the device (or anything on the
                // local network) hit /traffic/{z}/{x}/{y}.
                val serverSocket = java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
                serverPort = serverSocket.localPort
                Log.debug("OFFLINE_ROUTER", "Tile server started on port $serverPort (loopback only)")
                // Hand each client to a small pool so a slow tile doesn't block
                // concurrent tile requests behind the global mutex.
                val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
                while (!serverSocket.isClosed) {
                    val client = serverSocket.accept()
                    // Prevent a half-open / hung client from holding a worker forever.
                    runCatching { client.soTimeout = CLIENT_SO_TIMEOUT_MS }
                    pool.execute { handleClient(client) }
                }
            } catch (e: java.io.IOException) {
                Log.error("OFFLINE_ROUTER", "Tile server error", e)
            }
        }.start()
    }

    private fun handleClient(client: java.net.Socket) {
        try {
            serveTile(client)
        } catch (e: java.io.IOException) {
            Log.error("OFFLINE_ROUTER", "Error handling client", e)
        } finally {
            client.close()
        }
    }

    private fun serveTile(client: java.net.Socket) {
        val reader = client.getInputStream().bufferedReader()
        val firstLine = reader.readLine() ?: return

        // Expected: GET /traffic/{z}/{x}/{y} HTTP/1.1
        val parts = firstLine.split(" ")
        if (parts.size < MIN_REQUEST_PARTS || parts[0] != GET_METHOD) return
        val pathParts = parts[1].removePrefix(TRAFFIC_PREFIX).split("/")
        if (pathParts.size != TILE_PATH_PARTS) return
        val z = pathParts[0].toIntOrNull() ?: 0
        val x = pathParts[1].toIntOrNull() ?: 0
        val y = pathParts[2].substringBefore("?").toIntOrNull() ?: 0

        val bytes = OfflineRouter.getTrafficTileNative(z, x, y)
        val output = client.getOutputStream()
        if (bytes != null) {
            output.write(OK_HEADERS.replace("{len}", bytes.size.toString()).toByteArray())
            output.write(bytes)
        } else {
            output.write(NO_CONTENT.toByteArray())
        }
        output.flush()
    }
}
