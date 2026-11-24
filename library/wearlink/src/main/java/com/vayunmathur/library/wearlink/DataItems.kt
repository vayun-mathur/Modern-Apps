package com.vayunmathur.library.wearlink

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Path-keyed state documents (the DataClient replacement). A document is
 * chunked into DATA frames carrying generation + index + count + total size
 * + SHA-256 — the same generation/chunk discipline the contacts snapshot
 * already used over DataItems. The receiver applies a document only when
 * every chunk of one generation is present and the hash verifies; partial
 * or stale generations change nothing. Unlike DataItems there is no 100 KB
 * per-item cap and no system-side persistence: the document lives only
 * while a session delivers it, and each app persists what it receives
 * itself (as all three wear apps already do).
 */
data class DataChunk(
    val generation: Long,
    val index: Int,
    val count: Int,
    val totalSize: Long,
    val sha256: String,
    val bytes: ByteArray,
) {
    fun encodeBody(): ByteArray {
        val sha = sha256.encodeToByteArray()
        val out = ByteArrayOutputStream()
        val fixed = ByteArray(8 + 4 + 4 + 8 + 2)
        FrameCodec.writeLong(fixed, 0, generation)
        writeInt(fixed, 8, index); writeInt(fixed, 12, count)
        FrameCodec.writeLong(fixed, 16, totalSize)
        fixed[24] = ((sha.size ushr 8) and 0xFF).toByte()
        fixed[25] = (sha.size and 0xFF).toByte()
        out.write(fixed); out.write(sha); out.write(bytes)
        return out.toByteArray()
    }

    override fun equals(other: Any?): Boolean =
        other is DataChunk && generation == other.generation && index == other.index &&
            count == other.count && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = (generation * 31 + index).hashCode()

    companion object {
        fun decodeBody(body: ByteArray): DataChunk? = runCatching {
            if (body.size < 26) return null
            val generation = FrameCodec.readLong(body, 0)
            val index = readInt(body, 8)
            val count = readInt(body, 12)
            val total = FrameCodec.readLong(body, 16)
            val shaLen = ((body[24].toInt() and 0xFF) shl 8) or (body[25].toInt() and 0xFF)
            if (26 + shaLen > body.size) return null
            val sha = body.decodeToString(26, 26 + shaLen)
            DataChunk(generation, index, count, total, sha, body.copyOfRange(26 + shaLen, body.size))
        }.getOrNull()

        internal fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
            for (i in 0 until 4) bytes[offset + i] = ((value ushr (24 - i * 8)) and 0xFF).toByte()
        }

        internal fun readInt(bytes: ByteArray, offset: Int): Int {
            var v = 0
            for (i in 0 until 4) v = (v shl 8) or (bytes[offset + i].toInt() and 0xFF)
            return v
        }
    }
}

object DataItemCodec {
    fun chunk(path: String, generation: Long, payload: ByteArray): List<WearLinkFrame> {
        require(payload.size <= WearLinkProtocol.MAX_DATA_ITEM_BYTES) { "data item too large" }
        val sha = WearLinkHandshake.sha256Hex(payload)
        val count = maxOf(1, (payload.size + WearLinkProtocol.DATA_CHUNK_BYTES - 1) /
            WearLinkProtocol.DATA_CHUNK_BYTES)
        return (0 until count).map { index ->
            val from = index * WearLinkProtocol.DATA_CHUNK_BYTES
            val slice = payload.copyOfRange(
                from, minOf(payload.size, from + WearLinkProtocol.DATA_CHUNK_BYTES),
            )
            WearLinkFrame(
                FrameType.DATA, generation, path,
                DataChunk(generation, index, count, payload.size.toLong(), sha, slice).encodeBody(),
            )
        }
    }
}

/** Per-path reassembler. Pure; feed DATA frames, get complete documents. */
class DataReassembler {
    private class Pending(
        val generation: Long,
        val count: Int,
        val totalSize: Long,
        val sha256: String,
    ) {
        val chunks = mutableMapOf<Int, ByteArray>()
    }

    private val pending = mutableMapOf<String, Pending>()
    private val appliedGeneration = mutableMapOf<String, Long>()

    /** Completed payload for [path], or null while incomplete/stale/corrupt. */
    fun onFrame(frame: WearLinkFrame): ByteArray? {
        if (frame.type != FrameType.DATA) return null
        val chunk = DataChunk.decodeBody(frame.body) ?: return null
        if (chunk.count <= 0 || chunk.index !in 0 until chunk.count) return null
        if (chunk.totalSize < 0 || chunk.totalSize > WearLinkProtocol.MAX_DATA_ITEM_BYTES) return null
        val last = appliedGeneration[frame.path] ?: Long.MIN_VALUE
        if (chunk.generation < last) return null
        val current = pending[frame.path]
        val state = if (current == null || current.generation != chunk.generation) {
            Pending(chunk.generation, chunk.count, chunk.totalSize, chunk.sha256)
                .also { pending[frame.path] = it }
        } else {
            current
        }
        if (state.count != chunk.count || state.sha256 != chunk.sha256) return null
        state.chunks[chunk.index] = chunk.bytes
        if (state.chunks.size < state.count) return null
        val assembled = ByteArrayOutputStream()
        for (i in 0 until state.count) assembled.write(state.chunks[i] ?: return null)
        val bytes = assembled.toByteArray()
        pending.remove(frame.path)
        if (bytes.size.toLong() != state.totalSize) return null
        if (WearLinkHandshake.sha256Hex(bytes) != state.sha256) return null
        appliedGeneration[frame.path] = chunk.generation
        return bytes
    }

    fun lastAppliedGeneration(path: String): Long? = appliedGeneration[path]
}

/** SHA-256 streaming helper shared by stream send/receive. */
object Sha256 {
    fun hex(bytes: ByteArray): String = WearLinkHandshake.sha256Hex(bytes)

    fun hex(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n > 0) digest.update(buffer, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
