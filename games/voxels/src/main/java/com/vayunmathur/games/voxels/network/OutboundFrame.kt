package com.vayunmathur.games.voxels.network

/**
 * Decoder for the binary Rust -> Kotlin outbound frame (`net_bin.rs`, JNI
 * `netDrainOutboundBin`) — the local representation that replaced draining
 * outbound `NetMsg`s as one JSON string.
 *
 * Why: a `ChunkData` payload is a raw VOX3 chunk (tens of KiB). As JSON every
 * byte became a number (`[0,255,...]`, ~3-4 bytes/byte), crossed JNI as a
 * String, and was parsed back number-by-number by `JSONArray` in the pump,
 * only to be re-serialised for signing. In this frame chunk payloads are raw
 * bytes; small variants keep their single-message serde JSON text.
 *
 * Scope boundary (do not cross): this frame is ONLY the local JNI leg. It is
 * never persisted, signed or sent to the relay. [Chunk.toOpsJson] reconstructs
 * the exact legacy signed-op JSON — `{"type":"ChunkData","dim":...,"data":[..]}`
 * with unsigned decimal bytes, in serde field order — so the strings that
 * [MultiplayerPump] signs, `PendingOps` persists/replays and `VoxelsSync`
 * AES-encrypts into `AppendMsg` payloads stay byte-identical, and older peers
 * keep parsing them. The inbound leg likewise stays a JSON string: the string
 * pushed to Rust is the already-verified signed `ops` payload itself.
 *
 * Frame (little-endian): `"VXB1"`, u32 count, then per message a kind byte:
 * 0 = signed JSON (u32 len + JSON), 1 = ChunkData (dim u8, cx i32, cz i32,
 * u32 len + raw bytes), 2 = PlayerTransform JSON (u32 len + JSON).
 */
object OutboundFrame {
    private const val KIND_JSON = 0
    private const val KIND_CHUNK = 1
    private const val KIND_TRANSFORM = 2
    private const val MAGIC_SIZE = 4
    private const val COUNT_SIZE = 4
    private const val CHUNK_HEADER_SIZE = 13 // dim + cx + cz + len
    private const val BYTE_MASK = 0xFF
    private const val BITS_PER_BYTE = 8
    private const val INT_BYTES = 4

    /** One drained outbound message, chunk payloads still binary. */
    sealed interface Msg {
        /** A world op to sign + relay: [text] is its single-message JSON. */
        data class SignedJson(val text: String) : Msg

        /** An ephemeral player transform: [text] is its single-message JSON. */
        data class TransformJson(val text: String) : Msg

        /** A full chunk: raw VOX3 [data], reconstructed to JSON only for signing. */
        class Chunk(val dim: Int, val cx: Int, val cz: Int, val data: ByteArray) : Msg {
            /**
             * The legacy signed-op JSON for this chunk, byte-identical to what
             * the old JSON drain + `JSONObject.toString()` produced: serde field
             * order, unsigned decimal bytes, no spaces.
             */
            fun toOpsJson(): String {
                val sb = StringBuilder(HEADER_ESTIMATE + data.size * CHARS_PER_BYTE)
                sb.append("{\"type\":\"ChunkData\",\"dim\":").append(dim)
                    .append(",\"cx\":").append(cx)
                    .append(",\"cz\":").append(cz)
                    .append(",\"data\":[")
                for (i in data.indices) {
                    if (i > 0) sb.append(',')
                    sb.append(data[i].toInt() and BYTE_MASK)
                }
                return sb.append("]}").toString()
            }

            override fun equals(other: Any?): Boolean =
                other is Chunk && dim == other.dim && cx == other.cx && cz == other.cz &&
                    data.contentEquals(other.data)

            override fun hashCode(): Int =
                HASH_SEED * (HASH_SEED * (HASH_SEED * dim + cx) + cz) + data.contentHashCode()

            private companion object {
                const val HEADER_ESTIMATE = 64
                const val CHARS_PER_BYTE = 4
                const val HASH_SEED = 31
            }
        }
    }

    /** Decodes a frame; any truncation/corruption/trailing bytes reads as empty (never throws). */
    fun decode(bytes: ByteArray): List<Msg> {
        val reader = Reader(bytes)
        val count = reader.headerCount() ?: return emptyList()
        val out = ArrayList<Msg>(count)
        var ok = true
        repeat(count) { if (ok) reader.msg()?.let { out += it } ?: run { ok = false } }
        return if (ok && reader.atEnd()) out else emptyList()
    }

    /** Bounds-checked little-endian cursor; every failure is null, never an exception. */
    private class Reader(private val bytes: ByteArray) {
        private var off = 0

        fun atEnd(): Boolean = off == bytes.size

        /** Validates the magic and returns the message count, or null if this is not a frame. */
        fun headerCount(): Int? {
            if (bytes.size < MAGIC_SIZE + COUNT_SIZE) return null
            if (!MAGIC.indices.all { bytes[it] == MAGIC[it] }) return null
            off = MAGIC_SIZE
            val count = int() ?: return null
            // Each message costs at least its kind byte, so count cannot exceed the rest.
            return count.takeIf { it >= 0 && it <= bytes.size - off }
        }

        fun msg(): Msg? = when (u8()) {
            KIND_CHUNK -> chunk()
            KIND_JSON -> int()?.let { len -> text(len)?.let { Msg.SignedJson(it) } }
            KIND_TRANSFORM -> int()?.let { len -> text(len)?.let { Msg.TransformJson(it) } }
            else -> null
        }

        private fun chunk(): Msg? {
            if (bytes.size - off < CHUNK_HEADER_SIZE) return null
            val dim = u8()
            val cx = int()
            val cz = int()
            val len = int()
            val data = len?.let { take(it) }
            return if (dim == null || cx == null || cz == null) null
            else data?.let { Msg.Chunk(dim, cx, cz, it) }
        }

        private fun text(len: Int): String? = take(len)?.decodeToString()

        private fun take(len: Int): ByteArray? =
            if (len < 0 || len > bytes.size - off) null
            else bytes.copyOfRange(off, off + len).also { off += len }

        private fun u8(): Int? =
            if (off >= bytes.size) null else (bytes[off++].toInt() and BYTE_MASK)

        private fun int(): Int? {
            val raw = take(INT_BYTES) ?: return null
            return (0 until INT_BYTES).fold(0) { acc, i ->
                acc or ((raw[i].toInt() and BYTE_MASK) shl (BITS_PER_BYTE * i))
            }
        }

        private companion object {
            val MAGIC = byteArrayOf('V'.code.toByte(), 'X'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())
        }
    }
}
