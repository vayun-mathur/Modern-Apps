package com.vayunmathur.messages.telegram.mtproto.tl

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TlBuffer {
    private var buf: ByteArray
    private var pos: Int = 0
    private var writeStream: java.io.ByteArrayOutputStream? = null

    constructor() {
        buf = ByteArray(0)
        writeStream = java.io.ByteArrayOutputStream(256)
    }

    constructor(data: ByteArray) {
        buf = data
    }

    val raw: ByteArray get() = writeStream?.toByteArray() ?: buf
    val remaining: Int get() = buf.size - pos
    val length: Int get() = writeStream?.size() ?: buf.size

    fun reset() {
        buf = ByteArray(0)
        pos = 0
    }

    fun resetTo(data: ByteArray) {
        buf = data
        pos = 0
    }

    // ---- Encoding (appending to buf) ----

    fun putInt32(v: Int) {
        val b = ByteArray(4)
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(v)
        append(b)
    }

    fun putInt64(v: Long) {
        val b = ByteArray(8)
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putLong(v)
        append(b)
    }

    fun putDouble(v: Double) {
        putInt64(java.lang.Double.doubleToRawLongBits(v))
    }

    fun putId(id: Int) = putInt32(id)

    fun putBool(v: Boolean) {
        putId(if (v) TYPE_TRUE else TYPE_FALSE)
    }

    fun putString(s: String) {
        putBytes(s.toByteArray(Charsets.UTF_8))
    }

    fun putBytes(v: ByteArray) {
        val out = ByteArrayOutputStream()
        val l = v.size
        if (l <= 253) {
            out.write(l)
            out.write(v)
            val pad = nearestPadded(l + 1) - (l + 1)
            if (pad > 0) out.write(ByteArray(pad))
        } else {
            out.write(0xFE)
            out.write(l and 0xFF)
            out.write((l shr 8) and 0xFF)
            out.write((l shr 16) and 0xFF)
            out.write(v)
            val pad = nearestPadded(l + 4) - (l + 4)
            if (pad > 0) out.write(ByteArray(pad))
        }
        append(out.toByteArray())
    }

    fun putVectorHeader(length: Int) {
        putId(TYPE_VECTOR)
        putInt32(length)
    }

    fun putRawBytes(v: ByteArray) = append(v)

    fun putInt128(v: Int128) = append(v.data)

    fun putInt256(v: Int256) = append(v.data)

    // ---- Decoding (reading from buf at pos) ----

    fun int32(): Int {
        check(remaining >= 4) { "Buffer underflow: need 4 bytes, have $remaining" }
        val v = ByteBuffer.wrap(buf, pos, 4).order(ByteOrder.LITTLE_ENDIAN).int
        pos += 4
        return v
    }

    fun int64(): Long {
        check(remaining >= 8) { "Buffer underflow: need 8 bytes, have $remaining" }
        val v = ByteBuffer.wrap(buf, pos, 8).order(ByteOrder.LITTLE_ENDIAN).long
        pos += 8
        return v
    }

    fun double(): Double = java.lang.Double.longBitsToDouble(int64())

    fun peekId(): Int {
        check(remaining >= 4) { "Buffer underflow: need 4 bytes to peek" }
        return ByteBuffer.wrap(buf, pos, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    fun consumeId(expected: Int) {
        val got = int32()
        check(got == expected) { "Unexpected type ID: expected 0x${expected.toUInt().toString(16)}, got 0x${got.toUInt().toString(16)}" }
    }

    fun string(): String = bytes().toString(Charsets.UTF_8)

    fun bytes(): ByteArray {
        check(remaining >= 1) { "Buffer underflow" }
        val first = buf[pos].toInt() and 0xFF
        val strLen: Int
        val headerLen: Int
        if (first == 0xFE) {
            check(remaining >= 4) { "Buffer underflow for long string header" }
            strLen = (buf[pos + 1].toInt() and 0xFF) or
                    ((buf[pos + 2].toInt() and 0xFF) shl 8) or
                    ((buf[pos + 3].toInt() and 0xFF) shl 16)
            headerLen = 4
        } else {
            strLen = first
            headerLen = 1
        }
        val totalLen = nearestPadded(headerLen + strLen)
        check(remaining >= totalLen) { "Buffer underflow: need $totalLen bytes" }
        val result = buf.copyOfRange(pos + headerLen, pos + headerLen + strLen)
        pos += totalLen
        return result
    }

    fun bool(): Boolean {
        val id = int32()
        return when (id) {
            TYPE_TRUE -> true
            TYPE_FALSE -> false
            else -> error("Unexpected bool type ID: 0x${id.toUInt().toString(16)}")
        }
    }

    fun vectorHeader(): Int {
        consumeId(TYPE_VECTOR)
        return int32()
    }

    fun rawBytes(n: Int): ByteArray {
        check(remaining >= n) { "Buffer underflow: need $n bytes, have $remaining" }
        val result = buf.copyOfRange(pos, pos + n)
        pos += n
        return result
    }

    fun int128(): Int128 = Int128(rawBytes(16))

    fun int256(): Int256 = Int256(rawBytes(32))

    fun skip(n: Int) {
        check(remaining >= n) { "Buffer underflow: cannot skip $n bytes" }
        pos += n
    }

    fun copy(): ByteArray = buf.copyOf()

    fun data(): ByteArray = buf.copyOfRange(pos, buf.size)

    private fun append(data: ByteArray) {
        val ws = writeStream
        if (ws != null) {
            ws.write(data)
        } else {
            buf = buf + data
        }
    }

    companion object {
        private fun nearestPadded(l: Int): Int {
            val n = 4 * (l / 4)
            return if (n < l) n + 4 else n
        }
    }
}
