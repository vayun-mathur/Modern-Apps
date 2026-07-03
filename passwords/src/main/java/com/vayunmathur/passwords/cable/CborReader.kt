package com.vayunmathur.passwords.cable

/**
 * Minimal CBOR (RFC 8949) decoder, companion to the canonical encoder in
 * [com.vayunmathur.passwords.util.Cbor]. Covers exactly what caBLE / CTAP2 / COSE need:
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
        val major = (initial.toInt() and 0xFF) ushr 5
        val minor = initial.toInt() and 0x1F
        return when (major) {
            MAJOR_UNSIGNED -> readArg(minor)
            MAJOR_NEGATIVE -> -1L - readArg(minor)
            MAJOR_BYTE_STRING -> readBytes(readArg(minor).toIntChecked())
            MAJOR_TEXT_STRING -> String(readBytes(readArg(minor).toIntChecked()), Charsets.UTF_8)
            MAJOR_ARRAY -> {
                val n = readArg(minor).toIntChecked()
                ArrayList<Any?>(n).apply { repeat(n) { add(readValue()) } }
            }
            MAJOR_MAP -> {
                val n = readArg(minor).toIntChecked()
                LinkedHashMap<Any, Any?>(n * 2).apply {
                    repeat(n) {
                        val k = readValue() ?: error("CBOR map key must not be null")
                        put(k, readValue())
                    }
                }
            }
            MAJOR_SIMPLE -> when (minor) {
                SIMPLE_FALSE -> false
                SIMPLE_TRUE -> true
                SIMPLE_NULL -> null
                SIMPLE_UNDEFINED -> null
                else -> error("Unsupported CBOR simple value: $minor")
            }
            else -> error("Unsupported CBOR major type: $major")
        }
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
        in 0..23 -> minor.toLong()
        24 -> readByte().toLong() and 0xFF
        25 -> readUInt(2)
        26 -> readUInt(4)
        27 -> readUInt(8)
        else -> error("Unsupported CBOR additional-info: $minor")
    }

    private fun readUInt(n: Int): Long {
        var value = 0L
        repeat(n) { value = (value shl 8) or (readByte().toLong() and 0xFF) }
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

        /** Decodes a single top-level CBOR value from [bytes]. */
        fun decode(bytes: ByteArray): Any? = CborReader(bytes).readValue()
    }
}
