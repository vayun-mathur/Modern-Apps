package com.vayunmathur.weather.domain.wx

/**
 * `WXP1` packed objects (spec + writer: location_share_server
 * `src/handlers/weather/pack.rs`): many tiles in one R2 object, read with
 * HTTP Range requests. `run.wxp` holds every tile of the current run
 * (one stable key, replaced atomically per run); its per-tile entry sits at
 * a fixed offset, so one ranged GET tells the app whether a new run exists
 * and where its tile and history days are. `hist/<day>.wxp` holds finished
 * days, `geo.wxp` the static geo blocks.
 */
internal object WxPack {
    const val HEADER_LEN = 64L
    const val KIND_RUN = 1
    const val KIND_GEO = 3
    const val RUN_SLOTS = 10
    const val RUN_ENTRY_LEN = 40 + 8 * RUN_SLOTS

    /** Production run-pack layout (index box = the whole ICON tiling). */
    const val CELLS = 8
    const val NY = 1441
    const val NX = 2879
    const val RUN_NKY = 181 // ceil(1441 / 8)
    const val RUN_NKX = 360 // ceil(2879 / 8)

    private const val SPAN_LEN_BITS = 24
    private const val SPAN_LEN_MASK = 0xFF_FFFFL

    /** Byte range inside a pack; `len == 0` = absent. */
    data class Span(val off: Long, val len: Int) {
        val isEmpty get() = len == 0
        /** HTTP Range header value. */
        fun range() = "bytes=$off-${off + len - 1}"
    }

    fun span(v: Long) = Span(v ushr SPAN_LEN_BITS, (v and SPAN_LEN_MASK).toInt())

    private fun le64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (k in 7 downTo 0) v = (v shl 8) or (b[o + k].toLong() and 0xFF)
        return v
    }

    private fun le32(b: ByteArray, o: Int): Int = le64Partial(b, o, 4).toInt()
    private fun le16(b: ByteArray, o: Int): Int = le64Partial(b, o, 2).toInt()
    private fun le64Partial(b: ByteArray, o: Int, n: Int): Long {
        var v = 0L
        for (k in n - 1 downTo 0) v = (v shl 8) or (b[o + k].toLong() and 0xFF)
        return v
    }

    class RunEntry(
        val gen: Long,
        val nextCheck: Long,
        val histFromDay: Int,
        val histToDay: Int,
        val ty: Int,
        val tx: Int,
        val tile: Span,
        val hist: List<Span>,
    ) {
        /** Span of history day [day] in `hist/<day>.wxp`, null when absent. */
        fun histSpan(day: Long): Span? {
            val k = day - histFromDay
            if (k < 0 || day >= histToDay || k >= hist.size) return null
            return hist[k.toInt()].takeIf { !it.isEmpty }
        }
    }

    fun decodeRunEntry(b: ByteArray): RunEntry {
        require(b.size >= RUN_ENTRY_LEN) { "short run entry" }
        return RunEntry(
            gen = le64(b, 0),
            nextCheck = le64(b, 8),
            histFromDay = le32(b, 16),
            histToDay = le32(b, 20),
            ty = le16(b, 24),
            tx = le16(b, 26),
            tile = span(le64(b, 32)),
            hist = List(RUN_SLOTS) { k -> span(le64(b, 40 + 8 * k)) },
        )
    }

    /** Offset of tile (ty, tx)'s entry in the production run pack. */
    fun runEntryOffset(ty: Int, tx: Int): Long = HEADER_LEN + (ty.toLong() * RUN_NKX + tx) * RUN_ENTRY_LEN

    /** Offset of tile (ty, tx)'s span in a history day pack (same index box). */
    fun histEntryOffset(ty: Int, tx: Int): Long = HEADER_LEN + (ty.toLong() * RUN_NKX + tx) * 8

    class Header(val kind: Int, val entryLen: Int, val ky0: Int, val kx0: Int, val nky: Int, val nkx: Int) {
        fun entryOffset(ky: Int, kx: Int): Long? {
            val dy = ky - ky0
            val dx = kx - kx0
            if (dy !in 0 until nky || dx !in 0 until nkx) return null
            return HEADER_LEN + (dy.toLong() * nkx + dx) * entryLen
        }
    }

    fun decodeHeader(b: ByteArray): Header {
        require(b.size >= HEADER_LEN && String(b, 0, 4, Charsets.US_ASCII) == "WXP1") { "not a WXP1 pack" }
        require(b[4].toInt() == 1) { "pack version ${b[4]}" }
        return Header(
            kind = b[5].toInt(),
            entryLen = le16(b, 6),
            ky0 = le16(b, 40),
            kx0 = le16(b, 42),
            nky = le16(b, 44),
            nkx = le16(b, 46),
        )
    }

    /** A geo entry (8-byte span). */
    fun decodeSpan(b: ByteArray): Span = span(le64(b, 0))
}
