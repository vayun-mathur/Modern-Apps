package com.vayunmathur.maps.util

import com.vayunmathur.library.log.Log
import java.io.File
import java.nio.ByteOrder
import java.nio.MappedByteBuffer

/**
 * Side-file mapping for [PoiIndex]: `poi_index.bin` + `poi_names.bin` plus the
 * four optional sidecars.
 *
 * Split out so `PoiIndex` stays under the function cap. Same package, so no
 * API changes for any other caller.
 */
internal object PoiIndexSideFiles {
    private const val TAG = "PoiIndex"

    fun mapSideFiles(dir: File, indexFile: File, namesFile: File): PoiIndex.Mapped {
        val indexBuf = PoiIndex.mapReadOnly(indexFile).also { it.order(ByteOrder.LITTLE_ENDIAN) }
        val namesBuf = PoiIndex.mapReadOnly(namesFile)
        val count = (indexFile.length() / PoiIndex.RECORD_BYTES).toInt()
        // Separate and optional: a failure here leaves the index perfectly
        // usable, just without attributes.
        val attrs = openAttrs(File(dir, PoiIndex.ATTRS_FILE), count)
        val grid = openSpatial(File(dir, PoiIndex.SPATIAL_FILE), count)
        val words = openNameIndex(File(dir, PoiIndex.NAME_INDEX_FILE), count)
        val region = openRegion(File(dir, REGION_FILE), count)
        val admin = File(dir, ADMIN_FILE).takeIf { it.isFile }?.let { adminFromBuffer(PoiIndex.mapReadOnly(it)) }
        return PoiIndex.Mapped(
            index = indexBuf,
            names = namesBuf,
            namesLen = namesBuf.capacity(),
            count = count,
            attrs = attrs?.first,
            attrsBlobStart = attrs?.second ?: 0,
            spatial = grid?.buf,
            cellCount = grid?.cellCount ?: 0,
            lat0E7 = grid?.lat0E7 ?: 0,
            lon0E7 = grid?.lon0E7 ?: 0,
            cellE7 = grid?.cellE7 ?: 0,
            cols = grid?.cols ?: 0,
            nameIdx = words?.first,
            entryCount = words?.second ?: 0,
            region = region,
            admin = admin,
        )
    }

    /**
     * Map the attribute sidecar, returning null (rather than throwing) if anything is off.
     *
     * The record-count check is the one that matters: the sidecar joins to the
     * index purely by position, so a sidecar from a different build would hand
     * every place someone else's phone number. Refusing the file is the only safe
     * response, and there is no way to detect the mismatch later.
     *
     * @return the mapped buffer and the byte offset of the blob, or null.
     */
    private fun openAttrs(file: File, count: Int): Pair<PoiBytes, Int>? {
        if (!file.isFile) {
            Log.debug(TAG, "POI attribute sidecar absent")
            return null
        }
        return PoiIndex.attrsFromBuffer(PoiIndex.mapReadOnly(file), count)
    }

    /**
     * Map `poi_spatial.bin`, or null when it is absent, stale or malformed.
     *
     * The record-count check matters for the same reason it does for the sidecar: the grid
     * stores *ordinals*, so a grid built against a different `poi_index.bin` would return
     * the wrong places rather than none. Refusing it costs only the Morton fallback.
     */
    private fun openSpatial(file: File, count: Int): PoiIndex.Grid? {
        if (!file.isFile) return null
        return PoiIndex.spatialFromBuffer(PoiIndex.mapReadOnly(file), count)
    }

    /** Map `poi_name_index.bin` and its entry count, or null when unusable. */
    private fun openNameIndex(file: File, count: Int): Pair<MappedByteBuffer, Int>? {
        if (!file.isFile) return null
        return PoiIndex.nameIndexFromBuffer(PoiIndex.mapReadOnly(file), count)
    }

    /**
     * Map `poi_region.bin`, or null when it is absent, stale or malformed.
     *
     * The record-count check matters for the same reason it does for the
     * others: the sidecar joins purely by ordinal, so a region file from a
     * different build would hand every place someone else's boundary.
     */
    private fun openRegion(file: File, count: Int): PoiBytes? {
        if (!file.isFile) return null
        return regionFromBuffer(PoiIndex.mapReadOnly(file), count)
    }
}
