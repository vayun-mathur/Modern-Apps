package com.vayunmathur.maps.domain

/**
 * The 64-bit Morton (Z-order) key that `poi_index.bin` and `nodes.bin` are sorted by.
 *
 * This is a bit-for-bit cross-language contract with `latlng_to_spatial` in
 * `scripts/maps/osm_ingest/src/spatial.rs` (the writer) and `Graph::latlng_to_spatial` in
 * `maps/src/main/rust/src/graph.rs`. The device binary-searches files the Rust tools wrote, so
 * a one-bit disagreement does not fail — it silently returns the wrong records. `SpatialKeyTest`
 * pins this against vectors generated from the Rust implementation rather than from expectation.
 *
 * Keys are `u64` held in a `Long` for their bits only. Everything north of the equator has bit
 * 63 set, so a signed comparison sorts the whole northern hemisphere *below* the southern one:
 * every comparison has to go through [java.lang.Long.compareUnsigned].
 */

/** Longitude/latitude offsets and ranges mapping degrees into 0..1. */
private const val LON_OFFSET = 180.0
private const val LON_RANGE = 360.0
private const val LAT_OFFSET = 90.0
private const val LAT_RANGE = 180.0
/** `u32::MAX` as a double, for scaling a 0..1 coordinate into 32 bits. */
private const val U32_MAX_AS_DOUBLE = 4_294_967_295.0
/** `u32::MAX` as a long, for saturating the float-to-int conversion. */
private const val U32_MAX_AS_LONG = 0xFFFF_FFFFL
/** Bits per coordinate in the Morton key; two key bits (one per axis) per bit. */
private const val MORTON_BITS = 32
private const val BITS_PER_AXIS = 2
private const val BIT_MASK = 1
/** Stored ints are degrees × 10⁷; multiply (not divide) to match the writer bit-for-bit. */
private const val E7_TO_DEGREES = 1e-7

fun latLngToSpatial(lat: Double, lon: Double): Long {
    val x = (lon + LON_OFFSET) / LON_RANGE
    val y = (lat + LAT_OFFSET) / LAT_RANGE
    val ix = toU32(x * U32_MAX_AS_DOUBLE)
    val iy = toU32(y * U32_MAX_AS_DOUBLE)
    var res = 0L
    for (i in 0 until MORTON_BITS) {
        res = res or (((ix ushr i) and BIT_MASK).toLong() shl (i * BITS_PER_AXIS))
        res = res or (((iy ushr i) and BIT_MASK).toLong() shl (i * BITS_PER_AXIS + 1))
    }
    return res
}

/** [latLngToSpatial] for a stored `lat_e7`/`lon_e7` pair, mirroring `spatial_from_e7`. */
fun spatialFromE7(latE7: Int, lonE7: Int): Long =
    // `* 1e-7`, not `/ 1e7`. The two disagree in the last bit for roughly a third of e7 values
    // (they are different operations, and 1e-7 is not exactly representable). No sampled
    // disagreement actually reached the key, but this is a bit-for-bit contract: matching the
    // writer's arithmetic is cheaper than arguing about which differences survive truncation.
    latLngToSpatial(latE7 * E7_TO_DEGREES, lonE7 * E7_TO_DEGREES)

/**
 * The smallest Morton interval that is guaranteed to contain every point of the
 * `minLatE7..maxLatE7` × `minLonE7..maxLonE7` box, as `(first, last)` inclusive.
 *
 * Bit interleaving is monotone in each axis independently — the highest differing key bit
 * belongs to either x or y, and a 1 there forces that coordinate to be larger — so the box's two
 * extreme corners bound every point inside it. The interval is a *superset*: it also contains
 * points outside the box, which is why callers still test the exact coordinates.
 *
 * The interval is as wide as the box's Z-curve span, not as the box. A box straddling the
 * equator or the prime meridian flips a top-level bit and spans most of the key space, which is
 * Z-order's known discontinuity (pinned in `spatial.rs`'s
 * `morton_jumps_a_whole_quadrant_at_a_quadrant_boundary`). `poi_spatial.bin` replaces this with
 * an exact cell lookup; this is the bound available without a format change.
 */
fun spatialRangeForBbox(minLatE7: Int, maxLatE7: Int, minLonE7: Int, maxLonE7: Int): Pair<Long, Long> =
    spatialFromE7(minLatE7, minLonE7) to spatialFromE7(maxLatE7, maxLonE7)

/**
 * Rust's `f64 as u32`, which *saturates*: below zero clamps to 0, above `u32::MAX` clamps to
 * `u32::MAX`, NaN is 0.
 *
 * Kotlin's `Double.toInt()` saturates at `Int.MAX_VALUE` instead — half the range — which would
 * fold every longitude east of the prime meridian onto the same key. Going via `Long` keeps the
 * full range; `Double.toLong()` already saturates and already maps NaN to 0.
 */
private fun toU32(v: Double): Int = v.toLong().coerceIn(0L, U32_MAX_AS_LONG).toInt()
