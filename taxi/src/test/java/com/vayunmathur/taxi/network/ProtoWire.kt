package com.vayunmathur.taxi.network

/** Minimal protobuf wire builder for parser tests (mirrors ProtoMessageTest helpers). */
internal object ProtoWire {
    fun varintBytes(value: Long): ByteArray {
        val out = mutableListOf<Byte>()
        var v = value
        while (true) {
            var b = (v and 0x7FL).toInt()
            v = v ushr 7
            if (v != 0L) b = b or 0x80
            out.add(b.toByte())
            if (v == 0L) break
        }
        return out.toByteArray()
    }
    fun record(field: Int, wire: Int, payload: ByteArray): ByteArray =
        varintBytes(((field shl 3) or wire).toLong()) + payload
    fun varintField(field: Int, value: Long): ByteArray = record(field, 0, varintBytes(value))
    fun stringField(field: Int, value: String): ByteArray {
        val b = value.toByteArray(Charsets.UTF_8)
        return record(field, 2, varintBytes(b.size.toLong()) + b)
    }
    fun bytesField(field: Int, payload: ByteArray): ByteArray =
        record(field, 2, varintBytes(payload.size.toLong()) + payload)
    fun doubleField(field: Int, value: Double): ByteArray {
        val bits = java.lang.Double.doubleToLongBits(value)
        return record(field, 1, ByteArray(8) { i -> ((bits ushr (8 * i)) and 0xFF).toByte() })
    }
    fun msg(field: Int, vararg parts: ByteArray): ByteArray = bytesField(field, concat(*parts))
    fun wrappedLong(field: Int, value: Long): ByteArray = msg(field, varintField(1, value))
    fun wrappedString(field: Int, value: String): ByteArray = msg(field, stringField(1, value))
    fun wrappedBool(field: Int, value: Boolean): ByteArray = msg(field, varintField(1, if (value) 1 else 0))
    fun concat(vararg parts: ByteArray): ByteArray = parts.fold(byteArrayOf()) { a, p -> a + p }
}
