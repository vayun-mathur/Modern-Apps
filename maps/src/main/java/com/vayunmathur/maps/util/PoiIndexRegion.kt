package com.vayunmathur.maps.util

import com.vayunmathur.library.log.Log
import java.nio.MappedByteBuffer

/**
 * `poi_region.bin` reader: one tagged OSM boundary id (`u64`, 0 when the POI
 * links to no boundary) per `poi_index.bin` ordinal, so search hits and places
 * mask to their native boundary without a spatial lookup. The layout lives in
 * `scripts/maps/osm_ingest/src/poi_side.rs`; the low 2 bits of each id are the
 * OSM id space (2 = way, 3 = relation).
 *
 * Split out of [PoiIndex] for the FileLength limit. Like every other sidecar,
 * a region file whose record count disagrees with the index is refused rather
 * than trusted, since everything joins by ordinal.
 */
const val REGION_FILE = "poi_region.bin"

private const val TAG = "PoiIndex"

// --- poi_region.bin (see osm_ingest/src/poi_side.rs) -------------------------
private val REGION_MAGIC =
    byteArrayOf('P'.code.toByte(), 'R'.code.toByte(), 'G'.code.toByte(), '1'.code.toByte())
private const val REGION_VERSION = 1
internal const val REGION_HEADER_BYTES = 12
private const val REGION_VERSION_OFF = 4
private const val REGION_COUNT_OFF = 8
/** Bytes of one tagged OSM boundary id in the region array. */
internal const val REGION_ID_BYTES = 8
/** Tagged id for a POI that links to no boundary. */
internal const val REGION_NONE = 0L

/** Tagged OSM boundary id for record [i], or 0 when the region sidecar is absent. */
internal fun PoiIndex.Mapped.regionId(i: Int): Long =
    region?.getLong(REGION_HEADER_BYTES + i * REGION_ID_BYTES) ?: REGION_NONE

/**
 * Buffer core of the region sidecar, reused by the single-archive path. A planet5 archive
 * stores it block-compressed ([PoiBlockSection]: 0.25 GB -> 7 MB); [PoiBytes.of] reads either.
 */
internal fun regionFromBuffer(buf: MappedByteBuffer, count: Int): PoiBytes? {
    return runCatching {
        val bytes = PoiBytes.of(buf) ?: run {
            Log.status(TAG, "$REGION_FILE block table is malformed; ignoring it")
            return null
        }
        readRegion(bytes, count)
    }.getOrElse { PoiIndex.bufferFailure(it, REGION_FILE) }
}

private fun readRegion(buf: PoiBytes, count: Int): PoiBytes? {
    if (buf.size < REGION_HEADER_BYTES) return null
    for (i in REGION_MAGIC.indices) {
        if (buf.get(i) != REGION_MAGIC[i]) {
            Log.status(TAG, "$REGION_FILE has the wrong magic; ignoring it")
            return null
        }
    }
    if (buf.getInt(REGION_VERSION_OFF) != REGION_VERSION) {
        Log.status(TAG, "$REGION_FILE version ${buf.getInt(REGION_VERSION_OFF)} unsupported; ignoring it")
        return null
    }
    val records = buf.getInt(REGION_COUNT_OFF)
    if (records != count) {
        Log.status(TAG, "$REGION_FILE covers $records record(s), index has $count; ignoring it")
        return null
    }
    if (REGION_HEADER_BYTES + REGION_ID_BYTES.toLong() * records > buf.size.toLong()) {
        Log.status(TAG, "$REGION_FILE is truncated; ignoring it")
        return null
    }
    return buf
}
