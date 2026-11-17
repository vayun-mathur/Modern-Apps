package com.vayunmathur.pdf.platform

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the listing buffers from the native side (`listAnnotations`,
 * `listFormFields`, `searchDocument`, `listLinks`, `listOutline`).
 *
 * Split out of [SafePdfParser] so that file stays under the FileLength limit.
 * [SafePdfParser] keeps thin delegating functions, so every existing
 * `SafePdfParser.parseAnnotations(...)` call site keeps working.
 */
object SafePdfListings {

    /** Decode the annotation listing buffer from `listAnnotations`. */
    fun parseAnnotations(bytes: ByteArray): List<SafeAnnotation> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = buf.int
        val out = ArrayList<SafeAnnotation>(buf.listCapacity(count, ANNOTATION_MIN_BYTES))
        repeat(count) {
            val id = buf.long
            val subtype = buf.get().toInt()
            val x0 = buf.float; val y0 = buf.float; val x1 = buf.float; val y1 = buf.float
            val color = buf.int
            val contents = readString(buf)
            out.add(SafeAnnotation(id, subtype, x0, y0, x1, y1, color, contents))
        }
        return out
    }

    /** Decode the form-field listing buffer from `listFormFields`. */
    fun parseFormFields(bytes: ByteArray): List<SafeFormField> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = buf.int
        val out = ArrayList<SafeFormField>(buf.listCapacity(count, FORM_FIELD_MIN_BYTES))
        repeat(count) {
            val id = buf.long
            val type = buf.get().toInt()
            val x0 = buf.float; val y0 = buf.float; val x1 = buf.float; val y1 = buf.float
            val name = readString(buf)
            val value = readString(buf)
            val checked = buf.get().toInt() != 0
            out.add(SafeFormField(id, type, x0, y0, x1, y1, name, value, checked))
        }
        return out
    }

    /** Decode the search-match buffer from `searchDocument`. */
    fun parseSearchMatches(bytes: ByteArray): List<SafeSearchMatch> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = buf.int
        val out = ArrayList<SafeSearchMatch>(buf.listCapacity(count, SEARCH_MATCH_BYTES))
        repeat(count) {
            val page = buf.int
            out.add(SafeSearchMatch(page, buf.float, buf.float, buf.float, buf.float))
        }
        return out
    }

    /** Decode the link listing buffer from `listLinks`. */
    fun parseLinks(bytes: ByteArray): List<SafeLink> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = buf.int
        val out = ArrayList<SafeLink>(buf.listCapacity(count, LINK_MIN_BYTES))
        repeat(count) {
            val x0 = buf.float; val y0 = buf.float; val x1 = buf.float; val y1 = buf.float
            val dest = buf.int
            val uri = readString(buf)
            out.add(SafeLink(x0, y0, x1, y1, dest, uri))
        }
        return out
    }

    /** Decode the outline buffer from `listOutline`. */
    fun parseOutline(bytes: ByteArray): List<SafeOutlineItem> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = buf.int
        val out = ArrayList<SafeOutlineItem>(buf.listCapacity(count, OUTLINE_MIN_BYTES))
        repeat(count) {
            val level = buf.short.toInt() and 0xFFFF
            val page = buf.int
            val title = readString(buf)
            out.add(SafeOutlineItem(level, page, title))
        }
        return out
    }

    /**
     * Initial capacity for a listing whose header claims [count] records of at least
     * [minRecordBytes] each.
     *
     * The count is read straight off the wire, so a bare `ArrayList(count)` allocates an
     * `Object[count]` before a single record has been validated — a corrupt or truncated
     * header reading as ~2e9 raises OutOfMemoryError, which is an Error and so escapes
     * every `runCatching` between here and the composition rather than degrading to an
     * empty listing. The buffer cannot physically hold more than
     * `remaining / minRecordBytes` records, so cap on that: it can never clip a count a
     * well-formed buffer could justify, and it needs no invented constant. The `repeat`
     * loop still runs to `count` and stops on the underflow, so this only bounds the
     * pre-allocation, never the result.
     */
    private fun ByteBuffer.listCapacity(count: Int, minRecordBytes: Int): Int =
        count.coerceIn(0, remaining() / minRecordBytes)

    /** u64 id + u8 subtype + 4xf32 rect + u32 colour + the u16 /Contents length. */
    private const val ANNOTATION_MIN_BYTES = 8 + 1 + 16 + 4 + 2
    /** u64 id + u8 type + 4xf32 rect + two u16 string lengths + u8 checked. */
    private const val FORM_FIELD_MIN_BYTES = 8 + 1 + 16 + 2 + 2 + 1
    /** u32 page + 4xf32 rect, fixed width. */
    private const val SEARCH_MATCH_BYTES = 4 + 16
    /** 4xf32 rect + i32 destination page + the u16 URI length. */
    private const val LINK_MIN_BYTES = 16 + 4 + 2
    /** u16 level + i32 page + the u16 title length. */
    private const val OUTLINE_MIN_BYTES = 2 + 4 + 2

    /**
     * A u16-length-prefixed UTF-8 string, as every listing buffer writes them.
     *
     * No upper-bound rejection. Rust truncates each of these at `u16::MAX` — annotation
     * /Contents (annotations.rs:1980), form field /T and /V (forms.rs:452), a link URI
     * (forms.rs:351) and an outline title (forms.rs:1088) all use
     * `b.len().min(u16::MAX as usize)` — so any length the field can express is a length the
     * producer will legitimately send.
     *
     * `len` is u16-bounded, so the allocation is capped at 64 KB regardless, and the remaining
     * check below is the real guard against a truncated buffer.
     */
    private fun readString(buf: ByteBuffer): String {
        if (buf.remaining() < STRING_LEN_BYTES) {
            throw IllegalArgumentException("readString header truncated")
        }
        val len = buf.short.toInt() and U16_MASK
        if (buf.remaining() < len) {
            throw IllegalArgumentException(
                "readString truncated len=$len remaining=${buf.remaining()}"
            )
        }
        val b = ByteArray(len)
        buf.get(b)
        return String(b, Charsets.UTF_8)
    }

    private const val STRING_LEN_BYTES = 2
    private const val U16_MASK = 0xFFFF
}
