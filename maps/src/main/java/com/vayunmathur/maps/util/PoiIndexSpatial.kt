package com.vayunmathur.maps.util

/**
 * The CSR spatial-grid half of [PoiIndex]: bbox queries over `poi_spatial.bin`.
 *
 * Split out so PoiIndex.kt stays under the FileLength limit. These are `internal`
 * extensions on `PoiIndex.Mapped`; `forEachInBbox` keeps dispatching to
 * [forEachInCells] when the grid is present.
 */

/** Cell size: one int32 per CSR array slot. */
private const val CELL_SLOT_BYTES = 4
/** Degrees of latitude per metre, so a metre radius becomes a degree box. */
private const val METERS_PER_DEGREE_LAT = 111_320.0
/** Smallest cosine kept, so the longitude span stays finite at the poles. */
private const val MIN_COS_LAT = 1e-6
/** Stored ints are degrees × 10⁷. */
private const val E7_PER_DEGREE = 1e7

/** Cell offset along one axis. Must match `cell_axis` in `poi_side.rs`. */
private fun PoiIndex.Mapped.axis(value: Int, origin: Int): Int {
    val d = value.toLong() - origin.toLong()
    return if (d <= 0) 0 else (d / cellE7).toInt()
}

private fun PoiIndex.Mapped.row(latE7: Int): Int = axis(latE7, lat0E7)
private fun PoiIndex.Mapped.col(lonE7: Int): Int = axis(lonE7, lon0E7).coerceAtMost(cols - 1)

/** Whether the CSR grid is present and its header describes a usable grid. */
private fun PoiIndex.Mapped.gridUsable(): Boolean =
    spatial != null && cellCount > 0 && minOf(cols, cellE7) > 0

/** The [i]th entry of the ascending populated-cell-id array. */
private fun PoiIndex.Mapped.cellIdAt(i: Int): Int =
    spatial!!.getInt(PoiIndex.SPATIAL_HEADER_BYTES + CELL_SLOT_BYTES * i)

/** Index of the first populated cell whose id is >= [cellId], or [PoiIndex.Mapped.cellCount]. */
private fun PoiIndex.Mapped.cellLowerBound(cellId: Int): Int {
    var lo = 0
    var hi = cellCount
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (cellIdAt(mid) < cellId) lo = mid + 1 else hi = mid
    }
    return lo
}

/**
 * Visit the populated cells overlapping the box, as indices into the cell arrays, stopping
 * when [onCell] returns false.
 *
 * One binary search per grid ROW, then a forward walk of the ascending cell-id array across
 * the row (a row's populated cells are adjacent in that array), rather than one binary
 * search per cell of the box.
 */
internal fun PoiIndex.Mapped.forEachCellInBox(
    minLatE7: Int,
    maxLatE7: Int,
    minLonE7: Int,
    maxLonE7: Int,
    onCell: (cellIndex: Int) -> Boolean,
) {
    if (!gridUsable()) return
    val c0 = col(minLonE7)
    val c1 = col(maxLonE7)
    for (r in row(minLatE7)..row(maxLatE7)) {
        val last = r.toLong() * cols + c1
        var ci = cellLowerBound((r.toLong() * cols + c0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        while (ci < cellCount && cellIdAt(ci) <= last) {
            if (!onCell(ci)) return
            ci++
        }
    }
}

/**
 * Every ordinal filed under the populated cells overlapping the box, cell by cell, with no
 * per-record coordinate test and no sort: for callers that compute each hit's distance
 * themselves anyway (name search), the cell granularity (~2 km) is the whole filter they
 * need. Stops when [visit] returns false.
 */
internal fun PoiIndex.Mapped.forEachGridOrdinal(
    minLatE7: Int,
    maxLatE7: Int,
    minLonE7: Int,
    maxLonE7: Int,
    visit: (ordinal: Int) -> Boolean,
) {
    forEachCellInBox(minLatE7, maxLatE7, minLonE7, maxLonE7) { ci ->
        var k = cellOff(ci)
        val end = cellOff(ci + 1)
        var go = true
        while (go && k < end) {
            val ordinal = gridOrdinal(k)
            if (ordinal in 0 until count) go = visit(ordinal)
            k++
        }
        go
    }
}

/** CSR prefix entry [i], i.e. where cell `i`'s ordinals begin. */
private fun PoiIndex.Mapped.cellOff(i: Int): Int =
    spatial!!.getInt(
        PoiIndex.SPATIAL_HEADER_BYTES + CELL_SLOT_BYTES * cellCount + CELL_SLOT_BYTES * i
    )

private fun PoiIndex.Mapped.gridOrdinal(k: Int): Int =
    spatial!!.getInt(
        PoiIndex.SPATIAL_HEADER_BYTES + CELL_SLOT_BYTES * cellCount +
            CELL_SLOT_BYTES * (cellCount + 1) + CELL_SLOT_BYTES * k
    )

internal fun PoiIndex.Mapped.forEachInCells(
    minLatE7: Int,
    maxLatE7: Int,
    minLonE7: Int,
    maxLonE7: Int,
    onHit: (ordinal: Int, latE7: Int, lonE7: Int) -> Boolean,
) {
    val hits = ArrayList<Int>()
    forEachGridOrdinal(minLatE7, maxLatE7, minLonE7, maxLonE7) { ordinal ->
        if (latE7(ordinal) in minLatE7..maxLatE7 && lonE7(ordinal) in minLonE7..maxLonE7) {
            hits.add(ordinal)
        }
        true
    }
    hits.sort()
    for (ordinal in hits) {
        if (!onHit(ordinal, latE7(ordinal), lonE7(ordinal))) return
    }
}

private class Hit(val idx: Int, val distSq: Double)

/**
 * Nearest POIs to ([lat],[lon]) within [maxMeters], closest first (bbox
 * pre-filter + squared-distance sort). Names are decoded only for the
 * returned winners. Empty when the index isn't loaded.
 */
internal fun PoiIndex.nearest(
    lat: Double,
    lon: Double,
    limit: Int = 20,
    maxMeters: Double = 250.0,
): List<PoiIndex.PoiRecord> {
    val m = mappedForQuery() ?: return emptyList()
    val dLat = maxMeters / METERS_PER_DEGREE_LAT
    val cosLat = Math.cos(Math.toRadians(lat)).coerceAtLeast(MIN_COS_LAT)
    val dLon = maxMeters / (METERS_PER_DEGREE_LAT * cosLat)
    val minLatE7 = ((lat - dLat) * E7_PER_DEGREE).toInt()
    val maxLatE7 = ((lat + dLat) * E7_PER_DEGREE).toInt()
    val minLonE7 = ((lon - dLon) * E7_PER_DEGREE).toInt()
    val maxLonE7 = ((lon + dLon) * E7_PER_DEGREE).toInt()

    val hits = ArrayList<Hit>()
    m.forEachInBbox(minLatE7, maxLatE7, minLonE7, maxLonE7) { i, latE7, lonE7 ->
        hits.add(Hit(i, distanceSq(latE7 / E7_PER_DEGREE, lonE7 / E7_PER_DEGREE, lat, lon)))
        true
    }
    // Stable, so records at an equal distance stay in file order — the tie-break the
    // scan this replaced had, and what the equivalence test pins.
    hits.sortBy { it.distSq }
    return hits.take(limit).map { m.record(it.idx) }
}

/**
 * All POIs whose coordinate falls inside the [west]..[east] × [south]..[north]
 * bounding box, capped at [cap]. Names are decoded for each returned record.
 * Empty when the index isn't loaded; used to drive the ambient offline pin
 * overlay (P29).
 */
internal fun PoiIndex.inViewport(
    west: Double,
    south: Double,
    east: Double,
    north: Double,
    cap: Int = 300,
): List<PoiIndex.PoiRecord> {
    val m = mappedForQuery() ?: return emptyList()
    if (cap <= 0) return emptyList()
    val minLatE7 = (south * E7_PER_DEGREE).toInt()
    val maxLatE7 = (north * E7_PER_DEGREE).toInt()
    val minLonE7 = (west * E7_PER_DEGREE).toInt()
    val maxLonE7 = (east * E7_PER_DEGREE).toInt()
    val out = ArrayList<PoiIndex.PoiRecord>(minOf(cap, m.count))
    m.forEachInBbox(minLatE7, maxLatE7, minLonE7, maxLonE7) { i, _, _ ->
        out.add(m.record(i))
        out.size < cap
    }
    return out
}
