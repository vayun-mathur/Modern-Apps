package com.vayunmathur.maps.util

import com.vayunmathur.library.log.Log
import java.nio.ByteOrder
import java.nio.MappedByteBuffer

/**
 * The locality line of a search row — "City, State, Country" — from the archive alone.
 *
 * Two sources, best first:
 *
 *  * **`poi_admin.bin`** (archive kind 16, `osm_ingest/src/poi_admin.rs`): for every boundary
 *    a POI links to in `poi_region.bin`, and every boundary above it, the name, `name:en`,
 *    `admin_level` and parent. A row's locality is its own boundary walked up to the country:
 *    the city is the first level 5-8 boundary on the way up (a level 9-10 district such as a
 *    Paris arrondissement is skipped in favour of the city above it), the state the level-4
 *    boundary (level 3 where a country has no level 4), the country level 2.
 *  * **Without it** (planet4 predates the section) only the city can be derived: the POI's
 *    own `addr:city`, else the `addr:city` most POIs of the same boundary nearby agree on.
 *
 * Results are cached per boundary (the rows of one search nearly all share a handful), so a
 * page of 20 rows costs a few binary searches and no scan. Everything here is a mapped-file
 * read: call it off the main thread.
 */

/** `poi_admin.bin`, validated (see [adminFromBuffer]). */
internal class AdminTable(val buf: MappedByteBuffer, val count: Int, private val poolStart: Int, poolLen: Int) {
    private val poolEnd = poolStart + poolLen

    /** Entry index of [tagged], or -1. Entries are sorted ascending by tagged id. */
    fun find(tagged: Long): Int {
        var lo = 0
        var hi = count - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = buf.getLong(entryAt(mid))
            when {
                v < tagged -> lo = mid + 1
                v > tagged -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    fun parent(i: Int): Long = buf.getLong(entryAt(i) + PARENT_OFF)

    fun level(i: Int): Int = buf.get(entryAt(i) + LEVEL_OFF).toInt() and BYTE_MASK

    /** The entry's `name:en` when [english] and present, else its `name`; null when empty. */
    fun name(i: Int, english: Boolean): String? {
        val en = if (english) buf.getInt(entryAt(i) + NAME_EN_OFF) else NO_NAME
        val own = buf.getInt(entryAt(i) + NAME_OFF)
        return poolString(if (en != NO_NAME) en else own) ?: poolString(own)
    }

    private fun entryAt(i: Int): Int = HEADER_BYTES + ENTRY_BYTES * i

    private fun poolString(off: Int): String? {
        if (off < 0 || poolStart + off >= poolEnd) return null
        var end = poolStart + off
        while (end < poolEnd && buf.get(end).toInt() != 0) end++
        val n = end - poolStart - off
        if (n <= 0) return null
        val bytes = ByteArray(n)
        val dup = buf.duplicate()
        dup.position(poolStart + off)
        dup.get(bytes, 0, n)
        return String(bytes, Charsets.UTF_8)
    }

    internal companion object {
        const val HEADER_BYTES = 16
        const val ENTRY_BYTES = 32
        const val PARENT_OFF = 8
        const val NAME_OFF = 16
        const val NAME_EN_OFF = 20
        const val LEVEL_OFF = 24
        const val NO_NAME = -1
        const val BYTE_MASK = 0xFF
    }
}

const val ADMIN_FILE = "poi_admin.bin"

private const val TAG = "PoiIndex"
private val ADMIN_MAGIC = byteArrayOf('P'.code.toByte(), 'A'.code.toByte(), 'D'.code.toByte(), '1'.code.toByte())
private const val ADMIN_VERSION = 1
private const val ADMIN_COUNT_OFF = 8
private const val ADMIN_NAMES_LEN_OFF = 12
private const val ADMIN_VERSION_OFF = 4

/** The admin sidecar as an [AdminTable], or null when it is malformed (it then degrades as absent). */
internal fun adminFromBuffer(buf: MappedByteBuffer): AdminTable? {
    buf.order(ByteOrder.LITTLE_ENDIAN)
    return runCatching { readAdmin(buf) }.getOrElse { PoiIndex.bufferFailure(it, ADMIN_FILE) }
}

private fun readAdmin(buf: MappedByteBuffer): AdminTable? {
    if (buf.capacity() < AdminTable.HEADER_BYTES || ADMIN_MAGIC.indices.any { buf.get(it) != ADMIN_MAGIC[it] }) {
        Log.status(TAG, "$ADMIN_FILE has the wrong magic; ignoring it")
        return null
    }
    if (buf.getInt(ADMIN_VERSION_OFF) != ADMIN_VERSION) {
        Log.status(TAG, "$ADMIN_FILE version ${buf.getInt(ADMIN_VERSION_OFF)} unsupported; ignoring it")
        return null
    }
    val count = buf.getInt(ADMIN_COUNT_OFF)
    val namesLen = buf.getInt(ADMIN_NAMES_LEN_OFF)
    val poolStart = AdminTable.HEADER_BYTES.toLong() + AdminTable.ENTRY_BYTES.toLong() * count
    if (count < 0 || namesLen < 0 || poolStart + namesLen > buf.capacity()) {
        Log.status(TAG, "$ADMIN_FILE is truncated; ignoring it")
        return null
    }
    return AdminTable(buf, count, poolStart.toInt(), namesLen)
}

/** Admin levels: the band a city sits in, the state levels, and the country. */
private const val CITY_MIN_LEVEL = 5
private const val CITY_MAX_LEVEL = 8
private const val STATE_LEVEL = 4
private const val REGION_LEVEL = 3
private const val COUNTRY_LEVEL = 2

/** Cache-key bit for local-name walks (see [adminLocality]). */
private const val LOCAL_NAME_KEY_BIT = 1L shl 62

/** Parents followed at most (a corrupt cycle must not spin). */
private const val MAX_CHAIN = 16

/** Boxes (half-size, km) polled for a boundary's addresses, the votes wanted, the records read at most. */
private val CITY_BOX_KM = doubleArrayOf(1.0, 4.0)
private const val CITY_VOTES = 25
private const val CITY_VISIT_BUDGET = 4_000
private const val KM_PER_DEGREE = 111.32
private const val E7_PER_DEGREE = 1e7
private const val MIN_COS_LAT = 1e-6

/** Per-archive cache of boundary -> locality text, bounded. */
internal class LocalityCache {
    private val map = LinkedHashMap<Long, String>(CAPACITY, LOAD, true)
    private val none = HashSet<Long>()

    /** The cached text, "" for "known to have none", or null when not cached. */
    @Synchronized
    fun get(key: Long): String? = map[key] ?: if (key in none) "" else null

    @Synchronized
    fun put(key: Long, value: String?) {
        if (value == null) {
            if (none.size >= CAPACITY) none.clear()
            none.add(key)
        } else {
            if (map.size >= CAPACITY) map.remove(map.keys.first())
            map[key] = value
        }
    }

    private companion object {
        const val CAPACITY = 512
        const val LOAD = 0.75f
    }
}

/**
 * "City, State, Country" for POI [ordinal] (state only where a level-4/3 boundary exists),
 * or null when nothing is known. [english] prefers `name:en` over the local name.
 */
internal fun PoiIndex.localityAt(ordinal: Int, english: Boolean): String? {
    val m = mappedForQuery() ?: return null
    if (ordinal !in 0 until m.count) return null
    val region = m.regionId(ordinal)
    return m.adminLocality(region, english)
        // No hierarchy (or none for this boundary): the city alone, from the POI's own
        // address or the one its boundary's other POIs agree on.
        ?: attributesAt(ordinal)?.city?.takeIf { it.isNotBlank() }
        ?: m.cachedBoundaryCity(region, ordinal)
}

/** The cached hierarchy walk for [region], or null without a table or a hit. */
private fun PoiIndex.Mapped.adminLocality(region: Long, english: Boolean): String? {
    val table = admin
    if (table == null || region == REGION_NONE) return null
    // Tagged ids stay below 2^62 (60-bit OSM ids << 2), so the three key spaces are disjoint:
    // English walks negative, local-name walks at 2^62 and up, the fallback the plain id.
    val key = if (english) region.inv() else region or LOCAL_NAME_KEY_BIT
    localityCache.get(key)?.let { cached -> return cached.ifEmpty { null } }
    return table.localityOf(region, english).also { localityCache.put(key, it) }
}

/** [boundaryCity] for [region] around POI [ordinal], cached per boundary. */
private fun PoiIndex.Mapped.cachedBoundaryCity(region: Long, ordinal: Int): String? {
    if (region == REGION_NONE) return null
    localityCache.get(region)?.let { cached -> return cached.ifEmpty { null } }
    return boundaryCity(region, latE7(ordinal), lonE7(ordinal)).also { localityCache.put(region, it) }
}

/** The hierarchy walk for boundary [tagged]; see the file comment for the level rules. */
internal fun AdminTable.localityOf(tagged: Long, english: Boolean): String? {
    var city: String? = null
    var deepest: String? = null
    var state: String? = null
    var region: String? = null
    var country: String? = null
    var at = find(tagged)
    var steps = 0
    while (at >= 0 && steps < MAX_CHAIN) {
        val level = level(at)
        val name = name(at, english)
        when {
            level == COUNTRY_LEVEL -> country = country ?: name
            level == REGION_LEVEL -> region = region ?: name
            level == STATE_LEVEL -> state = state ?: name
            level in CITY_MIN_LEVEL..CITY_MAX_LEVEL -> city = city ?: name
            level > CITY_MAX_LEVEL -> deepest = deepest ?: name
        }
        val parent = parent(at)
        at = if (parent == REGION_NONE) -1 else find(parent)
        steps++
    }
    return joinLocality(city ?: deepest, state ?: region, country)
}

/** The non-blank parts joined with ", ", consecutive repeats dropped ("Singapore, Singapore"). */
internal fun joinLocality(vararg parts: String?): String? {
    val out = ArrayList<String>()
    for (p in parts) {
        val s = p?.trim()
        if (!s.isNullOrEmpty() && out.lastOrNull() != s) out.add(s)
    }
    return out.joinToString(", ").ifEmpty { null }
}

/**
 * The city most POIs linked to boundary [tagged] around ([latE7], [lonE7]) give in their own
 * `addr:city`, or null.
 *
 * Not the boundary's place label: neighbourhood labels (place=suburb/neighbourhood) link to
 * the city boundary that contains them too, so "the label with this boundary id" near Union
 * Square is as likely to be "Castro District" as "San Francisco". The addresses of the POIs
 * around it agree on the city instead. Reads each nearby record's link (region section) and,
 * for linked ones, its attributes, under a budget; cached per boundary by the caller.
 */
private fun PoiIndex.Mapped.boundaryCity(tagged: Long, latE7: Int, lonE7: Int): String? {
    val lat = latE7 / E7_PER_DEGREE
    val lon = lonE7 / E7_PER_DEGREE
    val cosLat = Math.cos(Math.toRadians(lat)).coerceAtLeast(MIN_COS_LAT)
    val votes = HashMap<String, Int>()
    var voters = 0
    var visited = 0
    for (half in CITY_BOX_KM) {
        val dLat = half / KM_PER_DEGREE
        val dLon = half / (KM_PER_DEGREE * cosLat)
        forEachGridOrdinal(
            ((lat - dLat) * E7_PER_DEGREE).toInt(), ((lat + dLat) * E7_PER_DEGREE).toInt(),
            ((lon - dLon) * E7_PER_DEGREE).toInt(), ((lon + dLon) * E7_PER_DEGREE).toInt(),
        ) { o ->
            visited++
            if (regionId(o) == tagged) {
                PoiIndex.attributesAt(o)?.city?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    votes[it] = (votes[it] ?: 0) + 1
                    voters++
                }
            }
            voters < CITY_VOTES && visited < CITY_VISIT_BUDGET
        }
        if (voters >= CITY_VOTES || visited >= CITY_VISIT_BUDGET) break
    }
    return votes.maxByOrNull { it.value }?.key
}
