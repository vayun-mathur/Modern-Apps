package com.vayunmathur.library.map

/**
 * Bounded Kotlin access to basemap geometry from the on-device `.mamaps`
 * archive — the geometry the Vulkan renderer otherwise keeps entirely
 * on the native side.
 *
 * Data comes from the archive's own decoded tile bodies, through the
 * same codec and tile selection the renderer uses (no second parser):
 * `roads` lines become [MapGeometryKind.ROAD]/[MapGeometryKind.PATH],
 * water features on the merged `landtype` layer become
 * [MapGeometryKind.WATER], and `buildings` polygons become
 * [MapGeometryKind.BUILDING]. Coordinates are plain lon/lat
 * ([GeoPoint]); quantization to a caller's grid (the wear snapshot's
 * 2 m) is the caller's step, at its own resolution.
 *
 * Performance discipline: a query is viewport-bounded (only tiles
 * covering [GeoBounds] are read, at the archive's deepest zoom capped
 * street-level, stepping out until the tile count fits), feature- and
 * point-capped on the native side ([MAX_FEATURES], plus a native point
 * budget that never cuts a feature mid-ring), and entirely off the
 * render path — it opens its own read-only archive view and never
 * touches a renderer handle. Call it from a background thread; it does
 * file I/O. Results arrive in wear priority order (roads, paths,
 * water, buildings), tiles centre-first.
 *
 * Honesty: when there is no readable archive (no path, no file, an
 * unparseable file, or the native library is absent) every requested
 * kind is reported in [MapGeometryResult.unsourcedKinds] and the
 * feature list is empty — callers must name those kinds degraded
 * rather than draw an empty map as if it were complete. When the
 * archive *was* read, a kind with no features genuinely has none in
 * the region (an ocean or desert bbox), and is not unsourced.
 */

/** A basemap geometry kind a [MapGeometry] query can return. */
enum class MapGeometryKind {
    ROAD,
    PATH,
    WATER,
    BUILDING,
}

/**
 * One extracted feature: a polyline, or one polygon ring/part when
 * [closed]. [kindClass] is the feature's class (a road's highway class
 * such as `residential`, else its kind name such as `lake`/`building`);
 * empty when the source feature carries neither.
 */
data class MapGeometryFeature(
    val kind: MapGeometryKind,
    val kindClass: String,
    val closed: Boolean,
    val points: List<GeoPoint>,
)

/**
 * The outcome of one [MapGeometry.query].
 *
 * [unsourcedKinds] is the set of requested kinds the query could not
 * source at all (archive unavailable). It is empty whenever the archive
 * was read — even for kinds that returned zero features, which is the
 * distinction between "no such features here" and "source unavailable".
 */
data class MapGeometryResult(
    val features: List<MapGeometryFeature> = emptyList(),
    val unsourcedKinds: Set<MapGeometryKind> = emptySet(),
    /** True when a native cap kept geometry back. */
    val truncated: Boolean = false,
    /** Tiles the archive held and the query decoded; 0 = nothing scanned. */
    val coveredTiles: Int = 0,
    /** Tiles covering the bbox at the chosen zoom. */
    val totalTiles: Int = 0,
) {
    fun featuresOf(kind: MapGeometryKind): List<MapGeometryFeature> =
        features.filter { it.kind == kind }
}

/** The geometry source: queries against one on-device archive path. */
object MapGeometry {
    /** Hard ceiling on features per query, whatever a caller asks for. */
    const val MAX_FEATURES = 512

    /** Default feature cap for a region-sized query. */
    const val DEFAULT_MAX_FEATURES = 256

    internal const val KIND_ROAD_BIT = 1
    internal const val KIND_PATH_BIT = 2
    internal const val KIND_WATER_BIT = 4
    internal const val KIND_BUILDING_BIT = 8

    internal fun bitOf(kind: MapGeometryKind): Int = when (kind) {
        MapGeometryKind.ROAD -> KIND_ROAD_BIT
        MapGeometryKind.PATH -> KIND_PATH_BIT
        MapGeometryKind.WATER -> KIND_WATER_BIT
        MapGeometryKind.BUILDING -> KIND_BUILDING_BIT
    }

    /**
     * Query [kinds] inside [bounds] from the archive at [archivePath].
     *
     * A null/blank path, an absent native library, or a native
     * UNAVAILABLE answer all yield an empty result with every requested
     * kind in [MapGeometryResult.unsourcedKinds]; this never throws.
     * Does file I/O: call from a background thread.
     */
    fun query(
        archivePath: String?,
        bounds: GeoBounds,
        kinds: Set<MapGeometryKind> = MapGeometryKind.entries.toSet(),
        maxFeatures: Int = DEFAULT_MAX_FEATURES,
    ): MapGeometryResult {
        if (archivePath.isNullOrBlank() || kinds.isEmpty() ||
            bounds.west >= bounds.east || bounds.south >= bounds.north
        ) {
            return MapGeometryResult(unsourcedKinds = kinds)
        }
        if (!MapNative.isAvailable) {
            return MapGeometryResult(unsourcedKinds = kinds)
        }
        val mask = kinds.fold(0) { acc, kind -> acc or bitOf(kind) }
        val rows = try {
            MapNative.queryGeometry(
                archivePath,
                bounds.west,
                bounds.south,
                bounds.east,
                bounds.north,
                mask,
                maxFeatures.coerceIn(1, MAX_FEATURES),
            )
        } catch (t: Throwable) {
            // The JNI boundary contract: a native failure is an
            // unsourced answer, never a crash in a background sync.
            return MapGeometryResult(unsourcedKinds = kinds)
        }
        return parseRows(rows, kinds)
    }

    /**
     * Parse the native wire rows (see `MapNative.queryGeometry`) into a
     * result. Pure, so host tests pin the shape without a device.
     * Malformed rows are skipped, never fatal; an UNAVAILABLE or
     * unrecognisable header marks every requested kind unsourced.
     */
    internal fun parseRows(rows: Array<String>?, requested: Set<MapGeometryKind>): MapGeometryResult {
        val header = rows?.firstOrNull()?.split('\u0001')
        if (header == null || header.firstOrNull() != "OK") {
            return MapGeometryResult(unsourcedKinds = requested)
        }
        val features = mutableListOf<MapGeometryFeature>()
        for (row in rows.drop(1)) {
            val fields = row.split('\u0001')
            if (fields.size < 4) continue
            val kind = try {
                MapGeometryKind.valueOf(fields[0])
            } catch (t: IllegalArgumentException) {
                continue
            }
            if (kind !in requested) continue
            val coords = fields[3].split(',').mapNotNull { it.toLongOrNull() }
            if (coords.size < 4 || coords.size % 2 != 0) continue
            val points = (coords.indices step 2).map { i ->
                GeoPoint(coords[i] / 1e7, coords[i + 1] / 1e7)
            }
            features += MapGeometryFeature(
                kind = kind,
                kindClass = fields[1],
                closed = fields[2] == "1",
                points = points,
            )
        }
        return MapGeometryResult(
            features = features,
            truncated = header.getOrNull(1) == "1",
            coveredTiles = header.getOrNull(2)?.toIntOrNull() ?: 0,
            totalTiles = header.getOrNull(3)?.toIntOrNull() ?: 0,
        )
    }
}
