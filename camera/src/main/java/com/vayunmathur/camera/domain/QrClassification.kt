package com.vayunmathur.camera.domain

/**
 * QR link classification for the camera scanner. Pure JVM (no Android
 * dependency) so it stays unit-testable.
 *
 * FIDO hybrid/caBLE QR codes (desktop sign-in via the passwords app) decode to
 * a `FIDO:/...` URI. Those get a standalone passkey action instead of the
 * generic open-URL row, so less-technical users know what it does.
 */

/** Which caBLE operation the desktop side is asking for. */
enum class FidoRequestType {
    /** `mc` (makeCredential): register a new passkey on this device. */
    CREATE,

    /** `ga` (getAssertion): sign in with an existing passkey. */
    SIGN_IN,

    /** No usable hint — the button falls back to a neutral label. */
    UNKNOWN,
}

/**
 * Whether [text] is a FIDO deep link (`FIDO:/...`, case-insensitive —
 * desktop Chrome renders it uppercase, which never matches an intent-filter
 * until the scheme is lowercased at fire time).
 */
fun isFidoDeepLink(text: String): Boolean =
    text.trim().startsWith("FIDO:", ignoreCase = true)

/**
 * Which operation a scanned FIDO [text] is asking for, read off the QR's
 * request-type hint (CBOR map key 5: `"ga"` = getAssertion/sign-in, `"mc"` =
 * makeCredential/create). Returns [FidoRequestType.UNKNOWN] when the payload
 * can't be decoded — the key is optional per the CTAP 2.2 hybrid-transport
 * spec, and some initiators omit it.
 */
fun fidoRequestType(text: String): FidoRequestType {
    val hint = fidoRequestTypeHint(text) ?: return FidoRequestType.UNKNOWN
    return when (hint) {
        "mc" -> FidoRequestType.CREATE
        "ga" -> FidoRequestType.SIGN_IN
        else -> FidoRequestType.UNKNOWN
    }
}

// --- caBLE v2 QR digit/CBOR decode (mirrors passwords' CableQrData) ---

/** bytes-in-partial-chunk -> number of decimal digits (index 1..6 valid). */
private val PARTIAL_CHUNK_DIGITS = intArrayOf(0, 3, 5, 8, 10, 13, 15, 0)
private const val CHUNK_BYTES = 7
private const val CHUNK_DIGITS = 17
private const val BYTE_MASK = 0xFF
private const val BYTE_SHIFT = 8

/** Reads CBOR map key 5 (request-type hint) from a FIDO URI, or null. */
private fun fidoRequestTypeHint(uri: String): String? {
    val trimmed = uri.trim()
    val body = when {
        trimmed.startsWith("FIDO:/") -> trimmed.removePrefix("FIDO:/")
        trimmed.startsWith("fido:/") -> trimmed.removePrefix("fido:/")
        trimmed.startsWith("FIDO:", ignoreCase = true) -> trimmed.substringAfter(':')
        else -> return null
    }.trim()
    if (body.isEmpty() || !body.all { it in '0'..'9' }) return null
    val cbor = try {
        digitDecode(body)
    } catch (_: IllegalArgumentException) {
        return null
    }
    return readRequestHint(cbor)
}

/** Decodes the compact base-10 digit encoding back into raw CBOR bytes. */
private fun digitDecode(digits: String): ByteArray {
    val out = ArrayList<Byte>(digits.length)
    var i = 0
    while (digits.length - i >= CHUNK_DIGITS) {
        appendLittleEndian(out, digits.substring(i, i + CHUNK_DIGITS).toLong(), CHUNK_BYTES)
        i += CHUNK_DIGITS
    }
    val remaining = digits.length - i
    if (remaining > 0) {
        val byteCount = PARTIAL_CHUNK_DIGITS.indexOf(remaining)
        require(byteCount in 1..6) { "Invalid trailing digit count: $remaining" }
        appendLittleEndian(out, digits.substring(i).toLong(), byteCount)
    }
    return out.toByteArray()
}

private fun appendLittleEndian(out: MutableList<Byte>, value: Long, byteCount: Int) {
    var v = value
    repeat(byteCount) {
        out.add((v and BYTE_MASK.toLong()).toByte())
        v = v ushr BYTE_SHIFT
    }
}

/**
 * Minimal CBOR scan for map key 5's text value. Walks the top-level map
 * without a full decoder — only unsigned-int keys, byte/text strings and
 * small ints are stepped over; anything else aborts to null.
 */
private fun readRequestHint(cbor: ByteArray): String? {
    val r = CborCursor(cbor)
    var result: String? = null
    if (r.major() == 5) {
        // Top level must be a map; key 5 must be a text string.
        val entries = r.argument()
        if (entries != null && entries >= 0 && entries <= cbor.size) {
            result = findRequestHint(r, entries)
        }
    }
    return result
}

private fun findRequestHint(r: CborCursor, entries: Long): String? {
    var result: String? = null
    var index = 0L
    var searching = true
    while (searching && index < entries) {
        val keyMajor = r.major()
        val key = if (keyMajor != null) r.argument() else null
        if (key == null) {
            searching = false
        } else if (keyMajor == 0 && key == 5L) {
            result = readTextValue(r)
            searching = false
        } else if (!r.skip()) {
            searching = false
        }
        index++
    }
    return result
}

private fun readTextValue(r: CborCursor): String? {
    val len = if (r.major() == 3) r.argument() else null
    return len?.let { r.text(it) }
}

/** Tiny CBOR cursor over definite-length items only. */
private class CborCursor(private val bytes: ByteArray) {
    var pos = 0

    fun major(): Int? {
        if (pos >= bytes.size) return null
        return (bytes[pos].toInt() and BYTE_MASK) ushr 5
    }

    /** Reads the initial byte's argument; null on indefinite lengths. */
    fun argument(): Long? {
        if (pos >= bytes.size) return null
        val minor = bytes[pos++].toInt() and 0x1F
        return when (minor) {
            in 0..23 -> minor.toLong()
            24 -> byte().toLong() and BYTE_MASK.toLong()
            25 -> uInt(2)
            26 -> uInt(4)
            27 -> uInt(8)
            else -> null
        }
    }

    fun text(len: Long): String? {
        if (len < 0 || len > Int.MAX_VALUE || pos + len > bytes.size) return null
        return String(bytes.copyOfRange(pos, (pos + len).toInt()), Charsets.UTF_8)
            .also { pos += len.toInt() }
    }

    /** Steps over one item; false when the shape is unsupported. */
    fun skip(): Boolean {
        val major = major() ?: return false
        val arg = argument() ?: return false
        return when (major) {
            0, 1 -> true
            2, 3 -> step(arg)
            4 -> repeatOk(arg) { skip() }
            5 -> repeatOk(arg * 2) { skip() }
            7 -> true
            else -> false
        }
    }

    private fun repeatOk(n: Long, fn: () -> Boolean): Boolean {
        if (n < 0 || n > bytes.size) return false
        repeat(n.toInt()) { if (!fn()) return false }
        return true
    }

    private fun step(n: Long): Boolean {
        if (n < 0 || n > Int.MAX_VALUE || pos + n > bytes.size) return false
        pos += n.toInt()
        return true
    }

    private fun byte(): Byte {
        if (pos >= bytes.size) throw IndexOutOfBoundsException("CBOR cursor past end at $pos of ${bytes.size}")
        return bytes[pos++]
    }

    private fun uInt(n: Int): Long? {
        if (pos + n > bytes.size) return null
        var v = 0L
        repeat(n) { v = (v shl BYTE_SHIFT) or (byte().toLong() and BYTE_MASK.toLong()) }
        return v
    }
}
