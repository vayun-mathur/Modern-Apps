package com.vayunmathur.share.domain.airdrop

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Apple binary property list (`bplist00`) codec — the body format of every AirDrop
 * `/Discover` and `/Ask` request and response.
 *
 * Values map to Kotlin as: `Boolean`, `Long` (every integer width), `Double`, `String`,
 * `ByteArray` (plist data), `List<Any?>` (array) and `Map<String, Any?>` (dict). `Int` is
 * accepted on encode and widened to `Long`. Dates decode to their raw seconds-since-2001
 * `Double`; AirDrop never sends one that matters.
 */
object BinaryPlist {

    private const val MAGIC = "bplist00"
    private const val TRAILER_SIZE = 32

    /** Format Exception: a string implementation for format exception. */
    class FormatException(message: String) : IllegalArgumentException(message)

    fun encode(root: Any?): ByteArray = Encoder().encode(root)

    fun decode(bytes: ByteArray): Any? = Decoder(bytes).decodeRoot()

    /** [decode] for the common case of a dict at the root. */
    @Suppress("UNCHECKED_CAST")
    fun decodeDict(bytes: ByteArray): Map<String, Any?> =
        decode(bytes) as? Map<String, Any?> ?: throw FormatException("root is not a dict")

    private class Encoder {
        private val objects = mutableListOf<Any?>()

        fun encode(root: Any?): ByteArray {
            flatten(root)
            val refSize = sizeFor(objects.size.toLong())
            val out = ByteArrayOutputStream()
            out.write(MAGIC.toByteArray(Charsets.US_ASCII))
            val offsets = LongArray(objects.size)
            objects.forEachIndexed { index, obj ->
                offsets[index] = out.size().toLong()
                // Children were flattened immediately after their parent.
                writeObject(out, obj, refSize, firstChildRef = index + 1)
            }
            val offsetTableOffset = out.size().toLong()
            val offsetSize = sizeFor(offsetTableOffset)
            offsets.forEach { writeSized(out, it, offsetSize) }
            out.write(ByteArray(6))
            out.write(offsetSize)
            out.write(refSize)
            writeSized(out, objects.size.toLong(), 8)
            writeSized(out, 0, 8)
            writeSized(out, offsetTableOffset, 8)
            return out.toByteArray()
        }

        /** Depth-first, matching the order [writeObject] hands out child refs. */
        private fun flatten(value: Any?) {
            objects += value
            when (value) {
                is Map<*, *> -> {
                    value.keys.forEach { flatten(it as? String ?: throw FormatException("dict key ${it?.javaClass}")) }
                    value.values.forEach { flatten(it) }
                }
                is List<*> -> value.forEach { flatten(it) }
            }
        }

        private fun subtreeSize(value: Any?): Int = 1 + when (value) {
            is Map<*, *> -> value.keys.size + value.values.sumOf { subtreeSize(it) }
            is List<*> -> value.sumOf { subtreeSize(it) }
            else -> 0
        }

        private fun writeObject(out: ByteArrayOutputStream, value: Any?, refSize: Int, firstChildRef: Int) {
            when (value) {
                is List<*> -> writeList(out, value, refSize, firstChildRef)
                is Map<*, *> -> writeDict(out, value, refSize, firstChildRef)
                else -> writeScalar(out, value)
            }
        }

        private fun writeScalar(out: ByteArrayOutputStream, value: Any?) {
            when (value) {
                null -> out.write(0x00)
                is Boolean -> out.write(if (value) 0x09 else 0x08)
                is Int -> writeInt(out, value.toLong())
                is Long -> writeInt(out, value)
                is Double -> {
                    out.write(0x23)
                    writeSized(out, java.lang.Double.doubleToRawLongBits(value), 8)
                }
                is String -> writeString(out, value)
                is ByteArray -> {
                    writeMarker(out, 0x40, value.size)
                    out.write(value)
                }
                else -> throw FormatException("cannot encode ${value.javaClass.name}")
            }
        }

        private fun writeList(out: ByteArrayOutputStream, value: List<*>, refSize: Int, firstChildRef: Int) {
            writeMarker(out, 0xA0, value.size)
            var ref = firstChildRef
            value.forEach { child ->
                writeSized(out, ref.toLong(), refSize)
                ref += subtreeSize(child)
            }
        }

        private fun writeDict(out: ByteArrayOutputStream, value: Map<*, *>, refSize: Int, firstChildRef: Int) {
            writeMarker(out, 0xD0, value.size)
            var ref = firstChildRef
            value.keys.forEach { _ -> writeSized(out, (ref++).toLong(), refSize) }
            value.values.forEach { child ->
                writeSized(out, ref.toLong(), refSize)
                ref += subtreeSize(child)
            }
        }

        private fun writeInt(out: ByteArrayOutputStream, v: Long) {
            val (marker, size) = when (v) {
                in 0L..0xFFL -> 0x10 to 1
                in 0L..0xFFFFL -> 0x11 to 2
                in 0L..0xFFFFFFFFL -> 0x12 to 4
                else -> 0x13 to 8
            }
            out.write(marker)
            writeSized(out, v, size)
        }

        private fun writeString(out: ByteArrayOutputStream, s: String) {
            if (s.all { it.code < 0x80 }) {
                writeMarker(out, 0x50, s.length)
                out.write(s.toByteArray(Charsets.US_ASCII))
            } else {
                writeMarker(out, 0x60, s.length)
                out.write(s.toByteArray(Charsets.UTF_16BE))
            }
        }

        private fun writeMarker(out: ByteArrayOutputStream, type: Int, count: Int) {
            if (count < 0x0F) {
                out.write(type or count)
            } else {
                out.write(type or 0x0F)
                writeInt(out, count.toLong())
            }
        }

        private fun sizeFor(max: Long): Int = when {
            max <= 0xFF -> 1
            max <= 0xFFFF -> 2
            max <= 0xFFFFFFFFL -> 4
            else -> 8
        }

        private fun writeSized(out: ByteArrayOutputStream, v: Long, size: Int) {
            for (i in size - 1 downTo 0) out.write(((v ushr (8 * i)) and 0xFF).toInt())
        }
    }

    private class Decoder(private val bytes: ByteArray) {
        private var offsetSize = 0
        private var refSize = 0
        private var numObjects = 0L
        private var offsetTableOffset = 0L
        private var depth = 0

        fun decodeRoot(): Any? {
            if (bytes.size < MAGIC.length + TRAILER_SIZE ||
                String(bytes, 0, MAGIC.length, Charsets.US_ASCII) != MAGIC
            ) {
                throw FormatException("not a bplist00")
            }
            val t = bytes.size - TRAILER_SIZE
            offsetSize = bytes[t + 6].toInt() and 0xFF
            refSize = bytes[t + 7].toInt() and 0xFF
            numObjects = readSized(t + 8, 8)
            val top = readSized(t + 16, 8)
            offsetTableOffset = readSized(t + 24, 8)
            if (!isTrailerValid(top, t)) {
                throw FormatException("corrupt trailer")
            }
            return readObject(top)
        }

        private fun isTrailerValid(top: Long, trailerStart: Int): Boolean {
            val sizesValid = offsetSize in 1..8 && refSize in 1..8
            val topValid = top < numObjects
            val tableFits = offsetTableOffset + numObjects * offsetSize <= trailerStart
            return sizesValid && topValid && tableFits
        }

        private fun offsetOf(ref: Long): Int {
            if (ref !in 0 until numObjects) throw FormatException("ref $ref out of range")
            val off = readSized((offsetTableOffset + ref * offsetSize).toInt(), offsetSize)
            if (off !in MAGIC.length.toLong() until offsetTableOffset) throw FormatException("bad offset $off")
            return off.toInt()
        }

        private fun readObject(ref: Long): Any? {
            if (++depth > MAX_DEPTH) throw FormatException("nesting too deep")
            try {
                val pos = offsetOf(ref)
                val marker = bytes[pos].toInt() and 0xFF
                val type = marker shr 4
                val info = marker and 0x0F
                return when (type) {
                    0x0 -> readSimple(info, marker)
                    0x1 -> readInt(pos + 1, 1 shl info)
                    0x2 -> readReal(pos + 1, 1 shl info)
                    0x3 -> readReal(pos + 1, 8)
                    0x4 -> {
                        val (len, start) = countAt(pos, info)
                        bytes.copyOfRange(start, checkedEnd(start, len))
                    }
                    0x5 -> {
                        val (len, start) = countAt(pos, info)
                        String(bytes, start, checkedEnd(start, len) - start, Charsets.US_ASCII)
                    }
                    0x6 -> {
                        val (len, start) = countAt(pos, info)
                        String(bytes, start, checkedEnd(start, len * 2) - start, Charsets.UTF_16BE)
                    }
                    0x8 -> readSized(pos + 1, info + 1)
                    0xA -> readArray(pos, info)
                    0xD -> readDict(pos, info)
                    else -> throw FormatException("unsupported marker $marker")
                }
            } finally {
                depth--
            }
        }

        private fun readSimple(info: Int, marker: Int): Any? = when (info) {
            0x0 -> null
            0x8 -> false
            0x9 -> true
            else -> throw FormatException("bad simple marker $marker")
        }

        private fun readArray(pos: Int, info: Int): List<Any?> {
            val (count, start) = countAt(pos, info)
            checkedEnd(start, count * refSize)
            return List(count) { i -> readObject(readSized(start + i * refSize, refSize)) }
        }

        private fun readDict(pos: Int, info: Int): Map<String, Any?> {
            val (count, start) = countAt(pos, info)
            checkedEnd(start, count * refSize * 2)
            val map = LinkedHashMap<String, Any?>(count)
            for (i in 0 until count) {
                val key = readObject(readSized(start + i * refSize, refSize)) as? String
                    ?: throw FormatException("non-string dict key")
                map[key] = readObject(readSized(start + (count + i) * refSize, refSize))
            }
            return map
        }

        private fun countAt(pos: Int, info: Int): Pair<Int, Int> {
            if (info != 0x0F) return info to pos + 1
            val intMarker = bytes[pos + 1].toInt() and 0xFF
            if (intMarker shr 4 != 0x1) throw FormatException("bad length marker")
            val size = 1 shl (intMarker and 0x0F)
            val count = readInt(pos + 2, size)
            if (count !in 0..Int.MAX_VALUE.toLong()) throw FormatException("bad length $count")
            return count.toInt() to pos + 2 + size
        }

        private fun checkedEnd(start: Int, len: Int): Int {
            val end = start.toLong() + len
            if (len < 0 || end > offsetTableOffset) throw FormatException("object overruns")
            return end.toInt()
        }

        private fun readInt(pos: Int, size: Int): Long = when (size) {
            1, 2, 4 -> readSized(pos, size)
            8 -> readSized(pos, 8)
            16 -> readSized(pos + 8, 8)
            else -> throw FormatException("bad int size $size")
        }

        private fun readReal(pos: Int, size: Int): Double = when (size) {
            4 -> ByteBuffer.wrap(bytes, pos, 4).float.toDouble()
            8 -> ByteBuffer.wrap(bytes, pos, 8).double
            else -> throw FormatException("bad real size $size")
        }

        private fun readSized(pos: Int, size: Int): Long {
            if (pos < 0 || pos + size > bytes.size) throw FormatException("read past end")
            var v = 0L
            for (i in 0 until size) v = (v shl 8) or (bytes[pos + i].toLong() and 0xFF)
            return v
        }
    }

    private const val MAX_DEPTH = 64
}
