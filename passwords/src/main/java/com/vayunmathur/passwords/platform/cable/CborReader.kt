package com.vayunmathur.passwords.platform.cable

/**
 * Minimal CBOR (RFC 8949) decoder, companion to the canonical encoder in
 * [com.vayunmathur.passwords.domain.Cbor]. Covers exactly what caBLE / CTAP2 / COSE need:
 * unsigned + negative integers, byte strings, text strings, arrays, maps, and the
 * `true`/`false`/`null` simple values.
 *
 * Decoded types:
 *  - integers  -> [Long]
 *  - byte string -> [ByteArray]
 *  - text string -> [String]
 *  - array     -> `List<Any?>`
 *  - map       -> `LinkedHashMap<Any, Any?>` (insertion order = wire order)
 *  - simple    -> [Boolean] or `null`
 *
 * Indefinite-length items and floats are intentionally unsupported (never used by these protocols).
 */
class CborReader(private val bytes: ByteArray, private var pos: Int = 0) {

    /** Number of unconsumed bytes remaining. */
    val remaining: Int get() = bytes.size - pos

    /** Reads a single CBOR data item, advancing the cursor. */
    fun readValue(): Any? {
        val initial = readByte()
        val major = (initial.toInt() and BYTE_MASK) ushr MAJOR_SHIFT
        val minor = initial.toInt() and MINOR_MASK
        return when (major) {
            MAJOR_UNSIGNED -> readArg(minor)
            MAJOR_NEGATIVE -> -1L - readArg(minor)
            MAJOR_BYTE_STRING -> readBytes(readArg(minor).toIntChecked())
            MAJOR_TEXT_STRING -> String(readBytes(readArg(minor).toIntChecked()), Charsets.UTF_8)
            MAJOR_ARRAY -> readArray(minor)
            MAJOR_MAP -> readMap(minor)
            MAJOR_SIMPLE -> readSimple(minor)
            else -> error("Unsupported CBOR major type: $major")
        }
    }

    private fun readArray(minor: Int): List<Any?> {
        val n = readArg(minor).toIntChecked()
        return ArrayList<Any?>(n).apply { repeat(n) { add(readValue()) } }
    }

    private fun readMap(minor: Int): Map<Any, Any?> {
        val n = readArg(minor).toIntChecked()
        return LinkedHashMap<Any, Any?>(n * 2).apply {
            repeat(n) {
                val k = readValue() ?: error("CBOR map key must not be null")
                put(k, readValue())
            }
        }
    }

    private fun readSimple(minor: Int): Any? = when (minor) {
        SIMPLE_FALSE -> false
        SIMPLE_TRUE -> true
        SIMPLE_NULL -> null
        SIMPLE_UNDEFINED -> null
        else -> error("Unsupported CBOR simple value: $minor")
    }

    /** Reads a value expected to be an integer. */
    fun readInt(): Long = readValue() as? Long ?: error("Expected CBOR integer")

    /** Reads a value expected to be a byte string. */
    fun readByteString(): ByteArray = readValue() as? ByteArray ?: error("Expected CBOR byte string")

    /** Reads a value expected to be a map keyed by [Long] (CTAP-style integer keys). */
    @Suppress("UNCHECKED_CAST")
    fun readIntMap(): Map<Long, Any?> {
        val map = readValue() as? Map<*, *> ?: error("Expected CBOR map")
        return map.entries.associate { (k, v) -> (k as Long) to v }
    }

    private fun readArg(minor: Int): Long = when (minor) {
        in 0..ARG_IMMEDIATE_MAX -> minor.toLong()
        ARG_UINT8 -> readByte().toLong() and BYTE_MASK.toLong()
        ARG_UINT16 -> readUInt(UINT16_BYTES)
        ARG_UINT32 -> readUInt(UINT32_BYTES)
        ARG_UINT64 -> readUInt(UINT64_BYTES)
        else -> error("Unsupported CBOR additional-info: $minor")
    }

    private fun readUInt(n: Int): Long {
        var value = 0L
        repeat(n) { value = (value shl BYTE_SHIFT) or (readByte().toLong() and BYTE_MASK.toLong()) }
        return value
    }

    private fun readByte(): Byte {
        if (pos >= bytes.size) error("Unexpected end of CBOR input")
        return bytes[pos++]
    }

    private fun readBytes(n: Int): ByteArray {
        if (pos + n > bytes.size) error("Unexpected end of CBOR input")
        return bytes.copyOfRange(pos, pos + n).also { pos += n }
    }

    private fun Long.toIntChecked(): Int {
        if (this < 0 || this > Int.MAX_VALUE) error("CBOR length out of range: $this")
        return toInt()
    }

    /** Shared constants and helpers for CborReader. */
    companion object {
        private const val MAJOR_UNSIGNED = 0
        private const val MAJOR_NEGATIVE = 1
        private const val MAJOR_BYTE_STRING = 2
        private const val MAJOR_TEXT_STRING = 3
        private const val MAJOR_ARRAY = 4
        private const val MAJOR_MAP = 5
        private const val MAJOR_SIMPLE = 7

        private const val SIMPLE_FALSE = 20
        private const val SIMPLE_TRUE = 21
        private const val SIMPLE_NULL = 22
        private const val SIMPLE_UNDEFINED = 23

        private const val ARG_IMMEDIATE_MAX = 23
        private const val ARG_UINT8 = 24
        private const val ARG_UINT16 = 25
        private const val ARG_UINT32 = 26
        private const val ARG_UINT64 = 27
        private const val BYTE_MASK = 0xFF
        private const val BYTE_SHIFT = 8
        private const val UINT16_BYTES = 2
        private const val UINT32_BYTES = 4
        private const val UINT64_BYTES = 8
        private const val MAJOR_SHIFT = 5
        private const val MINOR_MASK = 0x1F

        /** Decodes a single top-level CBOR value from [bytes]. */
        fun decode(bytes: ByteArray): Any? = CborReader(bytes).readValue()
    }
}
