package com.vayunmathur.weather.domain.wx

/**
 * Grid math + tile → bundle inputs: the port of the server's `om::cell_index`,
 * `dem` pixel math and the reference static client
 * (`weather/export/client.rs::series_from_tile`).
 */
internal object WxGrid {
    class ModelDef(val lat0: Double, val lon0: Double, val dy: Double, val dx: Double)

    val ICON = ModelDef(-90.0, -180.0, 0.125, 0.125)
    val CAMS_GLOBAL = ModelDef(-90.0, -180.0, 0.4, 0.4)
    val CAMS_EUROPE = ModelDef(30.05, -24.95, 0.1, 0.1)

    val ICON_VARS = listOf(
        "temperature_2m", "relative_humidity_2m", "precipitation", "pressure_msl", "cloud_cover", "weather_code",
        "wind_u_component_10m", "wind_v_component_10m", "wind_gusts_10m", "diffuse_radiation", "direct_radiation",
    )
    val CAMS_AQ_VARS = listOf("pm2_5", "pm10", "ozone", "nitrogen_dioxide", "sulphur_dioxide", "carbon_monoxide")
    const val CAMS_UV_VAR = "uv_index"

    /** Tile stand-in for the radiation pair (exact sunny-hour flag). */
    const val SUNNY_VAR = "sunny"

    /** Tile stand-in for the six AQ pollutants (per-hour US AQI). */
    const val US_AQI_VAR = "us_aqi"
    private val RADIATION = setOf("diffuse_radiation", "direct_radiation")

    /** ICON variables a tile carries. */
    val TILE_ICON_VARS = ICON_VARS.filter { it !in RADIATION } + SUNNY_VAR
    val POLLEN_VARS = listOf(
        "grass_pollen", "birch_pollen", "olive_pollen", "alder_pollen", "ragweed_pollen", "mugwort_pollen",
    )

    fun hourOf(epoch: Long): Long = Math.floorDiv(epoch, 3600L)

    fun europeContains(lat: Double, lon: Double): Boolean =
        lat >= 30.05 && lat <= 71.95 && lon >= -24.95 && lon <= 44.95

    /** `om::cell_index`: lon wraps, j clamps. */
    fun cellIndex(def: ModelDef, lat: Double, lon: Double, ny: Int, nx: Int): Pair<Int, Int> {
        val wrapped = WxMath.remEuclid(lon + 180.0, 360.0) - 180.0
        val j = WxMath.rround((lat - def.lat0) / def.dy).toLong()
        var i = WxMath.rround((wrapped - def.lon0) / def.dx).toLong()
        val jj = j.coerceIn(0, ny.toLong() - 1).coerceAtLeast(0)
        if (nx > 0) i = ((i % nx) + nx) % nx
        return jj.toInt() to i.coerceAtLeast(0).toInt()
    }

    /** `dem::cell_node`: unclamped, unwrapped node of the DEM cell box. */
    fun cellNode(lat: Double, lon: Double): Pair<Long, Long> {
        val wrapped = WxMath.remEuclid(lon + 180.0, 360.0) - 180.0
        return WxMath.rround((lat - -90.0) / ICON.dy).toLong() to WxMath.rround((wrapped - -180.0) / ICON.dx).toLong()
    }

    /** `dem::tile_frac` at zoom 10. */
    fun tileFrac(lat: Double, lon: Double): Pair<Double, Double> {
        val n = 1024.0
        val fx = (lon + 180.0) / 360.0 * n
        val fy = (1.0 - WxMath.asinh(StrictMath.tan(StrictMath.toRadians(lat))) / Math.PI) / 2.0 * n
        return fx to fy
    }

    /** Rust `f64 as u32` (saturating, NaN → 0), kept in a Long. */
    private fun asU32(v: Double): Long = if (v.isNaN()) 0 else v.toLong().coerceIn(0L, 0xFFFF_FFFFL)

    class GeoKey(val x: Int, val y: Int, val b: Int, val px: Int, val py: Int)

    /** The geo block + pixel `dem::point_elev` samples; null off the z10 grid. */
    fun geoOf(lat: Double, lon: Double): GeoKey? {
        val (fx, fy) = tileFrac(lat, lon)
        val xt = asU32(StrictMath.floor(fx))
        val yt = asU32(StrictMath.floor(fy))
        if (xt >= 1024 || yt >= 1024) return null
        val px = minOf(asU32((fx - xt.toDouble()) * 256.0), 255L).toInt()
        val py = minOf(asU32((fy - yt.toDouble()) * 256.0), 255L).toInt()
        return GeoKey(xt.toInt(), yt.toInt(), (py / 64) * 4 + px / 64, px % 64, py % 64)
    }

    /** ICON tile (ty, tx) of a point. */
    fun tileOf(lat: Double, lon: Double, ny: Int, nx: Int, cells: Int): Pair<Int, Int> {
        val (j, i) = cellIndex(ICON, lat, lon, ny, nx)
        return j / cells to i / cells
    }

    class Unavailable(msg: String) : Exception(msg)

    /** History days (epoch hour / 24) a bundle at [now] needs before hour [runH0]. */
    fun histDaysNeeded(now: Long, runH0: Long): LongRange =
        Math.floorDiv(WxWindows.at(now).hFetchLo, 24L) until Math.floorDiv(runH0, 24L)

    /** Cell [c] over `[lo, hi)`: run block, earlier hours from the history days. */
    private fun stitched(run: WxTiles.VarBlock, hist: List<WxTiles.VarBlock>, c: Int, lo: Long, hi: Long): FloatArray {
        val v = run.window(c, lo, hi)
        var h = lo
        while (h < minOf(run.h0, hi)) {
            val b = hist.firstOrNull { h >= it.h0 && h < it.h0 + it.nt } ?: throw Unavailable("missing history hour $h")
            v[(h - lo).toInt()] = b.values[c * b.nt + (h - b.h0).toInt()]
            h++
        }
        return v
    }

    /**
     * Bundle inputs for (lat, lon) at [now] from the run tile, the history
     * day tiles before it and the point's geo block; throws [Unavailable]
     * when they cannot serve it.
     */
    @Suppress("LongMethod", "ThrowsCount", "CyclomaticComplexMethod", "ComplexCondition")
    fun seriesFromTile(
        t: WxTiles.Tile,
        hist: List<WxTiles.Tile>,
        g: WxTiles.GeoBlock,
        lat: Double,
        lon: Double,
        now: Long,
    ): WxPointSeries {
        val w = WxWindows.at(now)
        val icon = t.section(WxTiles.MODEL_ICON) ?: throw Unavailable("no ICON section")
        val (j, i) = cellIndex(ICON, lat, lon, icon.ny, icon.nx)
        val c = icon.cell(j, i) ?: throw Unavailable("point outside tile")
        val histIcon = hist.mapNotNull { it.section(WxTiles.MODEL_ICON) }
        if (histIcon.any { it.j0 != icon.j0 || it.i0 != icon.i0 || it.nj != icon.nj || it.ni != icon.ni }) {
            throw Unavailable("history tile of another block")
        }
        val iconM = HashMap<String, FloatArray>()
        for (v in TILE_ICON_VARS) {
            val b = icon.variable(v) ?: throw Unavailable("tile lacks $v")
            val hb = histIcon.mapNotNull { it.variable(v) }
            iconM[v] = WxDerive.applyUnitSeries(v, stitched(b, hb, c, w.hFetchLo, w.hHi))
        }
        val pb = icon.variable("precipitation") ?: throw Unavailable("tile lacks precipitation")
        val precipM = stitched(pb, histIcon.mapNotNull { it.variable("precipitation") }, c, w.mLo, w.mHi)
        val inEurope = europeContains(lat, lon)
        val def = if (inEurope) CAMS_EUROPE else CAMS_GLOBAL
        val sec = t.section(if (inEurope) WxTiles.MODEL_CAMS_EUROPE else WxTiles.MODEL_CAMS_GLOBAL)
            ?: throw Unavailable("tile lacks the CAMS section")
        val (cj, ci) = cellIndex(def, lat, lon, sec.ny, sec.nx)
        val cc = sec.cell(cj, ci) ?: throw Unavailable("CAMS cell outside tile")
        val camsNames = if (inEurope) listOf(US_AQI_VAR) else listOf(US_AQI_VAR, CAMS_UV_VAR)
        val cams = camsNames.associateWith { v ->
            val b = sec.variable(v) ?: throw Unavailable("tile lacks $v")
            WxDerive.applyUnitSeries(v, b.window(cc, w.hLo, w.hHi))
        }
        val pollen = if (!inEurope) emptyMap() else POLLEN_VARS.associateWith { v ->
            val b = sec.variable(v) ?: throw Unavailable("tile lacks $v")
            b.window(cc, w.hLo, w.hHi)
        }
        val key = geoOf(lat, lon) ?: throw Unavailable("off the DEM grid")
        if (key.x != g.x || key.y != g.y || key.b != g.b) throw Unavailable("wrong geo block")
        val (nj, ni) = cellNode(lat, lon)
        val point = g.elevation(key.px, key.py)
        val mean = t.cellMean(nj, ni)
        val tzName = g.zone(key.px, key.py) ?: throw Unavailable("no zone")
        val zone = t.zone(tzName) ?: throw Unavailable("tile lacks zone $tzName")
        return WxPointSeries(
            tzName = tzName,
            tz = zone,
            icon = iconM,
            precipM = precipM,
            cams = cams,
            pollen = pollen,
            elevations = if (point != null && mean != null) point to mean else null,
            inEurope = inEurope,
        )
    }
}

/** `compute::Windows`. */
internal class WxWindows(
    val nowH: Long,
    val hLo: Long,
    val hHi: Long,
    val hFetchLo: Long,
    val mLo: Long,
    val mHi: Long,
) {
    companion object {
        fun at(now: Long): WxWindows {
            val nowH = WxGrid.hourOf(now)
            val hLo = nowH - 7 * 24
            val mLo = nowH - 24
            return WxWindows(nowH, hLo, hLo + 337, hLo - 24, mLo, mLo + 72)
        }
    }
}

/** `compute::PointSeries` (series in display units, NaN = missing). */
internal class WxPointSeries(
    val tzName: String,
    val tz: WxTiles.Zone,
    val icon: Map<String, FloatArray>,
    val precipM: FloatArray,
    val cams: Map<String, FloatArray>,
    val pollen: Map<String, FloatArray>,
    /** (point m, cell mean m), null = no lapse correction. */
    val elevations: Pair<Double, Double>?,
    val inEurope: Boolean,
)
