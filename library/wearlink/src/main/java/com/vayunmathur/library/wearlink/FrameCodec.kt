package com.vayunmathur.library.wearlink

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Length-prefixed framing: `[u32 BE payload length][payload]`, payload is
 * `[u8 type][u64 id][u16 path length][path UTF-8][body]`. Length-prefixing
 * (not delimiter scanning) so binary bodies need no escaping, and a corrupt
 * length fails closed instead of desynchronising the stream silently.
 */
object FrameCodec {
    private const val HEADER_BYTES = 4
    private const val FIXED_PAYLOAD = 1 + 8 + 2

    fun encode(frame: WearLinkFrame, out: OutputStream) {
        val pathBytes = frame.path.encodeToByteArray()
        require(pathBytes.size <= 0xFFFF) { "path too long" }
        val payloadSize = FIXED_PAYLOAD + pathBytes.size + frame.body.size
        require(payloadSize <= WearLinkProtocol.MAX_FRAME_PAYLOAD) {
            "frame payload $payloadSize exceeds ${WearLinkProtocol.MAX_FRAME_PAYLOAD}"
        }
        val header = ByteArray(HEADER_BYTES)
        header[0] = (payloadSize ushr 24).toByte()
        header[1] = (payloadSize ushr 16).toByte()
        header[2] = (payloadSize ushr 8).toByte()
        header[3] = payloadSize.toByte()
        out.write(header)
        out.write(frame.type.code)
        writeLong(out, frame.id)
        out.write((pathBytes.size ushr 8) and 0xFF)
        out.write(pathBytes.size and 0xFF)
        out.write(pathBytes)
        out.write(frame.body)
        out.flush()
    }

    /** Encoded frame bytes (tests, in-memory transports). */
    fun encodeToBytes(frame: WearLinkFrame): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        encode(frame, out)
        return out.toByteArray()
    }

    /** Next frame, or null on clean EOF before a header. Throws on corruption. */
    fun decode(input: InputStream): WearLinkFrame? {
        val data = DataInputStream(input)
        val header = ByteArray(HEADER_BYTES)
        var read = 0
        while (read < HEADER_BYTES) {
            val n = data.read(header, read, HEADER_BYTES - read)
            if (n < 0) {
                if (read == 0) return null
                throw EOFException("eof inside frame header")
            }
            read += n
        }
        val length = ((header[0].toInt() and 0xFF) shl 24) or
            ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or
            (header[3].toInt() and 0xFF)
        if (length < FIXED_PAYLOAD || length > WearLinkProtocol.MAX_FRAME_PAYLOAD) {
            throw IOException("invalid frame length $length")
        }
        val payload = ByteArray(length)
        data.readFully(payload)
        val type = FrameType.fromCode(payload[0].toInt() and 0xFF)
            ?: throw IOException("unknown frame type ${payload[0]}")
        var offset = 1
        val id = readLong(payload, offset); offset += 8
        val pathLength = ((payload[offset].toInt() and 0xFF) shl 8) or
            (payload[offset + 1].toInt() and 0xFF); offset += 2
        if (offset + pathLength > payload.size) throw IOException("path overruns frame")
        val path = payload.decodeToString(offset, offset + pathLength)
        val body = payload.copyOfRange(offset + pathLength, payload.size)
        return WearLinkFrame(type, id, path, body)
    }

    private fun writeLong(out: OutputStream, value: Long) {
        for (shift in 56 downTo 0 step 8) out.write(((value ushr shift) and 0xFF).toInt())
    }

    internal fun readLong(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return value
    }

    internal fun writeLong(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) bytes[offset + i] = ((value ushr (56 - i * 8)) and 0xFF).toByte()
    }
}
