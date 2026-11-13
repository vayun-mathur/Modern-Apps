package com.vayunmathur.weather.domain.wx

import org.brotli.dec.BrotliInputStream
import java.io.ByteArrayInputStream
import java.util.zip.CRC32

/**
 * Decoders for the static weather objects on R2 (format spec and encoder:
 * location_share_server `src/handlers/weather/tile.rs`):
 * - `WXT1` tile: one run's model series for a block of ICON cells,
 * - `WXG1` geo block: point elevations + time-zone grid (static).
 * Both: `magic | u8 version | u8 flags | u32 body_len | u32 crc32 | brotli(body)`.
 */
internal object WxTiles {
    const val VERSION = 1
    const val MODEL_ICON = 0
    const val MODEL_CAMS_GLOBAL = 1
    const val MODEL_CAMS_EUROPE = 2
    private const val CODEC_LINEAR = 0
    private const val CODEC_DICT = 1
    private const val NAN_CODE = 0x7FFF
    const val GEO_PX = 64
    const val GEO_TZ = 16

    class Tile(
        val ty: Int,
        val tx: Int,
        val cells: Int,
        val genEpoch: Long,
        val iconRunIso: String,
        val iconRunMod: Long,
        val sections: List<Section>,
        val cellMeans: List<CellMeans>,
        val zones: List<Zone>,
    ) {
        fun section(model: Int): Section? = sections.firstOrNull { it.model == model }
        fun cellMean(j: Long, i: Long): Double? = cellMeans.firstNotNullOfOrNull { it.get(j, i) }
        fun zone(name: String): Zone? = zones.firstOrNull { it.name == name }
    }

    class Section(
        val model: Int,
        val ny: Int,
        val nx: Int,
        val j0: Int,
        val i0: Int,
        val nj: Int,
        val ni: Int,
        val vars: List<VarBlock>,
    ) {
        fun cell(j: Int, i: Int): Int? {
            val lj = j - j0
            val li = Math.floorMod(i - i0, nx)
            return if (lj in 0 until nj && li < ni) lj * ni + li else null
        }
        fun variable(name: String): VarBlock? = vars.firstOrNull { it.name == name }
    }

    /** One variable over hours `[h0, h0+nt)`, cell-major stored (pre-unit) values. */
    class VarBlock(val name: String, val h0: Long, val nt: Int, val values: FloatArray) {
        /** Values of cell [c] over `[hLo, hHi)`, NaN outside the stored range. */
        fun window(c: Int, hLo: Long, hHi: Long): FloatArray = FloatArray((hHi - hLo).toInt().coerceAtLeast(0)) { k ->
            val t = hLo + k - h0
            if (t in 0 until nt) values[c * nt + t.toInt()] else Float.NaN
        }
    }

    class CellMeans(val j0: Long, val i0: Long, val nj: Int, val ni: Int, val values: DoubleArray) {
        fun get(j: Long, i: Long): Double? {
            val lj = j - j0
            val li = i - i0
            if (lj !in 0 until nj || li !in 0 until ni) return null
            val v = values[(lj * ni + li).toInt()]
            return if (v.isFinite()) v else null
        }
    }

    /** UTC-offset intervals `(from, offset, abbr)`, ascending. */
    class Zone(val name: String, val from: LongArray, val offset: IntArray, val abbr: List<String>) {
        private fun idx(epoch: Long): Int {
            var lo = 0
            var hi = from.size // first index with from > epoch
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (from[mid] <= epoch) lo = mid + 1 else hi = mid
            }
            return maxOf(lo - 1, 0)
        }
        fun offsetAt(epoch: Long): Int = offset[idx(epoch)]
        fun abbrAt(epoch: Long): String = abbr[idx(epoch)]
    }

    class GeoBlock(
        val x: Int,
        val y: Int,
        val b: Int,
        val zones: List<String>,
        val tz: ByteArray,
        val elev: IntArray?,
    ) {
        /** `dem::point_elev` value at block pixel (px, py). */
        fun elevation(px: Int, py: Int): Double? {
            val c = elev?.getOrNull(py * GEO_PX + px) ?: return null
            return WxMath.fmax((c.toDouble() - 8_388_608.0) / 256.0, -500.0)
        }
        fun zone(px: Int, py: Int): String? = zones.getOrNull(tz[(py / 4) * GEO_TZ + px / 4].toInt() and 0xFF)
    }

    class FormatException(msg: String) : Exception(msg)

    private class Reader(val b: ByteArray) {
        var p = 0
        fun take(n: Int): Int {
            if (n < 0 || p + n > b.size) throw FormatException("truncated")
            val at = p
            p += n
            return at
        }
        fun u8(): Int = b[take(1)].toInt() and 0xFF
        fun u16(): Int = u8() or (u8() shl 8)
        fun u32(): Long = (u16().toLong()) or (u16().toLong() shl 16)
        fun i32(): Int = u32().toInt()
        fun i64(): Long = u32() or (u32() shl 32)
        fun f32(): Float = Float.fromBits(i32())
        fun f64(): Double = Double.fromBits(i64())
        fun str(): String {
            val n = u8()
            val at = take(n)
            return String(b, at, n, Charsets.UTF_8)
        }
    }

    @Suppress("ThrowsCount")
    private fun unframe(magic: String, bytes: ByteArray): ByteArray {
        if (bytes.size < 14 || String(bytes, 0, 4, Charsets.US_ASCII) != magic) throw FormatException("not $magic")
        if (bytes[4].toInt() != VERSION) throw FormatException("$magic version ${bytes[4]}")
        val hdr = Reader(bytes).apply { p = 6 }
        val len = hdr.u32()
        val crc = hdr.u32()
        if (len > 64L shl 20) throw FormatException("body too large")
        val body = ByteArray(len.toInt())
        BrotliInputStream(ByteArrayInputStream(bytes, 14, bytes.size - 14)).use { s ->
            var off = 0
            while (off < body.size) {
                val n = s.read(body, off, body.size - off)
                if (n < 0) throw FormatException("short body")
                off += n
            }
            if (s.read() != -1) throw FormatException("long body")
        }
        val c = CRC32().apply { update(body) }.value
        if (c != crc) throw FormatException("checksum mismatch")
        return body
    }

    /** Undo `pack_codes`: byte planes → per-cell deltas (+ previous cell) → codes. */
    private fun unpack(b: ByteArray, at: Int, ncell: Int, nt: Int): IntArray {
        val n = ncell * nt
        val r = IntArray(n) { k -> (b[at + k].toInt() and 0xFF) or ((b[at + n + k].toInt() and 0xFF) shl 8) }
        for (c in 1 until ncell) for (t in 0 until nt) r[c * nt + t] = (r[c * nt + t] + r[(c - 1) * nt + t]) and 0xFFFF
        for (c in 0 until ncell) {
            var prev = 0
            for (t in 0 until nt) {
                prev = (prev + r[c * nt + t]) and 0xFFFF
                r[c * nt + t] = prev
            }
        }
        return r
    }

    @Suppress("ThrowsCount")
    private fun readVar(r: Reader, ncell: Int): VarBlock {
        val name = r.str()
        val h0 = r.i64()
        val nt = r.u32().toInt()
        val codec = r.u8()
        val scale = r.f32()
        val offset = r.f32()
        val dict = if (codec == CODEC_DICT) FloatArray(r.u32().toInt()) { r.f32() } else FloatArray(0)
        val plen = r.u32().toInt()
        if (plen != ncell * nt * 2) throw FormatException("$name payload $plen")
        val codes = unpack(r.b, r.take(plen), ncell, nt)
        val values = when (codec) {
            CODEC_LINEAR -> FloatArray(codes.size) { k ->
                val c = codes[k]
                // `.om` decode, in float: (code as i16) / scale − offset.
                if (c == NAN_CODE) Float.NaN else c.toShort().toFloat() / scale - offset
            }
            CODEC_DICT -> FloatArray(codes.size) { k -> dict.getOrNull(codes[k]) ?: throw FormatException("dict code") }
            else -> throw FormatException("codec $codec")
        }
        return VarBlock(name, h0, nt, values)
    }

    fun decodeTile(bytes: ByteArray): Tile {
        val r = Reader(unframe("WXT1", bytes))
        val ty = r.u16()
        val tx = r.u16()
        val cells = r.u8()
        val gen = r.i64()
        val runIso = r.str()
        val runMod = r.i64()
        r.i64()
        r.i64() // CAMS run ids (debugging only)
        val sections = List(r.u8()) {
            val model = r.u8()
            val ny = r.u32().toInt()
            val nx = r.u32().toInt()
            val j0 = r.u32().toInt()
            val i0 = r.u32().toInt()
            val nj = r.u16()
            val ni = r.u16()
            val vars = List(r.u8()) { readVar(r, nj * ni) }
            Section(model, ny, nx, j0, i0, nj, ni, vars)
        }
        val means = List(r.u8()) {
            val j0 = r.i64()
            val i0 = r.i64()
            val nj = r.u16()
            val ni = r.u16()
            CellMeans(j0, i0, nj, ni, DoubleArray(nj * ni) { r.f64() })
        }
        val zones = List(r.u8()) {
            val name = r.str()
            val n = r.u16()
            val from = LongArray(n)
            val off = IntArray(n)
            val abbr = ArrayList<String>(n)
            for (k in 0 until n) {
                from[k] = r.i64()
                off[k] = r.i32()
                abbr.add(r.str())
            }
            Zone(name, from, off, abbr)
        }
        if (r.p != r.b.size) throw FormatException("trailing bytes")
        return Tile(ty, tx, cells, gen, runIso, runMod, sections, means, zones)
    }

    @Suppress("ThrowsCount")
    fun decodeGeo(bytes: ByteArray): GeoBlock {
        val r = Reader(unframe("WXG1", bytes))
        val x = r.u16()
        val y = r.u16()
        val b = r.u8()
        val zones = List(r.u8()) { r.str() }
        val tzAt = r.take(GEO_TZ * GEO_TZ)
        val tz = r.b.copyOfRange(tzAt, tzAt + GEO_TZ * GEO_TZ)
        if (tz.any { (it.toInt() and 0xFF) >= zones.size }) throw FormatException("zone index")
        val elev = if (r.u8() == 0) {
            null
        } else {
            val n = GEO_PX * GEO_PX
            val at = r.take(n * 4)
            val e = IntArray(n)
            for (k in 0 until n) {
                val d = (r.b[at + k].toInt() and 0xFF) or ((r.b[at + n + k].toInt() and 0xFF) shl 8) or
                    ((r.b[at + 2 * n + k].toInt() and 0xFF) shl 16) or ((r.b[at + 3 * n + k].toInt() and 0xFF) shl 24)
                val prev = when {
                    k % GEO_PX != 0 -> e[k - 1]
                    k >= GEO_PX -> e[k - GEO_PX]
                    else -> 0
                }
                e[k] = prev + d
            }
            e
        }
        if (r.p != r.b.size) throw FormatException("trailing bytes")
        return GeoBlock(x, y, b, zones, tz, elev)
    }
}
