package com.vayunmathur.weather.network

import android.content.Context
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.weather.domain.wx.WxCompute
import com.vayunmathur.weather.domain.wx.WxGrid
import com.vayunmathur.weather.domain.wx.WxTiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Weather from static files on Cloudflare R2 (bucket `static`,
 * `https://static.maproj.org/wx/v1/`,
 * written by the server's `wx export`): `latest.json` names the current run,
 * one `WXT1` tile per ~1°×1° block holds every model series a bundle needs,
 * and a static `WXG1` geo block gives the point's elevation and time zone.
 * The bundle is computed on device ([WxCompute]), identical to the server's.
 *
 * Traffic: `latest.json` is re-read only after its `next_check` (the next
 * model run, ~6 h), so an hourly refresh is usually zero requests; a new run
 * costs one tile GET per location tile (+ one finished-day history object a
 * day); geo blocks are fetched once ever.
 * Everything is cached on disk by run id. Any failure throws and
 * [WeatherApi.bundle] falls back to the server API, so the rollout is safe.
 */
object WxStatic {
    private const val BASE = "https://static.maproj.org/wx/v1/"
    private const val HTTP_OK = 200
    private const val HTTP_FORBIDDEN = 403 // R2 answers a missing public key with 404 or 403
    private const val HTTP_NOT_FOUND = 404
    private const val MS_PER_S = 1000L

    /** Don't re-poll `latest.json` more often than this after `next_check`. */
    private const val POLL_MIN_SECS = 15 * 60L

    /** Re-poll even before `next_check` once the manifest is this old. */
    private const val MANIFEST_MAX_AGE_SECS = 12 * 3600L

    @androidx.annotation.VisibleForTesting
    internal var baseOverride: String? = null

    /** Kill switch (tests, or a remote flag later). */
    @Volatile
    var enabled: Boolean = true

    /** Test seam: object fetch (url → status + bytes). */
    @androidx.annotation.VisibleForTesting
    internal var fetcher: suspend (String) -> Pair<Int, ByteArray> = { url -> NetworkClient.performRequestBytes(url) }

    private val base get() = baseOverride ?: BASE
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    @Volatile
    private var dir: File? = null
    private var manifest: Pair<Manifest, Long>? = null
    private val tiles = HashMap<String, WxTiles.Tile?>()
    private val geos = HashMap<String, WxTiles.GeoBlock>()
    private val hists = HashMap<String, WxTiles.Tile>()

    @Serializable
    internal data class Grid(val ny: Int, val nx: Int, val cells: Int)

    /** Per-day history objects `hist/<day>/<ty>/<tx>.wxt` for days `[fromDay, toDay)`. */
    @Serializable
    internal data class Hist(
        val base: String,
        @SerialName("from_day") val fromDay: Long,
        @SerialName("to_day") val toDay: Long,
    )

    @Serializable
    internal data class Manifest(
        val v: Int,
        val run: String,
        val base: String,
        @SerialName("next_check") val nextCheck: Long,
        val grid: Grid,
        val hist: Hist,
        val geo: String = "geo/v1/",
    )

    /** This point/run cannot be served from R2 (ocean, missing object, clock skew): use the API. */
    class NotCovered(msg: String, cause: Throwable? = null) : Exception(msg, cause)

    /** Disk cache under the app's cache dir (call from activity + worker). */
    fun init(context: Context) {
        if (dir == null) dir = File(context.applicationContext.cacheDir, "wx-static").apply { mkdirs() }
    }

    private suspend fun get(path: String): ByteArray? {
        val (status, bytes) = fetcher(base + path)
        return when (status) {
            HTTP_OK -> bytes
            HTTP_FORBIDDEN, HTTP_NOT_FOUND -> null
            else -> throw java.io.IOException("wx static $path: HTTP $status")
        }
    }

    private fun file(name: String): File? = dir?.let { File(it, name) }

    private fun writeAtomic(f: File?, bytes: ByteArray) {
        if (f == null) return
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(f)
    }

    // Offline with a cached manifest: the IOException is the expected signal
    // to keep serving the cached run, not an error to propagate.
    @Suppress("SwallowedException", "ThrowsCount")
    private suspend fun currentManifest(now: Long): Manifest {
        val cached = manifest ?: file("latest.json")?.takeIf { it.exists() }?.let { f ->
            runCatching { json.decodeFromString<Manifest>(f.readText()) to f.lastModified() / MS_PER_S }.getOrNull()
        }
        if (cached != null) {
            val (m, fetchedAt) = cached
            val due = now >= m.nextCheck || now - fetchedAt >= MANIFEST_MAX_AGE_SECS
            if (!due || now - fetchedAt < POLL_MIN_SECS) {
                manifest = cached
                return m
            }
        }
        val fresh = try {
            get("latest.json") ?: throw NotCovered("no latest.json")
        } catch (e: java.io.IOException) {
            // Offline: keep using the cached run (its tiles stay valid).
            cached?.let { return it.first } ?: throw e
        }
        val m = json.decodeFromString<Manifest>(fresh.decodeToString())
        if (m.v != 1) throw NotCovered("manifest v${m.v}")
        writeAtomic(file("latest.json"), fresh)
        if (cached != null && cached.first.run != m.run) {
            // A new run: older tiles are dead weight.
            file("t")?.deleteRecursively()
            tiles.clear()
        }
        manifest = m to now
        return m
    }

    @Suppress("ThrowsCount")
    private suspend fun tile(m: Manifest, ty: Int, tx: Int): WxTiles.Tile {
        val key = "${m.run}/$ty/$tx"
        if (tiles.containsKey(key)) return tiles[key] ?: throw NotCovered("no tile $key")
        val f = file("t/${m.run}_${ty}_$tx.wxt")
        val miss = file("t/${m.run}_${ty}_$tx.none")
        if (miss?.exists() == true) {
            tiles[key] = null
            throw NotCovered("no tile $key")
        }
        val bytes = f?.takeIf { it.exists() }?.readBytes() ?: get("${m.base}$ty/$tx.wxt")?.also { writeAtomic(f, it) }
        if (bytes == null) {
            // Ocean / not exported: remember for this run.
            writeAtomic(miss, ByteArray(0))
            tiles[key] = null
            throw NotCovered("no tile $key")
        }
        return WxTiles.decodeTile(bytes).also { tiles[key] = it }
    }

    /**
     * The finished days before the run tile (immutable: cached for good,
     * days older than any window dropped). Usually all cached: a new day
     * object appears once a day.
     */
    private suspend fun history(m: Manifest, ty: Int, tx: Int, now: Long): List<WxTiles.Tile> {
        val days = WxGrid.histDaysNeeded(now, m.hist.toDay * 24)
        if (!days.isEmpty() && days.first < m.hist.fromDay) throw NotCovered("history before ${m.hist.fromDay}")
        val out = days.map { d ->
            val key = "$d/$ty/$tx"
            hists[key] ?: run {
                val f = file("h/${d}_${ty}_$tx.wxt")
                val bytes = f?.takeIf { it.exists() }?.readBytes()
                    ?: get("${m.hist.base}$key.wxt")?.also { writeAtomic(f, it) }
                    ?: throw NotCovered("no history $key")
                WxTiles.decodeTile(bytes).also { hists[key] = it }
            }
        }
        if (!days.isEmpty()) {
            val oldest = days.first - 1
            hists.keys.removeAll { it.substringBefore('/').toLong() < oldest }
            file("h")?.listFiles()?.forEach { f ->
                val day = f.name.substringBefore('_').toLongOrNull()
                if (day != null && day < oldest) f.delete()
            }
        }
        return out
    }

    private suspend fun geo(m: Manifest, k: WxGrid.GeoKey): WxTiles.GeoBlock {
        val key = "${k.x}/${k.y}/${k.b}"
        geos[key]?.let { return it }
        val f = file("g/${k.x}_${k.y}_${k.b}.wxg")
        val bytes = f?.takeIf { it.exists() }?.readBytes()
            ?: get("${m.geo}$key.wxg")?.also { writeAtomic(f, it) }
            ?: throw NotCovered("no geo block $key")
        return WxTiles.decodeGeo(bytes).also { geos[key] = it }
    }

    /**
     * The full bundle for (lat, lon) at [nowEpoch] from R2. Throws on any
     * failure ([NotCovered] for points without a tile, e.g. open ocean).
     */
    @Suppress("ThrowsCount")
    suspend fun bundle(lat: Double, lon: Double, nowEpoch: Long = System.currentTimeMillis() / MS_PER_S): WxBundle =
        withContext(Dispatchers.IO) {
            if (!enabled) throw NotCovered("disabled")
            val (t, hist, g) = lock.withLock {
                val m = currentManifest(nowEpoch)
                val (ty, tx) = WxGrid.tileOf(lat, lon, m.grid.ny, m.grid.nx, m.grid.cells)
                val k = WxGrid.geoOf(lat, lon) ?: throw NotCovered("off the DEM grid")
                Triple(tile(m, ty, tx), history(m, ty, tx, nowEpoch), geo(m, k))
            }
            val s = try {
                WxGrid.seriesFromTile(t, hist, g, lat, lon, nowEpoch)
            } catch (e: WxGrid.Unavailable) {
                throw NotCovered(e.message ?: "unavailable", e)
            }
            WxCompute.computeBundle(lat, lon, nowEpoch, t.iconRunIso, s)
        }

    @androidx.annotation.VisibleForTesting
    internal fun resetForTest() {
        manifest = null
        tiles.clear()
        geos.clear()
        hists.clear()
        dir = null
    }
}
