package com.vayunmathur.maps.util

import android.content.Context
import com.vayunmathur.library.log.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Offline reader for the P27 POI side files, memory-mapped from the app's
 * external files dir (the same dir the routing graph downloads into, see
 * [OfflineRouter] / MainActivity's InitialDownloadChecker):
 *
 *  * `poi_names.bin` — unique UTF-8 names, each NUL-terminated, concatenated in
 *    first-seen order. A `name_off` is the byte offset of a name's first byte;
 *    shared names resolve to the same offset.
 *  * `poi_index.bin` — a flat array of 14-byte little-endian records
 *    `{ int32 lat_e7; int32 lon_e7; uint32 name_off; uint16 type }`,
 *    `count = filesize / 14`, sorted ascending by the 64-bit Morton(lat,lon)
 *    key (the key itself is not stored — [spatialFromE7] recomputes it).
 *  * `poi_attrs.bin` - OPTIONAL attribute sidecar (opening hours, phone,
 *    website, address, cuisine, wheelchair), indexed by the ORDINAL of the
 *    matching `poi_index.bin` record. See `scripts/maps/osm_ingest/src/poi_attrs.rs`
 *    for the layout.
 *  * `poi_spatial.bin` - OPTIONAL sparse CSR lat/lon grid over record ordinals, so
 *    a bbox query visits only the cells it overlaps.
 *  * `poi_name_index.bin` - OPTIONAL word index: one `(record, word)` entry for
 *    every word of every name, sorted by word, so name search is a binary search.
 *    Both are laid out in `scripts/maps/osm_ingest/src/poi_side.rs`.
 *
 * It exposes a name search and a spatial nearest lookup, so a POI query can resolve
 * locally without a Google call. Everything is a harmless no-op until the index and
 * the name pool are present (they ship from the same host as the graph); queries then
 * return empty lists. The three side files are each separately optional, and each is
 * refused rather than trusted when its record count disagrees with the index — they
 * all join by ordinal, so a stale one would return the wrong place rather than none.
 *
 * **Nothing here scans the dataset.** Every lookup used to walk all `count` records,
 * which was imperceptible over a California extract and ANR'd the app on a planet-wide
 * one - 22.6 M records on the main thread inside a tap handler. Now:
 *
 *  * bbox queries go through the grid, or fall back to a Morton-span walk of the
 *    already-sorted `poi_index.bin` — see [Mapped.forEachInBbox] and its caveat;
 *  * name search goes through the word index, or falls back to the two-pass scan.
 *    The two differ deliberately; [searchByName] says how.
 */
object PoiIndex {
    private const val TAG = "PoiIndex"
    const val INDEX_FILE = "poi_index.bin"
    const val NAMES_FILE = "poi_names.bin"
    const val ATTRS_FILE = "poi_attrs.bin"
    const val SPATIAL_FILE = "poi_spatial.bin"
    const val NAME_INDEX_FILE = "poi_name_index.bin"

    /** Bytes per record: int32 lat_e7 + int32 lon_e7 + uint32 name_off + uint16 type. */
    internal const val RECORD_BYTES = 14
    /** Degrees per e7 unit: stored ints are degrees × 10⁷. */
    internal const val E7_TO_DEGREES = 1e-7
    /** Field offsets within one 14-byte index record. */
    private const val LON_FIELD_OFF = 4
    private const val NAME_OFF_FIELD_OFF = 8
    private const val TYPE_FIELD_OFF = 12

    /** Cap on candidates gathered before ranking, so a planet-sized pool can't
     *  blow up memory on a broad substring match. */
    internal const val CANDIDATE_CAP = 2_000

    // --- poi_attrs.bin layout (see poi_attrs.rs) ---------------------------
    private val ATTRS_MAGIC =
        byteArrayOf('M'.code.toByte(), 'A'.code.toByte(), 'P'.code.toByte(), 'A'.code.toByte())
    private const val ATTRS_VERSION = 1
    private const val ATTRS_HEADER_BYTES = 12
    private const val ATTRS_VERSION_OFF = 4
    private const val ATTRS_COUNT_OFF = 8
    /** Bytes of one int32 offset in the attribute offset array. */
    private const val ATTR_OFFSET_BYTES = 4
    /** Bytes of the u16 body-length header before an attribute blob. */
    private const val ATTR_BODY_HEADER_LEN = 2
    /** Bytes of one int32 slot in the CSR grid arrays. */
    private const val CELL_SLOT_BYTES = 4L
    /** `attr_off` for a POI that carries no attributes. */
    private const val NO_ATTRS = -1
    private const val U16_MASK = 0xFFFF

    // --- poi_spatial.bin / poi_name_index.bin (see osm_ingest/src/poi_side.rs) -----
    private val SPATIAL_MAGIC =
        byteArrayOf('P'.code.toByte(), 'S'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte())
    private const val SPATIAL_VERSION = 1
    internal const val SPATIAL_HEADER_BYTES = 32
    private const val SPATIAL_VERSION_OFF = 4
    private const val SPATIAL_COUNT_OFF = 8
    private const val SPATIAL_CELL_COUNT_OFF = 12
    private const val SPATIAL_LAT0_OFF = 16
    private const val SPATIAL_LON0_OFF = 20
    private const val SPATIAL_CELL_E7_OFF = 24
    private const val SPATIAL_COLS_OFF = 28

    private val NAME_INDEX_MAGIC =
        byteArrayOf('P'.code.toByte(), 'N'.code.toByte(), 'I'.code.toByte(), '1'.code.toByte())
    private const val NAME_INDEX_VERSION = 1
    internal const val NAME_INDEX_HEADER_BYTES = 16
    private const val NAME_INDEX_VERSION_OFF = 4
    private const val NAME_INDEX_COUNT_OFF = 8
    private const val NAME_INDEX_ENTRY_COUNT_OFF = 12
    private const val NAME_INDEX_ENTRY_BYTES = 5L

    /**
     * ASCII-only lowercase, and deliberately not [Char.lowercase].
     *
     * `poi_name_index.bin` is sorted by the writer, so the reader has to reproduce that
     * order byte for byte. Rust's `to_lowercase` and Kotlin's `lowercase` do not agree on
     * every input, and here a disagreement is a POI that can never be found. See the
     * cross-language contract in `osm_ingest/src/poi_side.rs`.
     */
    // Attribute key constants and record decoding live in PoiIndexAttrs.kt.

    // asciiLower / isAsciiSpace / queryKey moved to PoiIndexWords.kt as shared
    // internal helpers (asciiLowerW etc. were duplicates of these).

    /** A single POI resolved from the index. */
    data class PoiRecord(
        val latE7: Int,
        val lonE7: Int,
        val type: Int,
        val name: String,
        /**
         * Position in `poi_index.bin`, which is also this POI's key into the
         * attribute sidecar. -1 for a record not read from the index.
         */
        val ordinal: Int = -1,
    ) {
        val lat: Double get() = latE7 * E7_TO_DEGREES
        val lon: Double get() = lonE7 * E7_TO_DEGREES
    }

    /**
     * The OSM tags a place sheet wants, for one POI. Every field is null when OSM
     * did not tag it — which is the common case for most of them.
     */
    data class PoiAttributes(
        val openingHours: String? = null,
        val phone: String? = null,
        val website: String? = null,
        val houseNumber: String? = null,
        val street: String? = null,
        val city: String? = null,
        val postcode: String? = null,
        val cuisine: String? = null,
        val wheelchair: String? = null,
        /** Place rating merged in at build time from the external review table, 1.0–5.0. */
        val rating: Double? = null,
        val reviewCount: Int? = null,
        /** A short AI summary of the place's reviews. */
        val reviewSummary: String? = null,
    ) {
        /**
         * The `addr:*` tags as one display line, or null when there are none.
         *
         * House number joins the street with a space and the rest with commas, so a
         * partial address (very common in OSM — a street with no city) still reads
         * as an address rather than as a fragment.
         */
        val address: String?
            get() {
                val line = listOfNotNull(houseNumber, street)
                    .joinToString(" ")
                    .ifEmpty { null }
                return listOfNotNull(line, city, postcode)
                    .joinToString(", ")
                    .ifEmpty { null }
            }

        val isEmpty: Boolean
            get() = openingHours == null && phone == null && website == null &&
                address == null && cuisine == null && wheelchair == null &&
                rating == null && reviewCount == null && reviewSummary == null
    }

    /**
     * One consistent set of mappings, published as a unit.
     *
     * Every query used to be `@Synchronized` on this object, which meant a lookup for a tapped
     * pin also blocked a background viewport refresh and vice versa — for the duration of a
     * full-file scan. The mapped state is immutable once built, so the queries need no lock at
     * all: they read [mapped] once into a local and work from that. Only [reload] mutates, and
     * a query already in flight keeps using the snapshot it started with rather than seeing the
     * buffers swapped underneath it.
     */
    internal class Mapped(
        val index: MappedByteBuffer,
        val names: MappedByteBuffer,
        val namesLen: Int,
        val count: Int,
        /** The attribute sidecar, or null when it is absent or does not match. */
        val attrs: MappedByteBuffer?,
        /** Byte offset of the attribute blob, i.e. just past the offset array. */
        val attrsBlobStart: Int,
        /** The CSR spatial grid, or null when absent or mismatched. */
        internal val spatial: MappedByteBuffer? = null,
        internal val cellCount: Int = 0,
        internal val lat0E7: Int = 0,
        internal val lon0E7: Int = 0,
        internal val cellE7: Int = 0,
        internal val cols: Int = 0,
        /** The word index, or null when absent or mismatched. */
        internal val nameIdx: MappedByteBuffer? = null,
        internal val entryCount: Int = 0,
        /** The whole archive mapping, when loaded from one file: pins every slice above. */
        @Suppress("unused")
        val archive: MappedByteBuffer? = null,
    ) {
        internal fun latE7(i: Int): Int = index.getInt(i * RECORD_BYTES)
        internal fun lonE7(i: Int): Int = index.getInt(i * RECORD_BYTES + LON_FIELD_OFF)
        internal fun nameOff(i: Int): Int = index.getInt(i * RECORD_BYTES + NAME_OFF_FIELD_OFF)
        fun type(i: Int): Int =
            index.getShort(i * RECORD_BYTES + TYPE_FIELD_OFF).toInt() and U16_MASK

        /** The Morton key the file is sorted by, recomputed from the record's coordinate. */
        fun spatialAt(i: Int): Long = spatialFromE7(latE7(i), lonE7(i))

        internal fun record(i: Int): PoiRecord =
            PoiRecord(latE7(i), lonE7(i), type(i), nameAt(nameOff(i)) ?: "", i)

        /** Read the NUL-terminated UTF-8 name that starts at byte [off], or null. */
        fun nameAt(off: Int): String? {
            if (off < 0 || off >= namesLen) return null
            var end = off
            while (end < namesLen && names.get(end).toInt() != 0) end++
            val n = end - off
            if (n <= 0) return null
            val bytes = ByteArray(n)
            // Absolute bulk get keeps the shared buffer's position untouched.
            val dup = names.duplicate()
            dup.position(off)
            dup.get(bytes, 0, n)
            return String(bytes, Charsets.UTF_8)
        }

        /** Ordinal of the first record whose key is >= [key], or [count]. */
        fun lowerBound(key: Long): Int {
            var lo = 0
            var hi = count
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                // Unsigned: half the planet's keys have bit 63 set, and a signed compare here
                // walks off the front of the northern hemisphere.
                if (java.lang.Long.compareUnsigned(spatialAt(mid), key) < 0) lo = mid + 1 else hi = mid
            }
            return lo
        }

        /**
         * Visit every record inside the bbox in **ordinal order**, stopping early when
         * [onHit] returns false.
         *
         * Two implementations, picked by whether `poi_spatial.bin` was mapped:
         *
         *  * **Grid** — only the cells the box actually overlaps are visited, so the cost
         *    is the box's own area. Exact, and with no pathological cases.
         *  * **Morton walk** — a binary search to the box's lower key bound, then a walk to
         *    its upper bound. The bound is the box's *Morton span*, which is not the box: a
         *    box straddling the equator or the prime meridian flips a top-level Z-curve bit
         *    and covers most of the key space, degenerating back toward a full walk. Kept
         *    because the side file is optional, and it still beats scanning [count].
         *
         * Ordinal order either way. The grid visits cells row by row, so its hits come out
         * shuffled relative to the file and are sorted before being yielded — callers that
         * truncate at a cap, or that break distance ties by file order, must not see a
         * different answer depending on which side files happen to be present.
         */
        fun forEachInBbox(
            minLatE7: Int,
            maxLatE7: Int,
            minLonE7: Int,
            maxLonE7: Int,
            onHit: (ordinal: Int, latE7: Int, lonE7: Int) -> Boolean,
        ) {
            if (minLatE7 > maxLatE7 || minLonE7 > maxLonE7) return
            if (hasGrid()) {
                forEachInCells(minLatE7, maxLatE7, minLonE7, maxLonE7, onHit)
            } else {
                forEachInMortonSpan(minLatE7, maxLatE7, minLonE7, maxLonE7, onHit)
            }
        }

        /** Whether the CSR grid is present and usable for the query. */
        private fun hasGrid(): Boolean = spatial != null && cellCount > 0 && cols > 0 && cellE7 > 0

        private fun forEachInMortonSpan(
            minLatE7: Int,
            maxLatE7: Int,
            minLonE7: Int,
            maxLonE7: Int,
            onHit: (ordinal: Int, latE7: Int, lonE7: Int) -> Boolean,
        ) {
            val (first, last) = spatialRangeForBbox(minLatE7, maxLatE7, minLonE7, maxLonE7)
            var i = lowerBound(first)
            while (i < count) {
                if (java.lang.Long.compareUnsigned(spatialAt(i), last) > 0) return
                if (!visitIfInBox(i, minLatE7, maxLatE7, minLonE7, maxLonE7, onHit)) return
                i++
            }
        }

        /** Visit ordinal [i] when it falls inside the box; false stops the walk. */
        private fun visitIfInBox(
            i: Int,
            minLatE7: Int,
            maxLatE7: Int,
            minLonE7: Int,
            maxLonE7: Int,
            onHit: (ordinal: Int, latE7: Int, lonE7: Int) -> Boolean,
        ): Boolean {
            val latE7 = latE7(i)
            val lonE7 = lonE7(i)
            val inside = latE7 in minLatE7..maxLatE7 && lonE7 in minLonE7..maxLonE7
            return !inside || onHit(i, latE7, lonE7)
        }

        // The CSR grid walk (forEachInCells and its cell helpers) lives in PoiIndexSpatial.kt,
        // and word-index queries in PoiIndexWords.kt, both as internal extensions on this class.
    }

    @Volatile
    private var mapped: Mapped? = null
    private var tried = false

    /** Read access for the extension queries in PoiIndexSpatial.kt / PoiIndexWords.kt. */
    internal fun mappedForQuery(): Mapped? = mapped

    /** True once both side files were mapped successfully. */
    val available: Boolean get() = mapped != null

    /** True when the optional attribute sidecar is mapped and usable. */
    val attributesAvailable: Boolean get() = mapped?.attrs != null

    /** Number of POI records available offline (0 when not loaded). */
    val recordCount: Int get() = mapped?.count ?: 0

    /**
     * Map the POI data from [Context.getExternalFilesDir]. Idempotent and
     * safe to call repeatedly. Prefers the single-archive `.mamaps` file when
     * present (one download carries tiles, graph, POI and transit), falling
     * back to the side files. Returns false (and stays a no-op) when neither
     * is present. Not retried automatically after a failure unless [reload]
     * is called (e.g. after the download completes).
     */
    @Synchronized
    fun initialize(context: Context): Boolean {
        if (mapped != null) return true
        if (tried) return false
        tried = true
        val dir = context.getExternalFilesDir(null) ?: return false
        if (tryArchive(dir)) return true
        return open(dir)
    }

    /** Force a re-map after the download completes (archive preferred, then side files). */
    @Synchronized
    fun reload(context: Context): Boolean {
        val dir = context.getExternalFilesDir(null) ?: return false
        return reloadMapped(dir)
    }

    /** Re-map from a single-archive `.mamaps` file (see [PoiArchive]). */
    @Synchronized
    fun reloadArchive(file: File): Boolean {
        mapped = null
        tried = true
        val fromArchive = PoiArchive.openArchive(file) ?: return false
        mapped = fromArchive
        return true
    }

    /**
     * Re-map from an explicit directory.
     *
     * Also the seam `PoiIndexTest` maps fixtures through: the production entry point needs a
     * `Context` only to find the directory, and a unit test has a temp dir and no Context.
     */
    @Synchronized
    internal fun reload(dir: File): Boolean = reloadMapped(dir)

    /** Map the single archive when present, leaving [mapped] null otherwise. */
    private fun tryArchive(dir: File): Boolean {
        val archive = File(dir, MapTileCache.BASEMAP_ARCHIVE_FILE)
        if (!archive.isFile) return false
        val fromArchive = PoiArchive.openArchive(archive) ?: return false
        mapped = fromArchive
        return true
    }

    /** Shared re-map body: clear state, then map from [dir]. */
    private fun reloadMapped(dir: File): Boolean {
        mapped = null
        tried = true
        if (tryArchive(dir)) return true
        return open(dir)
    }

    @Synchronized
    private fun open(dir: File): Boolean {
        val indexFile = File(dir, INDEX_FILE)
        val namesFile = File(dir, NAMES_FILE)
        if (!indexFile.isFile || !namesFile.isFile) {
            Log.debug(TAG, "POI side files absent (index=${indexFile.exists()} names=${namesFile.exists()})")
            return false
        }
        return try {
            mapped = PoiIndexSideFiles.mapSideFiles(dir, indexFile, namesFile)
            Log.dev(
                TAG,
                "Loaded ${mapped?.count} POI records, names=${mapped?.namesLen}B, " +
                    "grid=${mapped?.cellCount ?: 0} cells, words=${mapped?.entryCount ?: 0}",
            )
            true
        } catch (e: java.io.IOException) {
            Log.status(TAG, "Failed to map POI side files", e)
            mapped = null
            false
        }
    }

    /** Buffer core of the attrs sidecar, reused by the single-archive path. */
    internal fun attrsFromBuffer(buf: MappedByteBuffer, count: Int): Pair<MappedByteBuffer, Int>? {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        return runCatching { readAttrs(buf, count) }
            .getOrElse { bufferFailure(it, ATTRS_FILE) }
    }

    private fun readAttrs(buf: MappedByteBuffer, count: Int): Pair<MappedByteBuffer, Int>? {
        if (buf.capacity() < ATTRS_HEADER_BYTES) {
            Log.status(TAG, "$ATTRS_FILE is truncated")
            return null
        }
        for (i in ATTRS_MAGIC.indices) {
            if (buf.get(i) != ATTRS_MAGIC[i]) {
                Log.status(TAG, "$ATTRS_FILE has the wrong magic")
                return null
            }
        }
        // Not a gate: the length prefixes let this reader step over a key added
        // by a later version, so a newer file is readable. A version it has
        // never heard of is worth a line in the log all the same.
        val version = buf.get(ATTRS_VERSION_OFF).toInt()
        if (version != ATTRS_VERSION) {
            Log.debug(TAG, "$ATTRS_FILE is version $version, expected $ATTRS_VERSION")
        }
        val attrCount = buf.getInt(ATTRS_COUNT_OFF)
        if (attrCount != count) {
            Log.status(TAG, "$ATTRS_FILE has $attrCount slots but the index has $count; ignoring it")
            return null
        }
        val blobStart = ATTRS_HEADER_BYTES + ATTR_OFFSET_BYTES * attrCount
        if (blobStart > buf.capacity()) {
            Log.status(TAG, "$ATTRS_FILE offset array runs past the file")
            return null
        }
        Log.dev(TAG, "Loaded POI attributes for $attrCount record(s)")
        return buf to blobStart
    }

    /**
     * Yield null (with a log line) for the failures a corrupt/truncated file
     * can actually produce — buffer overruns and bounds checks. Anything else
     * is a programming error and keeps propagating.
     */
    private fun <T> bufferFailure(cause: Throwable, file: String): T? {
        when (cause) {
            is IndexOutOfBoundsException,
            is IllegalArgumentException,
            -> {
                Log.status(TAG, "Failed to map $file", cause)
                return null
            }
            else -> throw cause
        }
    }

    /** The mapped spatial grid plus the parameters read out of its header. */
    internal class Grid(
        val buf: MappedByteBuffer,
        val cellCount: Int,
        val lat0E7: Int,
        val lon0E7: Int,
        val cellE7: Int,
        val cols: Int,
    )

    /** Buffer core of the spatial sidecar, reused by the single-archive path. */
    internal fun spatialFromBuffer(buf: MappedByteBuffer, count: Int): Grid? {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        return runCatching { readSpatial(buf, count) }
            .getOrElse { bufferFailure(it, SPATIAL_FILE) }
    }

    private fun readSpatial(buf: MappedByteBuffer, count: Int): Grid? {
        if (buf.capacity() < SPATIAL_HEADER_BYTES) return null
        for (i in SPATIAL_MAGIC.indices) {
            if (buf.get(i) != SPATIAL_MAGIC[i]) {
                Log.status(TAG, "$SPATIAL_FILE has the wrong magic; ignoring it")
                return null
            }
        }
        if (buf.getInt(SPATIAL_VERSION_OFF) != SPATIAL_VERSION) {
            Log.status(TAG, "$SPATIAL_FILE version ${buf.getInt(SPATIAL_VERSION_OFF)} unsupported; ignoring it")
            return null
        }
        val records = buf.getInt(SPATIAL_COUNT_OFF)
        if (records != count) {
            Log.status(TAG, "$SPATIAL_FILE covers $records record(s), index has $count; ignoring it")
            return null
        }
        val cellCount = buf.getInt(SPATIAL_CELL_COUNT_OFF)
        val cols = buf.getInt(SPATIAL_COLS_OFF)
        // cell_ids + cell_off + ordinals must all be present before any of them is read.
        val need = SPATIAL_HEADER_BYTES + CELL_SLOT_BYTES * cellCount +
            CELL_SLOT_BYTES * (cellCount + 1) + CELL_SLOT_BYTES * count
        if (cellCount < 0 || cols < 0 || need > buf.capacity()) {
            Log.status(TAG, "$SPATIAL_FILE is truncated; ignoring it")
            return null
        }
        return Grid(
            buf,
            cellCount,
            buf.getInt(SPATIAL_LAT0_OFF),
            buf.getInt(SPATIAL_LON0_OFF),
            buf.getInt(SPATIAL_CELL_E7_OFF),
            cols,
        )
    }

    /** Buffer core of the word-index sidecar, reused by the single-archive path. */
    internal fun nameIndexFromBuffer(buf: MappedByteBuffer, count: Int): Pair<MappedByteBuffer, Int>? {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        return runCatching { readNameIndex(buf, count) }
            .getOrElse { bufferFailure(it, NAME_INDEX_FILE) }
    }

    private fun readNameIndex(buf: MappedByteBuffer, count: Int): Pair<MappedByteBuffer, Int>? {
        if (buf.capacity() < NAME_INDEX_HEADER_BYTES) return null
        for (i in NAME_INDEX_MAGIC.indices) {
            if (buf.get(i) != NAME_INDEX_MAGIC[i]) {
                Log.status(TAG, "$NAME_INDEX_FILE has the wrong magic; ignoring it")
                return null
            }
        }
        if (buf.getInt(NAME_INDEX_VERSION_OFF) != NAME_INDEX_VERSION) {
            Log.status(TAG, "$NAME_INDEX_FILE version ${buf.getInt(NAME_INDEX_VERSION_OFF)} unsupported; ignoring it")
            return null
        }
        val records = buf.getInt(NAME_INDEX_COUNT_OFF)
        if (records != count) {
            Log.status(
                TAG,
                "$NAME_INDEX_FILE covers $records record(s), index has $count; ignoring it",
            )
            return null
        }
        val entries = buf.getInt(NAME_INDEX_ENTRY_COUNT_OFF)
        if (entries < 0 ||
            NAME_INDEX_HEADER_BYTES + NAME_INDEX_ENTRY_BYTES * entries > buf.capacity().toLong()
        ) {
            Log.status(TAG, "$NAME_INDEX_FILE is truncated; ignoring it")
            return null
        }
        return buf to entries
    }

    internal fun mapReadOnly(file: File): MappedByteBuffer =
        RandomAccessFile(file, "r").use { raf ->
            raf.channel.use { ch ->
                // Demo side files fit in a single mapping; cap defensively at 2GB.
                val len = minOf(ch.size(), Int.MAX_VALUE.toLong())
                ch.map(FileChannel.MapMode.READ_ONLY, 0, len)
            }
        }

    /**
     * Name search, ranked first-word-first then by distance to ([nearLat],[nearLon]).
     *
     * Two implementations, picked by whether `poi_name_index.bin` was mapped:
     *
     *  * **Indexed** — binary search to the query's place in a word-sorted index, then a
     *    walk of the matching range, so the cost is the number of matches. Because every
     *    word of a name is indexed, "pizza" still finds "Joe's Pizza".
     *  * **Scan** — the name pool, then every record. Two passes whose cost is the whole
     *    dataset, kept only because the side file is optional.
     *
     * **The two do not match exactly, by design.** The scan matches a substring anywhere,
     * so "izza" finds "Pizza"; a sorted index can only answer prefix questions, so the
     * indexed path matches a prefix *of any word*. That is a deliberate narrowing —
     * mid-word matches are lost, whole-word ones are not.
     */
    fun searchByName(
        query: String,
        nearLat: Double,
        nearLon: Double,
        limit: Int = 20,
    ): List<PoiRecord> {
        val m = mapped ?: return emptyList()
        if (query.isBlank()) return emptyList()
        if (m.nameIdx != null) return searchByWordIndex(m, query, nearLat, nearLon, limit)
        return searchByScan(m, query, nearLat, nearLon, limit)
    }

    // Indexed search lives in PoiIndexWords.kt; this delegates so searchByName is unchanged.
    private fun searchByWordIndex(
        m: Mapped,
        query: String,
        nearLat: Double,
        nearLon: Double,
        limit: Int,
    ): List<PoiRecord> = searchByWordIndex(m, query, nearLat, nearLon, limit, CANDIDATE_CAP)

    // Scan search lives in PoiIndexWords.kt; this delegates so searchByName is unchanged.
    // (This must call the package-level function there: calling itself
    // recurses forever — StackOverflowError on every scan-path search.)
    private fun searchByScan(
        m: Mapped,
        query: String,
        nearLat: Double,
        nearLon: Double,
        limit: Int,
    ): List<PoiRecord> =
        com.vayunmathur.maps.util.searchByScan(m, query, nearLat, nearLon, limit)

    // Spatial queries (nearest/inViewport) live in PoiIndexSpatial.kt as
    // extensions, alongside the grid they walk.

    /**
     * The attributes of the [ordinal]th index record, or null when there are none.
     *
     * Two indexed reads and a short decode, no scan. Deliberately NOT called while
     * building viewport or search result lists, where it would decode hundreds of
     * records nobody looks at.
     *
     * Every offset is treated as untrusted. The file arrives over the network and a
     * truncated download must read as "no attributes", not throw out of a tap.
     */
    fun attributesAt(ordinal: Int): PoiAttributes? {
        val m = mapped ?: return null
        val buf = m.attrs ?: return null
        val at = attrBodyAt(m, buf, ordinal) ?: return null
        return decodeAttrRecord(buf, at.first, at.second)
    }

    /** (bodyStart, bodyEnd) of the [ordinal]th record's attribute blob, or null. */
    private fun attrBodyAt(
        m: Mapped,
        buf: MappedByteBuffer,
        ordinal: Int,
    ): Pair<Int, Int>? {
        if (ordinal < 0 || ordinal >= m.count) return null
        val off = buf.getInt(ATTRS_HEADER_BYTES + ATTR_OFFSET_BYTES * ordinal)
        if (off == NO_ATTRS || off < 0) return null
        // Bounded before the add, not after: `attrsBlobStart + off` can wrap negative
        // for a large positive off, and a negative index passes an `at + 2 > capacity`
        // test on its way to an IndexOutOfBoundsException.
        val blobLen = buf.capacity() - m.attrsBlobStart
        if (off > blobLen - ATTR_BODY_HEADER_LEN) return null
        val at = m.attrsBlobStart + off
        val bodyLen = buf.getShort(at).toInt() and U16_MASK
        val body = at + ATTR_BODY_HEADER_LEN
        if (bodyLen > buf.capacity() - body) return null
        return body to body + bodyLen
    }

    /**
     * The attributes of the POI named [name] nearest to ([lat],[lon]), or null.
     *
     * This is the join a tapped tile feature needs, and the reason it is not an
     * exact coordinate match: the `ma_pois` tile layer quantises every point to its
     * tile's 4096-step grid (about 0.15 m at z16), so a tap's coordinate is close to
     * the side file's `lat_e7`/`lon_e7` but never equal to it.
     *
     * The name is matched first, normalised for case and punctuation (`McDonald's`
     * vs `McDonalds`, tile `name_en` vs sidecar `name`). A mall and a cafe inside it
     * can share a coordinate to within a metre, so a name hit still outranks pure
     * proximity. When nothing nearby matches by name — the tile and the sidecar
     * genuinely disagree — the nearest POI within [maxMeters] that carries
     * attributes is returned instead of nothing, so the sheet fills from OSM
     * rather than opening bare.
     *
     * Costs one [nearest] call, which is a binary search plus a walk of a 25 m box.
     * Still a file read against a cold mmap, so callers keep it off the main thread.
     */
    fun attributesNear(
        lat: Double,
        lon: Double,
        name: String,
        maxMeters: Double = 25.0,
    ): PoiAttributes? {
        val m = mapped
        if (m?.attrs == null) {
            Log.dev(
                TAG,
                "attributesNear: sidecar absent (index loaded=${m != null}); " +
                    "no attrs for \"$name\" at ($lat, $lon)",
            )
            return null
        }
        if (name.isBlank()) return null
        val candidates = nearest(lat, lon, limit = 8, maxMeters = maxMeters)
        val sought = normName(name)
        candidates.firstOrNull { normName(it.name) == sought }?.let { return attributesAt(it.ordinal) }
        for (rec in candidates) {
            val attrs = attributesAt(rec.ordinal)
            if (attrs != null) {
                Log.dev(
                    TAG,
                    "attributesNear: \"$name\" at ($lat, $lon) matched nothing by name; " +
                        "using nearest-with-attrs \"${rec.name}\"",
                )
                return attrs
            }
        }
        Log.dev(
            TAG,
            "attributesNear: \"$name\" at ($lat, $lon) matched none of " +
                "${candidates.size} candidate(s) within $maxMeters m",
        )
        return null
    }

    /**
     * A name reduced to alphanumerics, lowercased, for the sidecar join.
     *
     * Tile names and sidecar names disagree on case and punctuation far more often
     * than on the words themselves, so an exact match misses places both sides
     * know. Only used to compare two Kotlin-side strings — never against the
     * Rust writer's sort order, so [String.lowercase] (not [asciiLower]) is fine.
     */
    private fun normName(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /** Cheap squared planar distance (deg², lon scaled by cos lat) for ranking. */
    internal fun distanceSq(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val cosLat = Math.cos(Math.toRadians((aLat + bLat) / 2.0))
        val dLat = aLat - bLat
        val dLon = (aLon - bLon) * cosLat
        return dLat * dLat + dLon * dLon
    }
}
