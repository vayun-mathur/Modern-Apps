package com.vayunmathur.weather.domain.wx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Behaviour of the tile/pack value types (no brotli fixtures needed). */
class WxTilesPackTest {

    private fun zone() = WxTiles.Zone(
        "Europe/Berlin",
        longArrayOf(0L, 1000L, 2000L),
        intArrayOf(3600, 7200, 3600),
        listOf("CET", "CEST", "CET"),
    )

    @Test fun zoneOffsetBinarySearch() {
        val z = zone()
        assertEquals(3600, z.offsetAt(-5)) // before the first interval: first entry
        assertEquals(3600, z.offsetAt(0))
        assertEquals(3600, z.offsetAt(999))
        assertEquals(7200, z.offsetAt(1000))
        assertEquals(7200, z.offsetAt(1999))
        assertEquals(3600, z.offsetAt(2000))
        assertEquals(3600, z.offsetAt(Long.MAX_VALUE / 2))
        assertEquals("CEST", z.abbrAt(1500))
        assertEquals("CET", z.abbrAt(2500))
        assertEquals("CET", z.abbrAt(-100))
    }

    @Test fun localDayUsesZoneOffset() {
        val z = zone()
        // Epoch 0 at +1h is 1970-01-01 01:00 local.
        assertEquals("1970-01-01", WxCompute.localDay(z, 0L))
        // UTC zone: epoch -1 is still 1969-12-31.
        val utc = WxTiles.Zone("UTC", longArrayOf(0L), intArrayOf(0), listOf("UTC"))
        assertEquals("1969-12-31", WxCompute.localDay(utc, -1L))
        assertEquals("1970-01-01", WxCompute.localDay(utc, 0L))
    }

    @Test fun varBlockWindowPadsNaN() {
        val vb = WxTiles.VarBlock("temperature_2m", h0 = 100L, nt = 3, values = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f))
        // Cell 1 over [99, 103): one NaN pad, then 4,5, then NaN.
        val w = vb.window(1, 99L, 103L)
        assertEquals(4, w.size)
        assertTrue(w[0].isNaN()); assertEquals(4f, w[1]); assertEquals(5f, w[2]); assertEquals(6f, w[3])
        // Empty/inverted window.
        assertEquals(0, vb.window(0, 100L, 100L).size)
        assertEquals(0, vb.window(0, 102L, 100L).size)
        // Fully inside.
        assertTrue(vb.window(0, 100L, 103L).contentEquals(floatArrayOf(1f, 2f, 3f)))
    }

    @Test fun sectionCellWrapsLongitude() {
        val sec = WxTiles.Section(
            model = 0, ny = 10, nx = 360, j0 = 2, i0 = 350, nj = 4, ni = 20, vars = emptyList(),
        )
        assertEquals(0, sec.cell(2, 350))
        assertEquals(19, sec.cell(2, 369))
        assertEquals(0, sec.cell(2, 710)) // wraps past nx
        assertNull(sec.cell(1, 350)) // above j0
        assertNull(sec.cell(6, 350)) // below j0+nj
        assertNull(sec.variable("nope"))
    }

    @Test fun cellMeansBounds() {
        val cm = WxTiles.CellMeans(100L, 200L, 2, 3, doubleArrayOf(1.0, 2.0, Double.NaN, 4.0, 5.0, 6.0))
        assertEquals(1.0, cm.get(100L, 200L))
        assertEquals(6.0, cm.get(101L, 202L))
        assertNull(cm.get(100L, 202L)) // NaN stored -> null
        assertNull(cm.get(99L, 200L))
        assertNull(cm.get(100L, 203L))
        assertNull(cm.get(102L, 200L))
    }

    @Test fun geoBlockElevationAndZone() {
        // Elevation int encodes (m + offset): stored = (elev*256 + 8388608) accumulated.
        // GeoBlock stores cumulative ints; elevation(px,py) = (c - 8388608)/256 floored at -500.
        val elev = IntArray(64 * 64) { 8_388_608 + 256 * 10 } // 10 m everywhere
        val gb = WxTiles.GeoBlock(1, 2, 3, listOf("UTC", "Europe/Berlin"), ByteArray(256) { 1 }, elev)
        assertEquals(10.0, gb.elevation(0, 0))
        assertEquals(10.0, gb.elevation(63, 63))
        assertNull(WxTiles.GeoBlock(0, 0, 0, listOf("UTC"), ByteArray(256), null).elevation(0, 0))
        assertEquals("Europe/Berlin", gb.zone(0, 0))
        assertEquals("Europe/Berlin", gb.zone(63, 63))
        assertNull(WxTiles.GeoBlock(0, 0, 0, listOf("UTC"), ByteArray(256), null).zone(0, 0).let {
            // tz index 0 -> "UTC" exists, so not null; use an out-of-range index instead
            WxTiles.GeoBlock(0, 0, 0, listOf("UTC"), ByteArray(256) { 5 }, null).zone(0, 0)
        })
        // Below-sea-level floor: stored 0 -> (0-8388608)/256 is very negative -> -500.
        val deep = WxTiles.GeoBlock(0, 0, 0, listOf("UTC"), ByteArray(256), IntArray(64 * 64) { 0 })
        assertEquals(-500.0, deep.elevation(5, 5))
    }

    @Test fun spanPacking() {
        // Span packs offset in the high bits, length in the low 24.
        val s = WxPack.span((12345L shl 24) or 678L)
        assertEquals(12345L, s.off); assertEquals(678, s.len)
        assertTrue(!s.isEmpty)
        assertEquals("bytes=12345-13022", s.range())
        assertTrue(WxPack.span(0L).isEmpty)
        assertEquals(64L, WxPack.runEntryOffset(0, 0))
        assertEquals(64L, WxPack.histEntryOffset(0, 0)) // 8-byte spans start right after the header
        assertEquals(64L + 8L, WxPack.histEntryOffset(0, 1))
        assertTrue(WxPack.runEntryOffset(1, 0) > WxPack.runEntryOffset(0, 359))
    }

    @Test fun runEntryDecodeAndHistSpan() {
        val b = ByteArray(WxPack.RUN_ENTRY_LEN)
        fun put64(off: Int, v: Long) { for (k in 0..7) b[off + k] = (v shr (8 * k)).toByte() }
        fun put32(off: Int, v: Int) { for (k in 0..3) b[off + k] = (v shr (8 * k)).toByte() }
        fun put16(off: Int, v: Int) { for (k in 0..1) b[off + k] = (v shr (8 * k)).toByte() }
        put64(0, 42L); put64(8, 999L); put32(16, 100); put32(20, 103); put16(24, 7); put16(26, 9)
        put64(32, (5000L shl 24) or 120L)
        put64(40, (6000L shl 24) or 50L) // hist day 100
        // day 101 left empty
        put64(56, (7000L shl 24) or 60L) // hist day 102
        val e = WxPack.decodeRunEntry(b)
        assertEquals(42L, e.gen); assertEquals(999L, e.nextCheck)
        assertEquals(7, e.ty); assertEquals(9, e.tx)
        assertEquals(5000L, e.tile.off); assertEquals(120, e.tile.len)
        assertEquals(6000L, e.histSpan(100L)!!.off)
        assertNull(e.histSpan(101L)) // empty span
        assertEquals(7000L, e.histSpan(102L)!!.off)
        assertNull(e.histSpan(99L)) // before range
        assertNull(e.histSpan(103L)) // at histToDay (exclusive)
        assertFailsWith<IllegalArgumentException> { WxPack.decodeRunEntry(ByteArray(10)) }
    }

    @Test fun headerEntryOffset() {
        val h = WxPack.Header(kind = 3, entryLen = 8, ky0 = 10, kx0 = 20, nky = 2, nkx = 3)
        assertEquals(64L, h.entryOffset(10, 20))
        assertEquals(64L + 8L, h.entryOffset(10, 21))
        assertEquals(64L + 3 * 8L, h.entryOffset(11, 20))
        assertNull(h.entryOffset(9, 20))
        assertNull(h.entryOffset(12, 20))
        assertNull(h.entryOffset(10, 23))
    }

    @Test fun decodeHeaderRejectsBadPacks() {
        assertFailsWith<IllegalArgumentException> { WxPack.decodeHeader(ByteArray(64)) }
        val b = ByteArray(64)
        "WXP1".toByteArray().copyInto(b)
        b[4] = 9 // bad version
        assertFailsWith<IllegalArgumentException> { WxPack.decodeHeader(b) }
        b[4] = 1; b[5] = WxPack.KIND_GEO.toByte()
        val h = WxPack.decodeHeader(b)
        assertEquals(WxPack.KIND_GEO, h.kind)
    }

    @Test fun tileDecodersRejectGarbage() {
        assertFailsWith<WxTiles.FormatException> { WxTiles.decodeTile(byteArrayOf(1, 2, 3)) }
        assertFailsWith<WxTiles.FormatException> { WxTiles.decodeGeo(byteArrayOf(1, 2, 3)) }
        // Right magic, wrong version.
        val bad = "WXT1".toByteArray() + byteArrayOf(9, 0) + ByteArray(20)
        assertFailsWith<WxTiles.FormatException> { WxTiles.decodeTile(bad) }
    }

    @Test fun histDaysNeededRange() {
        // now is in window terms; runH0 at day boundary.
        val now = 20770L * 86400 + 3600
        val days = WxGrid.histDaysNeeded(now, 20770L * 24)
        assertTrue(days.first <= days.last)
        assertTrue(days.all { it < 20770L })
    }
}
