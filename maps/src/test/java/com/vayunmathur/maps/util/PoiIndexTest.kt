package com.vayunmathur.maps.util

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [PoiIndex]'s spatial lookups against the linear scans they replaced.
 *
 * `PoiIndex` had no tests at all while it grew three full-file scans, which is how a POI tap
 * came to walk 22.6 M records on the main thread. The interesting risk in replacing those scans
 * is not that the new code is slow, it is that a binary search over a key the reader recomputes
 * can stop one record early and drop a POI — for some coordinates only, without failing.
 *
 * So the oracle here is the OLD implementation, transcribed verbatim below, and the assertion is
 * that the indexed lookups return exactly what it returns. That pins today's behaviour rather
 * than the author's expectations of it, including the tie-breaks.
 */
class PoiIndexTest {

    private var temp: File? = null

    @AfterTest
    fun tearDown() {
        temp?.deleteRecursively()
        temp = null
    }

    // --- fixtures ----------------------------------------------------------

    /** One POI as the fixture writer sees it, before it knows its ordinal. */
    private class Poi(val latE7: Int, val lonE7: Int, val type: Int, val name: String)

    /**
     * The side files for [pois], written to a fresh temp dir and mapped.
     *
     * Records come out sorted by unsigned Morton key, because that is the only thing about
     * `poi_index.bin` the reader is allowed to assume. [returns] the records in file order, so a
     * list index is an ordinal.
     */
    private fun load(
        pois: List<Poi>,
        attrs: Map<Int, String> = emptyMap(),
        /** Extra `(key, value)` fields per ordinal, written after the phone. */
        attrFields: Map<Int, List<Pair<Int, String>>> = emptyMap(),
        attrSlots: Int? = null,
        /** Whether to write `poi_spatial.bin` / `poi_name_index.bin` at all. */
        sideFiles: Boolean = true,
        /** Record count to claim in `poi_spatial.bin`, for the refusal case. */
        spatialRecords: Int? = null,
        /** Record count to claim in `poi_name_index.bin`, for the refusal case. */
        nameRecords: Int? = null,
        /** Bytes to lop off the end of `poi_spatial.bin`, for the truncation case. */
        spatialTruncateBy: Int = 0,
    ): List<Poi> {
        val dir = Files.createTempDirectory("poiindex").toFile()
        temp = dir

        val sorted = pois.sortedWith { a, b ->
            java.lang.Long.compareUnsigned(
                spatialFromE7(a.latE7, a.lonE7),
                spatialFromE7(b.latE7, b.lonE7),
            )
        }

        // Name pool: each unique name once, NUL-terminated, in first-seen order.
        val nameOff = LinkedHashMap<String, Int>()
        val namesOut = java.io.ByteArrayOutputStream()
        for (p in sorted) {
            if (p.name !in nameOff) {
                nameOff[p.name] = namesOut.size()
                namesOut.write(p.name.toByteArray(Charsets.UTF_8))
                namesOut.write(0)
            }
        }
        File(dir, PoiIndex.NAMES_FILE).writeBytes(namesOut.toByteArray())

        val index = ByteBuffer.allocate(sorted.size * 14).order(ByteOrder.LITTLE_ENDIAN)
        for (p in sorted) {
            index.putInt(p.latE7)
            index.putInt(p.lonE7)
            index.putInt(nameOff.getValue(p.name))
            index.putShort(p.type.toShort())
        }
        File(dir, PoiIndex.INDEX_FILE).writeBytes(index.array())

        if (attrs.isNotEmpty() || attrFields.isNotEmpty() || attrSlots != null) {
            File(dir, PoiIndex.ATTRS_FILE)
                .writeBytes(attrsFile(attrSlots ?: sorted.size, attrs, attrFields))
        }
        if (sideFiles) {
            val grid = spatialFile(sorted, spatialRecords ?: sorted.size)
            File(dir, PoiIndex.SPATIAL_FILE)
                .writeBytes(grid.copyOf(grid.size - spatialTruncateBy))
            File(dir, PoiIndex.NAME_INDEX_FILE)
                .writeBytes(nameIndexFile(sorted, nameRecords ?: sorted.size))
        }

        assertTrue(PoiIndex.reload(dir), "fixture did not map")
        return sorted
    }

    /** Cell size the writer uses; see `CELL_E7` in `osm_ingest/src/poi_side.rs`. */
    private val cellE7 = 200_000

    private fun axis(value: Int, origin: Int): Int {
        val d = value.toLong() - origin.toLong()
        return if (d <= 0) 0 else (d / cellE7).toInt()
    }

    /**
     * A `poi_spatial.bin` for [pois], built the way `poi_side.rs` builds it.
     *
     * Written independently of the Rust writer on purpose: if the two drift, the reader
     * stops agreeing with one of them, and these tests are the only thing on this side of
     * the language boundary that would notice.
     */
    private fun spatialFile(pois: List<Poi>, records: Int): ByteArray {
        val lat0 = pois.minOfOrNull { it.latE7 } ?: 0
        val lon0 = pois.minOfOrNull { it.lonE7 } ?: 0
        val cols = if (pois.isEmpty()) 0 else axis(pois.maxOf { it.lonE7 }, lon0) + 1
        val pairs = pois.mapIndexed { ordinal, p ->
            axis(p.latE7, lat0) * cols + axis(p.lonE7, lon0).coerceAtMost(cols - 1) to ordinal
        }.sortedWith(compareBy({ it.first }, { it.second }))

        val cellIds = ArrayList<Int>()
        val cellOff = ArrayList<Int>()
        pairs.forEachIndexed { i, (cell, _) ->
            if (cellIds.lastOrNull() != cell) {
                cellIds.add(cell)
                cellOff.add(i)
            }
        }
        cellOff.add(pairs.size)

        val buf = ByteBuffer
            .allocate(32 + 4 * cellIds.size + 4 * cellOff.size + 4 * pairs.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.put("PSP1".toByteArray(Charsets.US_ASCII))
        buf.putInt(1)
        buf.putInt(records)
        buf.putInt(cellIds.size)
        buf.putInt(lat0)
        buf.putInt(lon0)
        buf.putInt(cellE7)
        buf.putInt(cols)
        cellIds.forEach { buf.putInt(it) }
        cellOff.forEach { buf.putInt(it) }
        pairs.forEach { buf.putInt(it.second) }
        return buf.array()
    }

    /** The whitespace-separated words of a name, as `poi_side.rs` splits them. */
    private fun words(name: String): List<String> =
        name.split(' ', '\t', '\n', '\r', '\u000B', '\u000C').filter { it.isNotEmpty() }

    /**
     * A `poi_name_index.bin` for [pois]: one entry per (record, word), sorted by the word.
     *
     * Fixture names are ASCII, so `lowercase()` here matches the writer's ASCII-only fold
     * and Kotlin's string order matches its byte order. A non-ASCII fixture would need the
     * byte-wise comparison the reader does.
     */
    private fun nameIndexFile(pois: List<Poi>, records: Int): ByteArray {
        val entries = ArrayList<Pair<Int, Int>>()
        pois.forEachIndexed { ordinal, p ->
            words(p.name).indices.take(255).forEach { wordIdx -> entries.add(ordinal to wordIdx) }
        }
        entries.sortWith(
            compareBy(
                { words(pois[it.first].name)[it.second].lowercase() },
                { it.first },
                { it.second },
            )
        )
        val buf = ByteBuffer.allocate(16 + 5 * entries.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("PNI1".toByteArray(Charsets.US_ASCII))
        buf.putInt(1)
        buf.putInt(records)
        buf.putInt(entries.size)
        entries.forEach { buf.putInt(it.first) }
        entries.forEach { buf.put(it.second.toByte()) }
        return buf.array()
    }

    /**
     * A `poi_attrs.bin` carrying one phone number per entry of [phones], keyed by ordinal.
     *
     * [slots] is separate from `phones.size` so a test can write a sidecar whose record count
     * disagrees with the index — the mismatch the reader has to refuse, since the join is
     * positional and a stale sidecar would hand every place someone else's phone number.
     */
    private fun attrsFile(
        slots: Int,
        phones: Map<Int, String>,
        extra: Map<Int, List<Pair<Int, String>>> = emptyMap(),
    ): ByteArray {
        val blob = java.io.ByteArrayOutputStream()
        val offsets = IntArray(slots) { -1 }
        for (ordinal in 0 until slots) {
            val fields = listOfNotNull(phones[ordinal]?.let { 2 to it }) + extra[ordinal].orEmpty() // 2 = KEY_PHONE
            if (fields.isEmpty()) continue
            offsets[ordinal] = blob.size()
            val encoded = fields.map { (key, v) -> key to v.toByteArray(Charsets.UTF_8) }
            val body = ByteBuffer.allocate(encoded.sumOf { 3 + it.second.size }).order(ByteOrder.LITTLE_ENDIAN)
            for ((key, value) in encoded) {
                body.put(key.toByte())
                body.putShort(value.size.toShort())
                body.put(value)
            }
            blob.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(body.capacity().toShort()).array())
            blob.write(body.array())
        }
        val out = ByteBuffer.allocate(12 + 4 * slots + blob.size()).order(ByteOrder.LITTLE_ENDIAN)
        out.put("MAPA".toByteArray(Charsets.US_ASCII))
        out.put(1)          // version
        out.put(0); out.put(0); out.put(0)
        out.putInt(slots)
        for (o in offsets) out.putInt(o)
        out.put(blob.toByteArray())
        return out.array()
    }

    /** A spread of POIs around ([lat],[lon]), plus a few far away to catch an unbounded walk. */
    private fun scatter(rng: Random, n: Int, lat: Double, lon: Double, spanDeg: Double): List<Poi> =
        List(n) { i ->
            Poi(
                latE7 = ((lat + rng.nextDouble(-spanDeg, spanDeg)) * 1e7).toInt(),
                lonE7 = ((lon + rng.nextDouble(-spanDeg, spanDeg)) * 1e7).toInt(),
                type = i % 40,
                // Deliberately not unique: shared names are the whole point of the name pool,
                // and attributesNear matches on name as well as distance.
                name = listOf("Cafe Roma", "Blue Bottle", "Safeway", "Kaiser", "Cafe Roma")[i % 5],
            )
        }

    // --- the oracle: the implementations these replaced ---------------------

    /** `nearest` before the Morton bound: a walk of every record. */
    private fun refNearest(
        recs: List<Poi>,
        lat: Double,
        lon: Double,
        limit: Int,
        maxMeters: Double,
    ): List<PoiIndex.PoiRecord> {
        val dLat = maxMeters / 111_320.0
        val cosLat = Math.cos(Math.toRadians(lat)).coerceAtLeast(1e-6)
        val dLon = maxMeters / (111_320.0 * cosLat)
        val minLatE7 = ((lat - dLat) * 1e7).toInt()
        val maxLatE7 = ((lat + dLat) * 1e7).toInt()
        val minLonE7 = ((lon - dLon) * 1e7).toInt()
        val maxLonE7 = ((lon + dLon) * 1e7).toInt()
        val hits = ArrayList<Pair<Int, Double>>()
        for (i in recs.indices) {
            val r = recs[i]
            if (r.latE7 in minLatE7..maxLatE7 && r.lonE7 in minLonE7..maxLonE7) {
                hits.add(i to distanceSq(r.latE7 / 1e7, r.lonE7 / 1e7, lat, lon))
            }
        }
        // sortedBy is stable, as ArrayList.sortBy was, so equal distances keep file order.
        return hits.sortedBy { it.second }.take(limit).map { (i, _) -> recs[i].asRecord(i) }
    }

    /** `inViewport` before the Morton bound: a walk of every record. */
    private fun refInViewport(
        recs: List<Poi>,
        west: Double,
        south: Double,
        east: Double,
        north: Double,
        cap: Int,
    ): List<PoiIndex.PoiRecord> {
        val minLatE7 = (south * 1e7).toInt()
        val maxLatE7 = (north * 1e7).toInt()
        val minLonE7 = (west * 1e7).toInt()
        val maxLonE7 = (east * 1e7).toInt()
        val out = ArrayList<PoiIndex.PoiRecord>()
        var i = 0
        while (i < recs.size && out.size < cap) {
            val r = recs[i]
            if (r.latE7 in minLatE7..maxLatE7 && r.lonE7 in minLonE7..maxLonE7) out.add(r.asRecord(i))
            i++
        }
        return out
    }

    private fun Poi.asRecord(ordinal: Int) = PoiIndex.PoiRecord(latE7, lonE7, type, name, ordinal)

    private fun distanceSq(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val cosLat = Math.cos(Math.toRadians((aLat + bLat) / 2.0))
        val dLat = aLat - bLat
        val dLon = (aLon - bLon) * cosLat
        return dLat * dLat + dLon * dLon
    }

    // --- equivalence -------------------------------------------------------

    /**
     * Both lookup paths must agree with the original scan, so every equivalence test runs
     * twice: once with the side files present (cell-local grid) and once without (Morton
     * span walk). A bug in either would otherwise hide behind the other.
     */
    private fun bothPaths(body: (sideFiles: Boolean) -> Unit) {
        for (sideFiles in listOf(true, false)) {
            body(sideFiles)
        }
    }

    @Test
    fun `nearest returns exactly what the full scan returned`() = bothPaths { sideFiles ->
        val rng = Random(1)
        val recs = load(scatter(rng, 4_000, 37.7749, -122.4194, 0.35), sideFiles = sideFiles)
        assertEquals(4_000, PoiIndex.recordCount)
        repeat(200) {
            val lat = 37.7749 + rng.nextDouble(-0.35, 0.35)
            val lon = -122.4194 + rng.nextDouble(-0.35, 0.35)
            val meters = rng.nextDouble(10.0, 2_000.0)
            val limit = rng.nextInt(1, 25)
            assertEquals(
                refNearest(recs, lat, lon, limit, meters),
                PoiIndex.nearest(lat, lon, limit = limit, maxMeters = meters),
                "nearest($lat, $lon, limit=$limit, maxMeters=$meters) sideFiles=$sideFiles",
            )
        }
    }

    @Test
    fun `inViewport returns exactly what the full scan returned, cap included`() =
        bothPaths { sideFiles ->
            val rng = Random(2)
            val recs = load(scatter(rng, 4_000, 51.5074, -0.1278, 0.4), sideFiles = sideFiles)
            repeat(200) {
                val lat = 51.5074 + rng.nextDouble(-0.4, 0.4)
                val lon = -0.1278 + rng.nextDouble(-0.4, 0.4)
                val h = rng.nextDouble(0.001, 0.3)
                val w = rng.nextDouble(0.001, 0.3)
                val cap = rng.nextInt(1, 60)
                assertEquals(
                    refInViewport(recs, lon - w, lat - h, lon + w, lat + h, cap),
                    PoiIndex.inViewport(lon - w, lat - h, lon + w, lat + h, cap = cap),
                    "inViewport around ($lat, $lon) +-($h, $w) cap=$cap sideFiles=$sideFiles",
                )
            }
        }

    /**
     * Z-order's discontinuity is a *performance* caveat, not a correctness one, and this is the
     * difference being asserted. A box containing the origin spans nearly the whole key space,
     * so the walk degenerates — but it must still return every record, which is exactly what
     * would break if the interval were computed from anything other than the two extreme corners.
     */
    @Test
    fun `a bbox straddling the equator and the prime meridian still finds everything`() {
        val rng = Random(3)
        val recs = load(scatter(rng, 2_000, 0.0, 0.0, 1.5))
        assertEquals(
            refInViewport(recs, -1.0, -1.0, 1.0, 1.0, 5_000),
            PoiIndex.inViewport(-1.0, -1.0, 1.0, 1.0, cap = 5_000),
        )
        assertEquals(
            refNearest(recs, 0.0, 0.0, 50, 50_000.0),
            PoiIndex.nearest(0.0, 0.0, limit = 50, maxMeters = 50_000.0),
        )
    }

    @Test
    fun `records outside the box are never returned however far the walk goes`() {
        val recs = load(
            listOf(
                Poi(377_749_000, -1_224_194_000, 1, "Near"),
                Poi(377_749_100, -1_224_194_100, 2, "Near"),
                Poi(-338_688_000, 1_512_093_000, 3, "Sydney"),
                Poi(515_074_000, -1_278_000, 4, "London"),
                Poi(356_762_000, 1_396_503_000, 5, "Tokyo"),
            )
        )
        val got = PoiIndex.nearest(37.7749, -122.4194, limit = 20, maxMeters = 100.0)
        assertEquals(listOf("Near", "Near"), got.map { it.name })
        assertEquals(refNearest(recs, 37.7749, -122.4194, 20, 100.0), got)
    }

    @Test
    fun `an empty index answers every query with an empty list`() {
        load(emptyList())
        assertEquals(0, PoiIndex.recordCount)
        assertTrue(PoiIndex.nearest(37.7749, -122.4194).isEmpty())
        assertTrue(PoiIndex.inViewport(-123.0, 37.0, -122.0, 38.0).isEmpty())
        assertTrue(PoiIndex.searchByName("cafe", 37.7749, -122.4194).isEmpty())
    }

    // --- names and attributes ---------------------------------------------

    @Test
    fun `searchByName ranks prefix matches before substring matches`() {
        load(
            listOf(
                Poi(377_749_000, -1_224_194_000, 1, "Blue Bottle"),
                Poi(377_749_100, -1_224_194_100, 2, "The Blue Door"),
            )
        )
        val got = PoiIndex.searchByName("blue", 37.7749, -122.4194)
        assertEquals(listOf("Blue Bottle", "The Blue Door"), got.map { it.name })
    }

    @Test
    fun `attributesNear joins on name as well as distance`() {
        // Two POIs a metre apart, as a cafe inside a mall is. Attaching the cafe's phone number
        // to the mall is the kind of wrong that looks right, so the name has to decide.
        val recs = load(
            listOf(
                Poi(377_749_000, -1_224_194_000, 1, "Mall"),
                Poi(377_749_050, -1_224_194_000, 2, "Cafe"),
            ),
            attrs = emptyMap(),
        )
        val mall = recs.indexOfFirst { it.name == "Mall" }
        val cafe = recs.indexOfFirst { it.name == "Cafe" }
        load(recs, attrs = mapOf(mall to "+1-555-MALL", cafe to "+1-555-CAFE"))

        assertTrue(PoiIndex.attributesAvailable)
        assertEquals(
            "+1-555-CAFE",
            PoiIndex.attributesNear(37.7749, -122.4194, "Cafe")?.phone,
        )
        assertEquals(
            "+1-555-MALL",
            PoiIndex.attributesNear(37.7749, -122.4194, "Mall")?.phone,
        )
        // The tile and the sidecar disagree on the name entirely: no exact or
        // normalised match, so the nearest POI within 25 m that carries
        // attributes wins instead of an empty sheet. Here that is the Mall
        // itself, which sits exactly on the query point.
        assertEquals(
            "+1-555-MALL",
            PoiIndex.attributesNear(37.7749, -122.4194, "Nowhere")?.phone,
        )
    }

    @Test
    fun `attributesNear matches names ignoring case and punctuation`() {
        val recs = load(
            listOf(
                Poi(377_749_000, -1_224_194_000, 1, "McDonald's"),
            ),
            attrs = emptyMap(),
        )
        load(recs, attrs = mapOf(0 to "+1-555-BIGMAC"))

        assertEquals(
            "+1-555-BIGMAC",
            PoiIndex.attributesNear(37.7749, -122.4194, "McDonalds")?.phone,
        )
        assertEquals(
            "+1-555-BIGMAC",
            PoiIndex.attributesNear(37.7749, -122.4194, "MCDONALD'S")?.phone,
        )
    }

    @Test
    fun `review fields decode beside the OSM tags and on their own`() {
        val recs = load(
            listOf(
                Poi(377_749_000, -1_224_194_000, 1, "Diner"),
                Poi(377_759_000, -1_224_194_000, 1, "Bakery"),
            ),
            attrs = emptyMap(),
        )
        val diner = recs.indexOfFirst { it.name == "Diner" }
        val bakery = recs.indexOfFirst { it.name == "Bakery" }
        load(
            recs,
            attrs = mapOf(diner to "+1-555-DINER"),
            attrFields = mapOf(
                diner to listOf(KEY_RATING to "4.3", KEY_REVIEW_COUNT to "1234", KEY_REVIEW_SUMMARY to "Huge portions."),
                // Out-of-range values are dropped, not shown; the summary still stands alone.
                bakery to listOf(KEY_RATING to "9.9", KEY_REVIEW_COUNT to "-4", KEY_REVIEW_SUMMARY to "Fresh croissants."),
            ),
        )

        val d = PoiIndex.attributesNear(37.7749, -122.4194, "Diner")
        assertEquals("+1-555-DINER", d?.phone)
        assertEquals(4.3, d?.rating)
        assertEquals(1234, d?.reviewCount)
        assertEquals("Huge portions.", d?.reviewSummary)

        val b = PoiIndex.attributesNear(37.7759, -122.4194, "Bakery")
        assertNull(b?.rating)
        assertNull(b?.reviewCount)
        assertEquals("Fresh croissants.", b?.reviewSummary)
        assertFalse(b?.isEmpty ?: true, "a summary alone is a record")
    }

    @Test
    fun `attributesNear returns null when nothing nearby carries attributes`() {
        load(
            listOf(
                Poi(377_749_000, -1_224_194_000, 1, "Mall"),
            ),
            attrs = emptyMap(),
        )
        assertNull(PoiIndex.attributesNear(37.7749, -122.4194, "Nowhere"))
    }

    @Test
    fun `a sidecar whose record count disagrees with the index is refused`() {
        load(
            listOf(
                Poi(377_749_000, -1_224_194_000, 1, "Mall"),
                Poi(377_749_050, -1_224_194_000, 2, "Cafe"),
            ),
            attrs = mapOf(0 to "+1-555-WRONG"),
            attrSlots = 7,
        )
        assertTrue(PoiIndex.available, "the index itself stays usable")
        assertFalse(PoiIndex.attributesAvailable)
        assertNull(PoiIndex.attributesNear(37.7749, -122.4194, "Cafe"))
    }

    @Test
    fun `an absent sidecar leaves the index working with no attributes`() {
        load(listOf(Poi(377_749_000, -1_224_194_000, 1, "Mall")))
        assertTrue(PoiIndex.available)
        assertFalse(PoiIndex.attributesAvailable)
        assertEquals(1, PoiIndex.nearest(37.7749, -122.4194, maxMeters = 50.0).size)
    }

    @Test
    fun `a missing index file leaves every query a no-op`() {
        val dir = Files.createTempDirectory("poiindex-empty").toFile()
        temp = dir
        assertFalse(PoiIndex.reload(dir))
        assertFalse(PoiIndex.available)
        assertEquals(0, PoiIndex.recordCount)
        assertTrue(PoiIndex.nearest(37.7749, -122.4194).isEmpty())
    }

    // --- word index --------------------------------------------------------

    /** The point of indexing every word rather than just the name's first. */
    @Test
    fun `a word in the middle of a name is findable`() {
        load(
            listOf(
                Poi(377_749_000, -1_224_194_000, 1, "Joe's Pizza"),
                Poi(377_749_100, -1_224_194_100, 2, "Pizza Hut"),
            )
        )
        val got = PoiIndex.searchByName("pizza", 37.7749, -122.4194).map { it.name }
        assertEquals(setOf("Joe's Pizza", "Pizza Hut"), got.toSet())
    }

    /** A first-word match is the indexed equivalent of the scan's "starts with". */
    @Test
    fun `a first-word match outranks a later-word match`() {
        load(
            listOf(
                // Deliberately the farther of the two, so only the rank can order them.
                Poi(377_760_000, -1_224_194_000, 1, "Pizza Hut"),
                Poi(377_749_000, -1_224_194_000, 2, "Joe's Pizza"),
            )
        )
        val got = PoiIndex.searchByName("pizza", 37.7749, -122.4194).map { it.name }
        assertEquals(listOf("Pizza Hut", "Joe's Pizza"), got)
    }

    @Test
    fun `a word prefix matches, and an unrelated word does not`() {
        load(listOf(Poi(377_749_000, -1_224_194_000, 1, "Blue Bottle Coffee")))
        for (q in listOf("blue", "bot", "coffee", "BOTTLE")) {
            assertEquals(
                1,
                PoiIndex.searchByName(q, 37.7749, -122.4194).size,
                "\"$q\" should match",
            )
        }
        assertTrue(PoiIndex.searchByName("tearoom", 37.7749, -122.4194).isEmpty())
    }

    /**
     * The documented narrowing: a sorted index answers prefix questions, so a match in the
     * middle of a *word* is lost where the scan found it. Asserted rather than left to be
     * discovered, since it is the one thing the two paths disagree about.
     */
    @Test
    fun `a mid-word match is found by the scan but not by the index`() {
        val pois = listOf(Poi(377_749_000, -1_224_194_000, 1, "Pizza"))
        load(pois, sideFiles = false)
        assertEquals(1, PoiIndex.searchByName("izza", 37.7749, -122.4194).size)
        load(pois, sideFiles = true)
        assertTrue(PoiIndex.searchByName("izza", 37.7749, -122.4194).isEmpty())
    }

    @Test
    fun `a blank query matches nothing on either path`() = bothPaths { sideFiles ->
        load(listOf(Poi(377_749_000, -1_224_194_000, 1, "Cafe")), sideFiles = sideFiles)
        assertTrue(PoiIndex.searchByName("   ", 37.7749, -122.4194).isEmpty())
    }

    // --- side-file refusal -------------------------------------------------

    /**
     * The grid stores ordinals, so one built against a different `poi_index.bin` would
     * return the wrong places. It must be refused and the Morton walk used instead — the
     * results still have to be right, which is what makes refusing safe.
     */
    @Test
    fun `a spatial grid whose record count disagrees with the index is refused`() {
        val rng = Random(11)
        val recs = load(scatter(rng, 200, 37.7749, -122.4194, 0.05), spatialRecords = 199)
        assertTrue(PoiIndex.available)
        assertEquals(
            refInViewport(recs, -122.47, 37.72, -122.37, 37.82, 500),
            PoiIndex.inViewport(-122.47, 37.72, -122.37, 37.82, cap = 500),
            "a refused grid must fall back, not return less",
        )
    }

    @Test
    fun `a name index whose record count disagrees with the index is refused`() {
        load(
            listOf(Poi(377_749_000, -1_224_194_000, 1, "Joe's Pizza")),
            nameRecords = 7,
        )
        // Refused, so the scan answers — and the scan still matches mid-word.
        assertEquals(1, PoiIndex.searchByName("izza", 37.7749, -122.4194).size)
    }

    @Test
    fun `absent side files leave the index working on the Morton path`() {
        load(listOf(Poi(377_749_000, -1_224_194_000, 1, "Mall")), sideFiles = false)
        assertTrue(PoiIndex.available)
        assertEquals(1, PoiIndex.nearest(37.7749, -122.4194, maxMeters = 50.0).size)
        assertEquals(1, PoiIndex.searchByName("mall", 37.7749, -122.4194).size)
    }

    @Test
    fun `a truncated spatial grid is refused rather than read past its end`() {
        val recs = load(scatter(Random(12), 50, 37.7749, -122.4194, 0.02), spatialTruncateBy = 8)
        assertTrue(PoiIndex.available)
        assertEquals(
            refInViewport(recs, -122.44, 37.75, -122.40, 37.79, 500),
            PoiIndex.inViewport(-122.44, 37.75, -122.40, 37.79, cap = 500),
        )
    }
}
