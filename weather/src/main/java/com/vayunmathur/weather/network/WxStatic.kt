package com.vayunmathur.weather.network

import android.content.Context
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.weather.domain.wx.WxCompute
import com.vayunmathur.weather.domain.wx.WxGrid
import com.vayunmathur.weather.domain.wx.WxPack
import com.vayunmathur.weather.domain.wx.WxTiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Weather from static packed files on Cloudflare R2 (bucket `static`,
 * `https://static.maproj.org/wx/v1/`, written by the server's `wx export`),
 * read with HTTP Range requests; the bundle is computed on device
 * ([WxCompute]), identical to the server's.
 *
 * Requests per location: before the entry's `next_check` (the next model
 * run, ~6 h) none — the bundle is recomputed locally for the new hour; then
 * one ranged GET of the tile's 120-byte entry in `run.wxp` (run id,
 * next_check, offsets), and only if the run changed one ranged GET of the
 * tile (If-Match the entry's ETag, so a run replaced in between is caught)
 * plus one per new finished day (`hist/<day>.wxp`). The location's geo block
 * (elevation + time zone) is fetched once ever. Everything is cached on
 * disk. Any failure throws and [WeatherApi.bundle] falls back to the server
 * API, so the rollout is safe.
 */
object WxStatic {
    private const val BASE = "https://static.maproj.org/wx/v1/"
    private const val RUN_KEY = "run.wxp"
    private const val GEO_KEY = "geo.wxp"
    private const val HTTP_PARTIAL = 206
    private const val HTTP_PRECONDITION_FAILED = 412
    private const val HTTP_FORBIDDEN = 403 // R2 answers a missing public key with 404 or 403
    private const val HTTP_NOT_FOUND = 404
    private const val MS_PER_S = 1000L

    /** Don't re-check an entry more often than this after `next_check`. */
    private const val POLL_MIN_SECS = 15 * 60L

    /** Re-check even before `next_check` once the entry is this old. */
    private const val ENTRY_MAX_AGE_SECS = 12 * 3600L

    /** Absent tiles (open ocean) are re-checked this often. */
    private const val ABSENT_RECHECK_SECS = 6 * 3600L

    @androidx.annotation.VisibleForTesting
    internal var baseOverride: String? = null

    /** Kill switch (tests, or a remote flag later). */
    @Volatile
    var enabled: Boolean = true

    /** HTTP response: status, headers (lower-case names), body. */
    class Resp(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    /** Test seam: one GET with request headers. */
    @androidx.annotation.VisibleForTesting
    internal var fetcher: suspend (String, Map<String, String>) -> Resp = { url, headers ->
        val (status, h, body) = NetworkClient.performRequestBytesFull(url, headers = headers)
        Resp(status, h.entries.associate { (k, v) -> k.lowercase() to v.joinToString(",") }, body)
    }

    private val base get() = baseOverride ?: BASE
    private val lock = Mutex()

    @Volatile
    private var dir: File? = null

    private class Entry(val e: WxPack.RunEntry, val raw: ByteArray, val etag: String, val fetchedAt: Long)

    private val entries = HashMap<String, Entry>()
    private val tiles = HashMap<String, WxTiles.Tile>()
    private val hists = HashMap<String, WxTiles.Tile>()
    private val geos = HashMap<String, WxTiles.GeoBlock>()
    private var geoHeader: WxPack.Header? = null

    /** This point/run cannot be served from R2 (ocean, missing object, clock skew): use the API. */
    class NotCovered(msg: String, cause: Throwable? = null) : Exception(msg, cause)

    /** A ranged read found the run replaced since its entry was read. */
    private class RunChanged : Exception("run replaced")

    /** Disk cache under the app's cache dir (call from activity + worker). */
    fun init(context: Context) {
        if (dir == null) dir = File(context.applicationContext.cacheDir, "wx-static").apply { mkdirs() }
    }

    private fun file(name: String): File? = dir?.let { File(it, name) }

    private fun writeAtomic(f: File?, bytes: ByteArray) {
        if (f == null) return
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(f)
    }

    /** Ranged GET of [len] bytes at [off] of [key] → (body, ETag). */
    @Suppress("ThrowsCount")
    private suspend fun range(key: String, off: Long, len: Int, ifMatch: String? = null): Pair<ByteArray, String> {
        val headers = buildMap {
            put("Range", "bytes=$off-${off + len - 1}")
            put("Accept-Encoding", "identity")
            if (ifMatch != null) put("If-Match", ifMatch)
        }
        val r = fetcher(base + key, headers)
        return when (r.status) {
            HTTP_PARTIAL -> {
                if (r.body.size != len) throw java.io.IOException("wx static $key: ${r.body.size} bytes, want $len")
                r.body to (r.headers["etag"] ?: "")
            }
            HTTP_PRECONDITION_FAILED -> throw RunChanged()
            HTTP_FORBIDDEN, HTTP_NOT_FOUND -> throw NotCovered("no $key")
            else -> throw java.io.IOException("wx static $key: HTTP ${r.status}")
        }
    }

    private fun loadEntry(key: String): Entry? {
        entries[key]?.let { return it }
        val f = file("e/$key") ?: return null
        if (!f.exists()) return null
        return runCatching {
            val b = f.readBytes()
            val raw = b.copyOfRange(0, WxPack.RUN_ENTRY_LEN)
            val rest = String(b, WxPack.RUN_ENTRY_LEN, b.size - WxPack.RUN_ENTRY_LEN, Charsets.UTF_8).split('\n')
            Entry(WxPack.decodeRunEntry(raw), raw, rest[0], rest[1].toLong())
        }.getOrNull()?.also { entries[key] = it }
    }

    // Offline with a cached entry: the IOException is the expected signal to
    // keep serving the cached run, not an error to propagate.
    @Suppress("SwallowedException")
    private suspend fun entry(ty: Int, tx: Int, now: Long, force: Boolean = false): Entry {
        val key = "${ty}_$tx"
        val cached = loadEntry(key)
        if (cached != null && !force) {
            val age = now - cached.fetchedAt
            val next = if (cached.e.gen == 0L) cached.fetchedAt + ABSENT_RECHECK_SECS else cached.e.nextCheck
            val due = now >= next || age >= ENTRY_MAX_AGE_SECS
            if (!due || age < POLL_MIN_SECS) return cached
        }
        val (raw, etag) = try {
            range(RUN_KEY, WxPack.runEntryOffset(ty, tx), WxPack.RUN_ENTRY_LEN)
        } catch (e: java.io.IOException) {
            if (cached != null) return cached
            throw e
        }
        val e = WxPack.decodeRunEntry(raw)
        if (e.gen != 0L && (e.ty != ty || e.tx != tx)) throw NotCovered("run pack layout changed")
        val fresh = Entry(e, raw, etag, now)
        entries[key] = fresh
        writeAtomic(file("e/$key"), raw + "$etag\n$now".toByteArray())
        return fresh
    }

    private suspend fun runTile(en: Entry, ty: Int, tx: Int): WxTiles.Tile {
        val key = "${en.e.gen}_${ty}_$tx"
        tiles[key]?.let { return it }
        val f = file("t/$key.wxt")
        val bytes = f?.takeIf { it.exists() }?.readBytes()
            ?: range(RUN_KEY, en.e.tile.off, en.e.tile.len, ifMatch = en.etag).first.also {
                // A new run: drop older tiles.
                file("t")?.listFiles()?.forEach { old -> if (old.name.endsWith("_${ty}_$tx.wxt")) old.delete() }
                writeAtomic(f, it)
            }
        tiles.keys.removeAll { it.endsWith("_${ty}_$tx") }
        return WxTiles.decodeTile(bytes).also { tiles[key] = it }
    }

    /** The finished days before the run tile (immutable: cached for good, old days dropped). */
    private suspend fun history(en: Entry, ty: Int, tx: Int, now: Long): List<WxTiles.Tile> {
        val days = WxGrid.histDaysNeeded(now, en.e.histToDay.toLong() * 24)
        val out = days.map { d ->
            val key = "${d}_${ty}_$tx"
            hists[key] ?: run {
                val f = file("h/$key.wxt")
                val bytes = f?.takeIf { it.exists() }?.readBytes() ?: run {
                    val s = en.e.histSpan(d) ?: throw NotCovered("no history day $d")
                    range("hist/$d.wxp", s.off, s.len).first.also { writeAtomic(f, it) }
                }
                WxTiles.decodeTile(bytes).also { hists[key] = it }
            }
        }
        if (!days.isEmpty()) {
            val oldest = days.first - 1
            hists.keys.removeAll { it.substringBefore('_').toLong() < oldest }
            file("h")?.listFiles()?.forEach { f ->
                val day = f.name.substringBefore('_').toLongOrNull()
                if (day != null && day < oldest) f.delete()
            }
        }
        return out
    }

    @Suppress("ThrowsCount")
    private suspend fun geo(k: WxGrid.GeoKey): WxTiles.GeoBlock {
        val key = "${k.x}_${k.y}_${k.b}"
        geos[key]?.let { return it }
        val f = file("g/$key.wxg")
        val bytes = f?.takeIf { it.exists() }?.readBytes() ?: run {
            val h = geoHeader ?: file("g/header")?.takeIf { it.exists() }?.readBytes()?.let(WxPack::decodeHeader)
                ?: range(GEO_KEY, 0, WxPack.HEADER_LEN.toInt()).first.let { b ->
                    writeAtomic(file("g/header"), b)
                    WxPack.decodeHeader(b)
                }
            geoHeader = h
            if (h.kind != WxPack.KIND_GEO || h.entryLen != 8) throw NotCovered("geo pack layout")
            val gby = k.y * 4 + k.b / 4
            val gbx = k.x * 4 + k.b % 4
            val at = h.entryOffset(gby, gbx) ?: throw NotCovered("no geo block $key")
            val s = WxPack.decodeSpan(range(GEO_KEY, at, 8).first)
            if (s.isEmpty) throw NotCovered("no geo block $key")
            range(GEO_KEY, s.off, s.len).first.also { writeAtomic(f, it) }
        }
        return WxTiles.decodeGeo(bytes).also { geos[key] = it }
    }

    private suspend fun inputs(
        ty: Int,
        tx: Int,
        k: WxGrid.GeoKey,
        now: Long,
    ): Triple<WxTiles.Tile, List<WxTiles.Tile>, WxTiles.GeoBlock> {
        var en = entry(ty, tx, now)
        if (en.e.gen == 0L || en.e.tile.isEmpty) throw NotCovered("no tile $ty/$tx")
        val tile = try {
            runTile(en, ty, tx)
        } catch (_: RunChanged) {
            en = entry(ty, tx, now, force = true)
            runTile(en, ty, tx)
        }
        return Triple(tile, history(en, ty, tx, now), geo(k))
    }

    /**
     * The full bundle for (lat, lon) at [nowEpoch] from R2. Throws on any
     * failure ([NotCovered] for points without a tile, e.g. open ocean).
     */
    @Suppress("ThrowsCount")
    suspend fun bundle(lat: Double, lon: Double, nowEpoch: Long = System.currentTimeMillis() / MS_PER_S): WxBundle =
        withContext(Dispatchers.IO) {
            if (!enabled) throw NotCovered("disabled")
            val (ty, tx) = WxGrid.tileOf(lat, lon, WxPack.NY, WxPack.NX, WxPack.CELLS)
            val k = WxGrid.geoOf(lat, lon) ?: throw NotCovered("off the DEM grid")
            val (t, hist, g) = lock.withLock {
                try {
                    inputs(ty, tx, k, nowEpoch)
                } catch (e: RunChanged) {
                    throw NotCovered("run replaced twice", e)
                }
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
        entries.clear()
        tiles.clear()
        hists.clear()
        geos.clear()
        geoHeader = null
        dir = null
    }
}
