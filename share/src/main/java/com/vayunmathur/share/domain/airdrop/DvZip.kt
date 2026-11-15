package com.vayunmathur.share.domain.airdrop

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * `application/x-dvzip` framing — a cpio archive cut into chunks, each sent as a 4-byte
 * big-endian length followed by that many bytes.
 *
 * A length whose top byte is zero marks a zlib-deflated chunk; a non-zero top byte marks a
 * stored chunk whose length is the low 24 bits. A zero length ends the stream. Matches
 * Mosey's `ChunkedInflaterInputStream` (reader) and its 128 KiB per-chunk deflater (writer).
 */
object DvZip {

    const val CHUNK_SIZE = 128 * 1024
    private const val STORED_FLAG = 0x80

    /** Longest chunk accepted on read, compressed or not, so a hostile length cannot OOM us. */
    private const val MAX_CHUNK = 16 * 1024 * 1024

    /** Writer: a output stream implementation for writer. */
    class Writer(private val out: OutputStream) : OutputStream() {
        private val buffer = ByteArray(CHUNK_SIZE)
        private var filled = 0
        private val deflater = Deflater()
        private val scratch = ByteArray(CHUNK_SIZE + CHUNK_SIZE / 8 + 64)
        private var closed = false

        override fun write(b: Int) {
            if (filled == buffer.size) emit()
            buffer[filled++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var left = len
            while (left > 0) {
                if (filled == buffer.size) emit()
                val n = minOf(left, buffer.size - filled)
                System.arraycopy(b, o, buffer, filled, n)
                filled += n
                o += n
                left -= n
            }
        }

        override fun flush() {
            emit()
            out.flush()
        }

        override fun close() {
            if (closed) return
            closed = true
            emit()
            deflater.end()
            out.flush()
        }

        private fun emit() {
            if (filled == 0) return
            deflater.reset()
            deflater.setInput(buffer, 0, filled)
            deflater.finish()
            var len = 0
            while (!deflater.finished()) {
                if (len == scratch.size) throw IOException("deflate output overflow")
                len += deflater.deflate(scratch, len, scratch.size - len)
            }
            writeLength(len)
            out.write(scratch, 0, len)
            filled = 0
        }

        private fun writeLength(len: Int) {
            out.write(len ushr 24 and 0xFF)
            out.write(len ushr 16 and 0xFF)
            out.write(len ushr 8 and 0xFF)
            out.write(len and 0xFF)
        }
    }

    /** Reader: a input stream implementation for reader. */
    class Reader(private val input: InputStream) : InputStream() {
        private val inflater = Inflater()
        private var chunk = ByteArray(0)
        private var pos = 0
        private var ended = false

        override fun read(): Int {
            if (!fill()) return -1
            return chunk[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, chunk.size - pos)
            System.arraycopy(chunk, pos, b, off, n)
            pos += n
            return n
        }

        override fun close() {
            inflater.end()
            input.close()
        }

        private fun fill(): Boolean {
            while (pos >= chunk.size) {
                if (ended) return false
                val header = readExactly(4) ?: run { ended = true; return false }
                val stored = (header[0].toInt() and 0xFF) != 0
                if (stored) header[0] = 0
                val len = ((header[0].toInt() and 0xFF) shl 24) or ((header[1].toInt() and 0xFF) shl 16) or
                    ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
                if (len == 0) {
                    ended = true
                    return false
                }
                if (len > MAX_CHUNK) throw IOException("dvzip chunk too large: $len")
                val body = readExactly(len) ?: throw EOFException("truncated dvzip chunk")
                chunk = if (stored) body else inflate(body)
                pos = 0
            }
            return true
        }

        private fun inflate(body: ByteArray): ByteArray {
            inflater.reset()
            inflater.setInput(body)
            val out = java.io.ByteArrayOutputStream(CHUNK_SIZE)
            val buf = ByteArray(8192)
            try {
                while (!inflater.finished()) {
                    val n = inflater.inflate(buf)
                    if (n == 0 && inflater.needsInput()) throw IOException("dvzip chunk ends mid-stream")
                    out.write(buf, 0, n)
                    if (out.size() > MAX_CHUNK) throw IOException("dvzip chunk inflates too large")
                }
            } catch (e: DataFormatException) {
                throw IOException("bad dvzip chunk", e)
            }
            return out.toByteArray()
        }

        /** Null on a clean EOF before the first byte; a truncation after that throws. */
        private fun readExactly(n: Int): ByteArray? {
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(buf, off, n - off)
                if (r < 0) {
                    if (off == 0) return null
                    throw EOFException("truncated dvzip")
                }
                off += r
            }
            return buf
        }
    }

    /** Stored-chunk length marker, exposed for tests that build a stream by hand. */
    internal fun storedHeader(len: Int): ByteArray = byteArrayOf(
        STORED_FLAG.toByte(),
        (len ushr 16 and 0xFF).toByte(),
        (len ushr 8 and 0xFF).toByte(),
        (len and 0xFF).toByte(),
    )
}
