package com.vayunmathur.networklocation

import java.io.ByteArrayOutputStream
import java.io.File

/** Bit writer matching the Rust WPSDB2 fixture writer (LSB-first within each byte). */
internal class BitWriter {
    private val backing = mutableListOf<Byte>()
    var bits: Long = 0
        private set

    fun push(v: Long, nbits: Int) {
        for (k in 0 until nbits) {
            while (backing.size <= (bits / 8).toInt()) backing.add(0)
            if (((v ushr k) and 1L) == 1L) {
                val idx = (bits / 8).toInt()
                backing[idx] = (backing[idx].toInt() or (1 shl (bits % 8).toInt())).toByte()
            }
            bits++
        }
    }

    fun pushBit(set: Boolean) = push(if (set) 1L else 0L, 1)

    fun bytes(): ByteArray = ByteArray(backing.size) { backing[it] }
}

internal data class WpsRec(
    val keyHi: Long,
    val keyLo: Long,
    val latE8: Long,
    val lonE8: Long,
    val acc: Long,
    val altMeters: Long?,
)

private fun wpsKeyOf(r: WpsRec) = java.math.BigInteger.valueOf(r.keyHi).shiftLeft(64)
    .add(
        java.math.BigInteger.valueOf(r.keyLo)
            .and(java.math.BigInteger.ONE.shiftLeft(64).subtract(java.math.BigInteger.ONE)),
    )

private fun wpsChooseL(universeBits: Int, n: Int): Int {
    if (n == 0) return minOf(universeBits, 64)
    var cand = 0
    val u = java.math.BigInteger.ONE.shiftLeft(universeBits)
    while (cand < 64 && u.shiftRight(cand + 1) >= java.math.BigInteger.valueOf(n.toLong())) cand++
    return cand
}

private fun wpsHigh(sorted: List<WpsRec>, l: Int, universeBits: Int): BitWriter {
    val high = BitWriter()
    var prevBucket = java.math.BigInteger.ZERO
    for (r in sorted) {
        val bucket = wpsKeyOf(r).shiftRight(l)
        var b = prevBucket
        while (b < bucket) {
            high.pushBit(false)
            b = b.add(java.math.BigInteger.ONE)
        }
        high.pushBit(true)
        prevBucket = bucket
    }
    val buckets = java.math.BigInteger.ONE.shiftLeft(universeBits - l)
    var b = prevBucket
    while (b < buckets) {
        high.pushBit(false)
        b = b.add(java.math.BigInteger.ONE)
    }
    return high
}

private fun wpsZeros(highBytes: ByteArray, highLenBits: Long, sampleLog2: Int): ByteArray {
    val zeros = mutableListOf(0L)
    var count = 0L
    for (p in 0 until highLenBits) {
        val byte = highBytes[(p / 8).toInt()].toInt()
        if (((byte ushr (p % 8).toInt()) and 1) == 0) count++
        if ((p + 1) and ((1L shl sampleLog2) - 1) == 0L) zeros.add(count)
    }
    val out = ByteArrayOutputStream()
    for (z in zeros) for (i in 0 until 8) out.write((z ushr (8 * i)).toInt() and 0xFF)
    return out.toByteArray()
}

private fun wpsLow(sorted: List<WpsRec>, l: Int): ByteArray {
    val low = BitWriter()
    if (l > 0) {
        val mask = java.math.BigInteger.ONE.shiftLeft(l).subtract(java.math.BigInteger.ONE)
        for (r in sorted) low.push(wpsKeyOf(r).and(mask).toLong(), l)
    }
    return low.bytes()
}

private fun wpsPayload(sorted: List<WpsRec>): ByteArray {
    val payload = BitWriter()
    for (r in sorted) {
        payload.push(r.latE8 + 9_000_000_000L, 35)
        payload.push(r.lonE8 + 18_000_000_000L, 36)
        payload.push(r.acc, 16)
        payload.push((r.altMeters ?: -32768L) + 32768L, 16)
    }
    return payload.bytes()
}

private fun ByteArrayOutputStream.u64le(v: Long) {
    for (i in 0 until 8) write((v ushr (8 * i)).toInt() and 0xFF)
}

/** Writes a valid WPSDB2 store (payload kind 2) holding [records], mirroring wpsdb_part2.rs. */
internal fun writeWpsStore(file: File, universeBits: Int, records: List<WpsRec>) {
    val sorted = records.sortedWith(compareBy({ it.keyHi.toULong() }, { it.keyLo.toULong() }))
        .distinctBy { it.keyHi to it.keyLo }
    val sampleLog2 = 6
    val l = wpsChooseL(universeBits, sorted.size)
    val high = wpsHigh(sorted, l, universeBits)
    val highBytes = high.bytes()
    val out = ByteArrayOutputStream()
    out.write("WPSDB2\u0000\u0000".toByteArray(Charsets.US_ASCII))
    out.write(1)
    out.write(universeBits)
    out.write(2) // payload kind 2
    out.write(l)
    out.write(sampleLog2)
    out.write(byteArrayOf(0, 0, 0))
    out.u64le(sorted.size.toLong())
    out.u64le(high.bits)
    out.u64le(highBytes.size.toLong())
    out.write(highBytes)
    val zerosBytes = wpsZeros(highBytes, high.bits, sampleLog2)
    out.u64le(zerosBytes.size.toLong())
    out.write(zerosBytes)
    val lowBytes = wpsLow(sorted, l)
    out.u64le(lowBytes.size.toLong())
    out.write(lowBytes)
    val payloadBytes = wpsPayload(sorted)
    out.u64le(payloadBytes.size.toLong())
    out.write(payloadBytes)
    file.parentFile?.mkdirs()
    file.writeBytes(out.toByteArray())
}

/** Minimal zstd frame holding [data] in raw (uncompressed) blocks, which ruzstd decodes. */
internal fun zstdRaw(data: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte()))
    out.write(0xA0) // single-segment, 4-byte frame content size
    for (i in 0 until 4) out.write((data.size ushr (8 * i)) and 0xFF)
    if (data.isEmpty()) {
        out.write(byteArrayOf(1, 0, 0)) // last, raw, size 0
    } else {
        var off = 0
        while (off < data.size) {
            val chunk = minOf(data.size - off, 100_000)
            val last = if (off + chunk == data.size) 1 else 0
            val header = (chunk shl 3) or last
            for (i in 0 until 3) out.write((header ushr (8 * i)) and 0xFF)
            out.write(data, off, chunk)
            off += chunk
        }
    }
    return out.toByteArray()
}

private fun be32(out: ByteArrayOutputStream, v: Int) {
    for (i in 3 downTo 0) out.write((v ushr (8 * i)) and 0xFF)
}

private fun be64(out: ByteArrayOutputStream, v: Long) {
    for (i in 7 downTo 0) out.write(((v ushr (8 * i)) and 0xFF).toInt())
}

private fun zigzag(v: Int): Long = (((v shl 1) xor (v shr 31)).toLong()) and 0xFFFFFFFFL

private fun varint(out: ByteArrayOutputStream, value: Long) {
    var v = value
    while (true) {
        val b = (v and 0x7F).toInt()
        v = v ushr 7
        if (v == 0L) {
            out.write(b)
            return
        }
        out.write(b or 0x80)
    }
}

private fun geocoderColumn(values: IntArray, delta: Boolean): ByteArray {
    val raw = ByteArrayOutputStream()
    var prev = 0
    for ((i, v) in values.withIndex()) {
        val dv = if (delta && i > 0) v - prev else v
        varint(raw, zigzag(dv))
        prev = v
    }
    val comp = zstdRaw(raw.toByteArray())
    val out = ByteArrayOutputStream()
    be32(out, values.size)
    be32(out, 1)
    be32(out, raw.size())
    be32(out, comp.size)
    out.write(comp)
    return out.toByteArray()
}

private fun geocoderDict(strings: List<String>): ByteArray {
    val per = maxOf(strings.size, 1)
    val blocks = if (strings.isEmpty()) 0 else (strings.size + per - 1) / per
    val out = ByteArrayOutputStream()
    be32(out, strings.size)
    be32(out, per)
    be32(out, blocks)
    // first-string table: first string of each block, cumulative ends
    val firsts = ByteArrayOutputStream()
    var end = 0
    for (blk in 0 until blocks) {
        val s = strings[blk * per].toByteArray(Charsets.UTF_8)
        firsts.write(s)
        end += s.size
        be32(out, end)
    }
    out.write(firsts.toByteArray())
    // blocks: raw encoding [be32 len][utf8] per string, zstd-wrapped
    val blockData = ByteArrayOutputStream()
    var compEnd = 0
    val ends = mutableListOf<Int>()
    for (blk in 0 until blocks) {
        val rawBlk = ByteArrayOutputStream()
        val from = blk * per
        val to = minOf(from + per, strings.size)
        for (i in from until to) {
            val s = strings[i].toByteArray(Charsets.UTF_8)
            be32(rawBlk, s.size)
            rawBlk.write(s)
        }
        val comp = zstdRaw(rawBlk.toByteArray())
        blockData.write(comp)
        compEnd += comp.size
        ends.add(compEnd)
    }
    for (e in ends) be32(out, e)
    out.write(blockData.toByteArray())
    return out.toByteArray()
}

internal data class GeoRec(
    val latE5: Int,
    val lonE5: Int,
    val nameId: Int,
    val houseId: Int,
    val streetId: Int,
    val cityId: Int,
    val stateId: Int,
    val countryId: Int,
    val postcodeId: Int,
    val kind: Int,
)

/** Dictionary contents shared by [writeGeocoderDb] records, in dict order. */
internal val GEO_DICTS: List<List<String>> = listOf(
    listOf("", "Test Cafe"), // name
    listOf("123", "124"), // house
    listOf("Main St"), // street
    listOf("Springfield"), // city
    listOf("CA"), // state
    listOf("US"), // country
    listOf("12345"), // postcode
)

internal val GEO_RECORDS: List<GeoRec> = listOf(
    GeoRec(3_742_150, -12_208_400, 1, 0, 0, 0, 0, 0, 0, 1),
    GeoRec(3_742_250, -12_208_300, -1, 1, 0, 0, 0, 0, 0, 1),
)

/** Writes a minimal but valid v5 geocoder.db holding [GEO_RECORDS]. */
internal fun writeGeocoderDb(file: File) {
    val cellOf = { r: GeoRec ->
        val row = (r.latE5.toLong() + 9_000_000L) / 5_000L
        val col = (r.lonE5.toLong() + 18_000_000L) / 5_000L
        row * 7_200L + col
    }
    val ordered = GEO_RECORDS.sortedBy(cellOf)
    val grid = mutableListOf<Pair<Long, Int>>()
    ordered.forEachIndexed { idx, r ->
        val c = cellOf(r)
        if (grid.none { it.first == c }) grid.add(c to idx)
    }
    grid.sortBy { it.first }

    val dictSections = GEO_DICTS.map { geocoderDict(it) }
    val colValues: List<IntArray> = listOf(
        ordered.map { it.latE5 }.toIntArray(),
        ordered.map { it.lonE5 }.toIntArray(),
        ordered.map { it.nameId }.toIntArray(),
        ordered.map { it.houseId }.toIntArray(),
        ordered.map { it.streetId }.toIntArray(),
        ordered.map { it.cityId }.toIntArray(),
        ordered.map { it.stateId }.toIntArray(),
        ordered.map { it.countryId }.toIntArray(),
        ordered.map { it.postcodeId }.toIntArray(),
        ordered.map { it.kind }.toIntArray(),
    )
    val deltaCols = booleanArrayOf(true, true, false, false, false, false, false, false, false, false)
    val colSections = colValues.mapIndexed { i, v -> geocoderColumn(v, deltaCols[i]) }

    val gridBody = ByteArrayOutputStream()
    be32(gridBody, grid.size)
    for ((cell, start) in grid) {
        be64(gridBody, cell)
        be32(gridBody, start)
    }

    val fwdRecs = ordered.indices.filter { ordered[it].streetId >= 0 }
    val fwd = geocoderColumn(fwdRecs.toIntArray(), delta = true)
    val nmPairs = ordered.indices
        .filter { ordered[it].nameId >= 0 }
        .map { ordered[it].nameId to it }
        .sortedBy { it.first }
    val nmName = geocoderColumn(nmPairs.map { it.first }.toIntArray(), delta = true)
    val nmRec = geocoderColumn(nmPairs.map { it.second }.toIntArray(), delta = false)

    val sections = dictSections + colSections + listOf(gridBody.toByteArray(), fwd, nmName, nmRec)
    val out = ByteArrayOutputStream()
    be32(out, 0x4D414745)
    be32(out, 5)
    be32(out, ordered.size)
    for (s in sections) {
        be32(out, s.size)
        out.write(s)
    }
    file.parentFile?.mkdirs()
    file.writeBytes(out.toByteArray())
}
