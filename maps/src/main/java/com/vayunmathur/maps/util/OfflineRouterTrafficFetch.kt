package com.vayunmathur.maps.util

import com.vayunmathur.library.log.Log
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.StaticMirror
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Live-traffic fetching + payload decoding for [OfflineRouter].
 *
 * Split out so `OfflineRouter` stays under the function cap: the JNI reverse
 * callback (`fetchTrafficData`) keeps its exact name/signature there and
 * delegates here, and the native updaters are reached through its `internal`
 * members. Same package, so no API changes for any other caller.
 */
internal object OfflineRouterTrafficFetch {
    /** Success status for the traffic fetch. */
    private const val TRAFFIC_HTTP_OK = 200
    /** Bytes of the two-u32 traffic response header. */
    private const val TRAFFIC_HEADER_BYTES = 8
    /** Bytes of one interleaved (u64 id, u8 speed) traffic record. */
    private const val TRAFFIC_RECORD_BYTES = 9L
    /** Mask for one unsigned 32-bit count read out of the payload. */
    private const val U32_MASK = 0xFFFF_FFFFL

    /**
     * Oldest R2 copy of a square still used. HERE flow moves every 1-2 min; the
     * server itself serves a square's match for up to 5 min (stale-while-revalidate).
     */
    private const val TRAFFIC_STATIC_MAX_AGE_MS = 5L * 60 * 1000

    /**
     * The R2 copy of a whole 1° square (`traffic/v1/<lat>_<lon>.bin`, named by its
     * south-west corner, as the server writes it), or null for any other bbox.
     */
    internal fun staticSquareUrl(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): String? {
        val whole = minLat == Math.floor(minLat) && minLon == Math.floor(minLon)
        if (!whole || maxLat != minLat + 1.0 || maxLon != minLon + 1.0) return null
        return "${StaticMirror.BASE}/traffic/v1/${minLat.toInt()}_${minLon.toInt()}.bin"
    }

    fun fetchTrafficData(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
        packedSquare: Int,
        forceAsync: Boolean,
    ) {
        Log.dev(
            "TRAFFIC_DATA",
            "fetchTrafficData START: bbox ($minLat,$minLon)-($maxLat,$maxLon) " +
                "packed=$packedSquare forceAsync=$forceAsync",
        )

        val block: suspend () -> Unit = block@{
            try {
                val payload = fetchTrafficPayload(minLat, minLon, maxLat, maxLon, packedSquare)
                    ?: return@block
                processTrafficPayload(payload, packedSquare)
            } catch (_: Exception) {
                Log.error("TRAFFIC_DATA", "fetchTrafficData ERROR")
                OfflineRouter.finishTrafficFetch(packedSquare)
            }
            Log.dev("TRAFFIC_DATA", "fetchTrafficData END: packed=$packedSquare")
        }

        if (forceAsync) {
            OfflineRouterTraffic.launch(block)
        } else {
            // Previously called runBlocking(Dispatchers.IO) which blocked the
            // native caller's thread (often a Dispatchers.Default worker via
            // getRoute) for an entire 60s HTTP round-trip. That starved the
            // Default pool. Always async; the native side reacts to
            // notifyTrafficUpdated / notifyTrafficFetchFinishedNative when the
            // HTTP response is processed.
            OfflineRouterTraffic.launch(block)
        }
    }

    /** One fetched traffic payload: HTTP status plus raw bytes. */
    private data class TrafficPayload(val status: Int, val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean = super.equals(other)
        override fun hashCode(): Int = super.hashCode()
    }

    private suspend fun fetchTrafficPayload(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
        packedSquare: Int,
    ): TrafficPayload? {
        // The server copies every fresh match of a whole 1° square to R2; read that
        // first and ask the server (which re-matches and re-copies) only when the
        // copy is missing or older than TRAFFIC_STATIC_MAX_AGE_MS.
        val static = staticSquareUrl(minLat, minLon, maxLat, maxLon)
            ?.let { StaticMirror.getStatic(it, TRAFFIC_STATIC_MAX_AGE_MS) }
        val (status, bytes) = if (static != null) {
            static.status to static.bytes
        } else {
            NetworkClient.performRequestBytes(
                url = "https://api.maproj.org/maps/traffic" +
                    "?min_lat=$minLat&min_lon=$minLon&max_lat=$maxLat&max_lon=$maxLon",
            )
        }
        Log.dev(
            "TRAFFIC_DATA",
            "fetchTrafficData NETWORK DONE: status=$status, size=${bytes.size}",
        )
        // Two-level response (little-endian):
        //   u32 n_big, u32 n_component
        //   n_big       x (u64 big_edge_id,  u8 kph)         -- routing, unchanged
        //   n_component x (u64 component_id,  u8 ratio_pct)  -- display
        // ratio_pct = round(speedRatio*100); 0 = no data. Records are interleaved
        // (id then speed), not struct-of-arrays. The big level still feeds
        // updateTrafficNative exactly as before; the component level is kept in
        // Kotlin and pushed to the renderer as an id->colour table.
        if (status != TRAFFIC_HTTP_OK || bytes.size < TRAFFIC_HEADER_BYTES) {
            Log.status("TRAFFIC_DATA", "fetchTrafficData NO DATA: status=$status")
            OfflineRouter.finishTrafficFetch(packedSquare)
            return null
        }
        return TrafficPayload(status, bytes)
    }

    private fun processTrafficPayload(payload: TrafficPayload, packedSquare: Int) {
        val bytes = payload.bytes
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val nBig = buffer.int.toLong() and U32_MASK
        val nComponent = buffer.int.toLong() and U32_MASK
        val expected = TRAFFIC_HEADER_BYTES + TRAFFIC_RECORD_BYTES * (nBig + nComponent)
        if (bytes.size.toLong() != expected) {
            Log.status(
                "TRAFFIC_DATA",
                "fetchTrafficData SIZE MISMATCH: got ${bytes.size}, " +
                    "expected $expected (n_big=$nBig n_component=$nComponent)",
            )
            OfflineRouter.finishTrafficFetch(packedSquare)
            return
        }
        val nBigI = nBig.toInt()
        val nComponentI = nComponent.toInt()

        // Big level: split the interleaved (id, kph) records into the parallel
        // arrays updateTrafficNative expects.
        val edgeIds = LongArray(nBigI)
        val speeds = ByteArray(nBigI)
        for (i in 0 until nBigI) {
            edgeIds[i] = buffer.long
            speeds[i] = buffer.get()
        }

        // Component level: kept for display.
        val compIds = LongArray(nComponentI)
        val compRatios = ByteArray(nComponentI)
        for (i in 0 until nComponentI) {
            compIds[i] = buffer.long
            compRatios[i] = buffer.get()
        }

        Log.dev(
            "TRAFFIC_DATA",
            "fetchTrafficData PROCESSING: $nBigI big edges, $nComponentI components",
        )
        OfflineRouter.updateTraffic(edgeIds, speeds, packedSquare)
        OfflineRouterTraffic.storeSquare(
            packedSquare,
            OfflineRouterTraffic.TrafficComponents(compIds, compRatios),
        )
        OfflineRouterTraffic.notifyTrafficUpdated()
    }
}
