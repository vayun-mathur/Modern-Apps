package com.vayunmathur.weather.network

import android.content.Context
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.weather.domain.map.OmMapMetadata
import com.vayunmathur.weather.domain.wx.WxCompute
import com.vayunmathur.weather.domain.wx.WxDerive
import com.vayunmathur.weather.domain.wx.WxGrid
import com.vayunmathur.weather.domain.wx.WxPack
import com.vayunmathur.weather.domain.wx.WxTiles
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
 * disk. Any failure throws; callers fall back to their Room cache. The one
 * exception is [NotCovered] (a point with no land tile, e.g. open ocean),
 * where [WeatherApi] keeps a flagged origin fallback — see its doc.
 */
object WxStatic {
    private const val BASE = "https://static.maproj.org/wx/v1/"

    /** Bundle-vocabulary variables the map can shade from the run tiles. */
    val MAP_VARIABLES = listOf(
        "temperature_2m", "apparent_temperature", "relative_humidity_2m", "dew_point_2m", "precipitation",
        "wind_speed_10m", "wind_u_component_10m", "wind_v_component_10m", "wind_gusts_10m", "pressure_msl",
        "visibility", "cloud_cover", "uv_index",
    )
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

    // --- Manifest (latest.json) -------------------------------------------

    /** `latest.json` manifest written last by each generator run. */
    @Serializable
    data class Manifest(
        val v: Int = 1,
        val run: String = "",
        val gen: Long = 0,
        @SerialName("generated_at") val generatedAt: Long = 0,
        @SerialName("next_check") val nextCheck: Long = 0,
        @SerialName("icon_run") val iconRun: String = "",
        @SerialName("valid_times") val validTimes: List<String> = emptyList(),
        val variables: List<String> = emptyList(),
    )

    private val manifestJson = Json { ignoreUnknownKeys = true }

    /** Test seam: full (non-ranged) GET of `latest.json` as text. */
    @androidx.annotation.VisibleForTesting
    internal var manifestFetcher: suspend (String) -> String = { url ->
        val r = NetworkClient.performRequest(url)
        if (!r.isSuccess) throw java.io.IOException("wx manifest: HTTP ${r.status}")
        r.body
    }

    @Volatile private var manifestCache: Manifest? = null

    /**
     * The current run's manifest, cached until its `next_check`. Advisory:
     * the bundle path works without it (per-tile entries carry the same
     * `gen`/`next_check`); this is how callers learn the run's
     * `generated_at` and valid hours without decoding a tile.
     */
    suspend fun manifest(nowEpoch: Long = System.currentTimeMillis() / MS_PER_S): Manifest {
        manifestCache?.let { if (nowEpoch < it.nextCheck) return it }
        val m = manifestJson.decodeFromString<Manifest>(manifestFetcher(base + "latest.json"))
        manifestCache = m
        return m
    }

    /** When the served run was generated (epoch seconds), or 0 if unknown. */
    suspend fun generatedAtEpoch(): Long = runCatching { manifest().generatedAt }.getOrDefault(0L)

    // --- Point reads derived from the packs --------------------------------

    /**
     * Time zone of a point from its `geo.wxp` block alone (zone name), with
     * the offset/abbreviation resolved on device for [nowEpoch]. Works for
     * ocean points too: the geo grid carries `Etc/GMT±N` zones. Throws
     * [NotCovered] off the geo grid.
     */
    suspend fun timezoneAt(lat: Double, lon: Double, nowEpoch: Long = System.currentTimeMillis() / MS_PER_S): RegionTimezone =
        withContext(Dispatchers.IO) {
            if (!enabled) throw NotCovered("disabled")
            val k = WxGrid.geoOf(lat, lon) ?: throw NotCovered("off the DEM grid")
            val g = lock.withLock { geo(k) }
            val name = g.zone(k.px, k.py) ?: throw NotCovered("no zone")
            val tz = java.util.TimeZone.getTimeZone(name)
            // getTimeZone falls back to GMT for unknown ids; only trust an
            // exact id match (or UTC), else report the name with 0 offset.
            val known = tz.id == name || name == "UTC"
            val at = java.util.Date(nowEpoch * MS_PER_S)
            RegionTimezone(
                timezone = name,
                abbreviation = if (known) tz.getDisplayName(tz.inDaylightTime(at), java.util.TimeZone.SHORT, java.util.Locale.US) else name,
                utcOffsetSeconds = if (known) tz.getOffset(nowEpoch * MS_PER_S) / 1000 else 0,
            )
        }

    /** Current temperature (°C) of one point from its static bundle. */
    suspend fun currentTemperature(lat: Double, lon: Double, nowEpoch: Long = System.currentTimeMillis() / MS_PER_S): Double? =
        bundle(lat, lon, nowEpoch).current?.temp

    /**
     * Current temperatures for up to 20 points (search-result rows). Each
     * point is sampled from its tile — tiles are disk/memory cached, so
     * points sharing a tile cost one fetch. A point that cannot be served
     * is absent from the result, as with the old batch endpoint.
     */
    suspend fun currentTemperatures(
        points: List<Pair<Double, Double>>,
        nowEpoch: Long = System.currentTimeMillis() / MS_PER_S,
    ): Map<Pair<Double, Double>, Double> {
        if (points.isEmpty()) return emptyMap()
        val out = LinkedHashMap<Pair<Double, Double>, Double>()
        for (p in points.take(20)) {
            val t = runCatching { currentTemperature(p.first, p.second, nowEpoch) }.getOrNull()
            if (t != null) out[p] = t
        }
        return out
    }

    // --- Map reads (tiles instead of the `.om` spatial proxy) --------------

    private suspend fun tileAt(ty: Int, tx: Int, now: Long): WxTiles.Tile {
        var en = entry(ty, tx, now)
        if (en.e.gen == 0L || en.e.tile.isEmpty) throw NotCovered("no tile $ty/$tx")
        return try {
            runTile(en, ty, tx)
        } catch (_: RunChanged) {
            en = entry(ty, tx, now, force = true)
            runTile(en, ty, tx)
        }
    }

    private fun isoHour(epochHour: Long): String {
        val d = java.time.Instant.ofEpochSecond(epochHour * 3600).atOffset(java.time.ZoneOffset.UTC)
        return String.format(java.util.Locale.US, "%04d-%02d-%02dT%02d:00Z", d.year, d.monthValue, d.dayOfMonth, d.hour)
    }

    private fun parseHourIso(s: String): Long? = runCatching {
        val norm = when {
            s.endsWith("Z") && s.count { it == ':' } == 1 -> s.dropLast(1) + ":00Z"
            else -> s
        }
        java.time.Instant.parse(norm).epochSecond / 3600
    }.getOrNull()

    /**
     * Map metadata for the run serving (lat, lon): run time, the ICON hours
     * its tiles hold, and the shadeable variables. Null when the point has
     * no tile. Replaces `data_spatial/<model>/latest.json` on the origin.
     */
    suspend fun mapMetadata(lat: Double, lon: Double, nowEpoch: Long = System.currentTimeMillis() / MS_PER_S): OmMapMetadata? =
        withContext(Dispatchers.IO) {
            if (!enabled) return@withContext null
            val (ty, tx) = WxGrid.tileOf(lat, lon, WxPack.NY, WxPack.NX, WxPack.CELLS)
            val tile = runCatching { lock.withLock { tileAt(ty, tx, nowEpoch) } }.getOrNull() ?: return@withContext null
            val icon = tile.section(WxTiles.MODEL_ICON) ?: return@withContext null
            val temp = icon.variable("temperature_2m") ?: return@withContext null
            OmMapMetadata(
                referenceTime = tile.iconRunIso,
                validTimes = (0 until temp.nt).map { isoHour(temp.h0 + it) },
                variables = MAP_VARIABLES,
            )
        }

    /**
     * Values of [variable] (bundle vocabulary, e.g. `temperature_2m`,
     * `apparent_temperature`, `wind_speed_10m`, `uv_index`) over a bbox at
     * [validTime], sampled nearest-cell from the run tiles into an
     * [outW]×[outH] row-major raster (row 0 = north, Mercator rows as the
     * old `.om` resampler). NaN where no tile/variable covers a pixel.
     *
     * Precision: the old path bilinearly interpolated the same 0.125°
     * ICON grid from `.om` files; this reads the identical grid from the
     * R2 tiles but takes the nearest cell (no interpolation, no DEM lapse
     * correction — the `.om` path had neither lapse either), so edges are
     * up to half a cell (~7 km) blockier. Derived series (feels/dew/
     * visibility/wind speed) use the same [WxDerive] formulas as the bundle.
     */
    @Suppress("LongParameterList", "CyclomaticComplexMethod")
    suspend fun regionValues(
        variable: String, validTime: String,
        west: Double, south: Double, east: Double, north: Double,
        outW: Int, outH: Int,
        nowEpoch: Long = System.currentTimeMillis() / MS_PER_S,
    ): FloatArray? = withContext(Dispatchers.IO) {
        if (!enabled || outW <= 0 || outH <= 0) return@withContext null
        val epochH = parseHourIso(validTime) ?: return@withContext null
        val byTile = HashMap<Pair<Int, Int>, WxTiles.Tile?>()
        suspend fun tileFor(lat: Double, lon: Double): WxTiles.Tile? {
            val tt = WxGrid.tileOf(lat, lon, WxPack.NY, WxPack.NX, WxPack.CELLS)
            if (!byTile.containsKey(tt)) {
                byTile[tt] = runCatching { lock.withLock { tileAt(tt.first, tt.second, nowEpoch) } }.getOrNull()
            }
            return byTile[tt]
        }
        val merc = { latDeg: Double -> StrictMath.log(StrictMath.tan(Math.PI / 4 + Math.toRadians(latDeg) / 2)) }
        val invMerc = { y: Double -> Math.toDegrees(2 * StrictMath.atan(StrictMath.exp(y)) - Math.PI / 2) }
        val yN = merc(north)
        val yS = merc(south)
        val out = FloatArray(outW * outH) { Float.NaN }
        for (r in 0 until outH) {
            val lat = invMerc(yN + (r + 0.5) / outH * (yS - yN))
            for (c in 0 until outW) {
                val lon = west + (c + 0.5) * (east - west) / outW
                val tile = tileFor(lat, lon) ?: continue
                out[r * outW + c] = sampleVariable(tile, variable, lat, lon, epochH)
            }
        }
        out
    }

    private fun sampleVariable(tile: WxTiles.Tile, variable: String, lat: Double, lon: Double, epochH: Long): Float {
        val icon = tile.section(WxTiles.MODEL_ICON) ?: return Float.NaN
        val (j, i) = WxGrid.cellIndex(WxGrid.ICON, lat, lon, icon.ny, icon.nx)
        val cell = icon.cell(j, i) ?: return Float.NaN
        fun raw(section: WxTiles.Section, name: String, cj: Int, ci: Int): Double {
            val b = section.variable(name) ?: return Double.NaN
            val cc = section.cell(cj, ci) ?: return Double.NaN
            val k = epochH - b.h0
            if (k < 0 || k >= b.nt) return Double.NaN
            val v = b.values[cc * b.nt + k.toInt()].toDouble()
            return if (v.isFinite()) WxDerive.applyUnitSeries(name, floatArrayOf(v.toFloat()))[0].toDouble() else v
        }
        fun iv(name: String): Double = raw(icon, name, j, i)
        val temp = iv("temperature_2m")
        val rh = iv("relative_humidity_2m")
        return when (variable) {
            "temperature_2m" -> temp.toFloat()
            "relative_humidity_2m" -> rh.toFloat()
            "pressure_msl" -> iv("pressure_msl").toFloat()
            "cloud_cover" -> iv("cloud_cover").toFloat()
            "precipitation" -> iv("precipitation").toFloat()
            "wind_gusts_10m" -> iv("wind_gusts_10m").toFloat()
            "wind_speed_10m" -> {
                val u = iv("wind_u_component_10m") / 3.6
                val v = iv("wind_v_component_10m") / 3.6
                (WxDerive.windSpdDir(u, v)?.first ?: Double.NaN).toFloat()
            }
            "apparent_temperature" -> {
                val u = iv("wind_u_component_10m") / 3.6
                val v = iv("wind_v_component_10m") / 3.6
                val ms = if (u.isFinite() && v.isFinite()) StrictMath.hypot(u, v) else Double.NaN
                (WxDerive.apparentTemperature(temp, rh, ms) ?: Double.NaN).toFloat()
            }
            "dew_point_2m" -> (WxDerive.dewPoint(temp, rh) ?: Double.NaN).toFloat()
            "visibility" -> (WxDerive.visibility(iv("precipitation"), rh) ?: Double.NaN).toFloat()
            "uv_index" -> {
                val inEurope = WxGrid.europeContains(lat, lon)
                val sec = tile.section(if (inEurope) WxTiles.MODEL_CAMS_EUROPE else WxTiles.MODEL_CAMS_GLOBAL)
                    ?: return Float.NaN
                val def = if (inEurope) WxGrid.CAMS_EUROPE else WxGrid.CAMS_GLOBAL
                val (cj, ci) = WxGrid.cellIndex(def, lat, lon, sec.ny, sec.nx)
                raw(sec, "uv_index", cj, ci).toFloat()
            }
            else -> Float.NaN
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun resetForTest() {
        entries.clear()
        tiles.clear()
        hists.clear()
        geos.clear()
        geoHeader = null
        manifestCache = null
        dir = null
    }
}
