package com.vayunmathur.passwords.util

object Cbor {
    private const val TYPE_UNSIGNED_INT = 0x00
    private const val TYPE_NEGATIVE_INT = 0x01
    private const val TYPE_BYTE_STRING = 0x02
    private const val TYPE_TEXT_STRING = 0x03
    private const val TYPE_ARRAY = 0x04
    private const val TYPE_MAP = 0x05

    fun encode(data: Any): ByteArray = when (data) {
        is Boolean -> byteArrayOf((if (data) 0xF5 else 0xF4).toByte())
        is Number -> {
            val value = data.toLong()
            if (value >= 0) createArg(TYPE_UNSIGNED_INT, value)
            else createArg(TYPE_NEGATIVE_INT, -1 - value)
        }
        is ByteArray -> createArg(TYPE_BYTE_STRING, data.size.toLong()) + data
        is String -> createArg(TYPE_TEXT_STRING, data.length.toLong()) + data.encodeToByteArray()
        is List<*> -> data.fold(createArg(TYPE_ARRAY, data.size.toLong())) { acc, i -> acc + encode(i!!) }
        is Map<*, *> -> {
            val byteMap = data.entries.associate { (k, v) -> encode(k!!) to encode(v!!) }
            val sortedKeys = byteMap.keys.sortedWith(Comparator { a, b ->
                if (a.size != b.size) a.size - b.size
                else a.indices.firstNotNullOfOrNull { i ->
                    ((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)).takeIf { it != 0 }
                } ?: 0
            })
            sortedKeys.fold(createArg(TYPE_MAP, data.size.toLong())) { acc, key ->
                acc + key + byteMap[key]!!
            }
        }
        else -> throw IllegalArgumentException("Unsupported CBOR type: ${data::class}")
    }

    private fun createArg(type: Int, arg: Long): ByteArray {
        val t = type shl 5
        val a = arg.toInt()
        if (arg < 24) return byteArrayOf(((t or a) and 0xFF).toByte())
        if (arg <= 0xFF) return byteArrayOf(((t or 24) and 0xFF).toByte(), (a and 0xFF).toByte())
        if (arg <= 0xFFFF) return byteArrayOf(
            ((t or 25) and 0xFF).toByte(),
            ((a shr 8) and 0xFF).toByte(),
            (a and 0xFF).toByte()
        )
        return byteArrayOf(
            ((t or 26) and 0xFF).toByte(),
            ((a shr 24) and 0xFF).toByte(),
            ((a shr 16) and 0xFF).toByte(),
            ((a shr 8) and 0xFF).toByte(),
            (a and 0xFF).toByte()
        )
    }
}
