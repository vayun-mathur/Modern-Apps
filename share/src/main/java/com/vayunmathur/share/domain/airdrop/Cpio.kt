package com.vayunmathur.share.domain.airdrop

import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * cpio archive codec — the `/Upload` body (`application/x-cpio`).
 *
 * Writing produces the portable odc format (`070707`), which is what Apple devices emit and
 * accept. Reading also takes newc (`070701`, and `070702` with an ignored checksum), since
 * Mosey's own reader does.
 */
object Cpio {

    private const val ODC_MAGIC = "070707"
    private const val NEWC_MAGIC = "070701"
    private const val NEWC_CRC_MAGIC = "070702"
    private const val TRAILER = "TRAILER!!!"
    private const val ODC_HEADER_LEN = 76
    private const val NEWC_HEADER_LEN = 110

    const val MODE_DIR = 0x41ED // 040755
    const val MODE_FILE = 0x81A4 // 0100644
    private const val MODE_TYPE_MASK = 0xF000
    private const val MODE_TYPE_DIR = 0x4000
    private const val MODE_TYPE_FILE = 0x8000

    /** Longest name accepted on read; a hostile header must not make us allocate gigabytes. */
    private const val MAX_NAME = 4096

    /** Format Exception: a string implementation for format exception. */
    class FormatException(message: String) : IOException(message)

    /** Streams an odc archive. Close (or [finish]) to write the trailer. */
    class Writer(private val out: OutputStream) : AutoCloseable {
        private var ino = 1L
        private var finished = false

        fun directory(name: String, mtimeSeconds: Long = nowSeconds()) {
            header(name, MODE_DIR, 0, mtimeSeconds)
        }

        /** Writes a file entry whose content is exactly [size] bytes copied from [content]. */
        fun file(name: String, size: Long, content: InputStream, mtimeSeconds: Long = nowSeconds()) {
            header(name, MODE_FILE, size, mtimeSeconds)
            val copied = copyExactly(content, out, size)
            if (copied != size) throw IOException("$name: expected $size bytes, got $copied")
        }

        fun finish() {
            if (finished) return
            finished = true
            header(TRAILER, 0, 0, 0)
            out.flush()
        }

        override fun close() = finish()

        private fun header(name: String, mode: Int, size: Long, mtime: Long) {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val sb = StringBuilder(ODC_HEADER_LEN)
                .append(ODC_MAGIC)
                .append(octal(0, 6)) // dev
                .append(octal(if (name == TRAILER) 0 else ino++, 6))
                .append(octal(mode.toLong(), 6))
                .append(octal(0, 6)) // uid
                .append(octal(0, 6)) // gid
                .append(octal(1, 6)) // nlink
                .append(octal(0, 6)) // rdev
                .append(octal(mtime, 11))
                .append(octal(nameBytes.size + 1L, 6))
                .append(octal(size, 11))
            out.write(sb.toString().toByteArray(Charsets.US_ASCII))
            out.write(nameBytes)
            out.write(0)
        }

        private fun octal(v: Long, width: Int): String {
            val s = java.lang.Long.toOctalString(v)
            if (s.length > width) throw IOException("value $v does not fit $width octal digits")
            return s.padStart(width, '0')
        }
    }

    /** Type representing entry. */
    class Entry(
        val name: String,
        val mode: Int,
        val size: Long,
        val mtimeSeconds: Long,
    ) {
        val isDirectory: Boolean get() = mode and MODE_TYPE_MASK == MODE_TYPE_DIR
        val isFile: Boolean get() = mode and MODE_TYPE_MASK == MODE_TYPE_FILE
    }

    /**
     * Pull-style reader. Call [next] for each entry; the returned stream yields exactly that
     * entry's content and is drained automatically by the following [next] call.
     */
    class Reader(private val input: InputStream) {
        private var current: BoundedStream? = null
        private var pad = 0
        private var done = false

        fun next(): Pair<Entry, InputStream>? {
            if (done) return null
            current?.let { it.skipRest(); skipFully(input, pad.toLong()) }
            current = null
            val magic = String(readFully(6), Charsets.US_ASCII)
            val (entry, namePadAfter, dataPad) = when (magic) {
                ODC_MAGIC -> readOdc()
                NEWC_MAGIC, NEWC_CRC_MAGIC -> readNewc()
                else -> throw FormatException("bad cpio magic '$magic'")
            }
            skipFully(input, namePadAfter.toLong())
            if (entry.name == TRAILER) {
                done = true
                return null
            }
            val stream = BoundedStream(input, entry.size)
            current = stream
            pad = dataPad
            return entry to stream
        }

        private fun readOdc(): Triple<Entry, Int, Int> {
            val h = String(readFully(ODC_HEADER_LEN - 6), Charsets.US_ASCII)
            fun field(start: Int, len: Int): Long =
                h.substring(start, start + len).toLongOrNull(8) ?: throw FormatException("bad odc field")
            val mode = field(12, 6).toInt()
            val mtime = field(42, 11)
            val nameSize = field(53, 6).toInt()
            val size = field(59, 11)
            val name = readName(nameSize)
            return Triple(Entry(name, mode, size, mtime), 0, 0)
        }

        private fun readNewc(): Triple<Entry, Int, Int> {
            val h = String(readFully(NEWC_HEADER_LEN - 6), Charsets.US_ASCII)
            fun field(index: Int): Long =
                h.substring(index * 8, index * 8 + 8).toLongOrNull(16) ?: throw FormatException("bad newc field")
            val mode = field(1).toInt()
            val mtime = field(5)
            val size = field(6)
            val nameSize = field(11).toInt()
            val name = readName(nameSize)
            val namePad = align4(NEWC_HEADER_LEN + nameSize)
            return Triple(Entry(name, mode, size, mtime), namePad, align4(size))
        }

        private fun readName(nameSize: Int): String {
            if (nameSize !in 1..MAX_NAME) throw FormatException("bad name size $nameSize")
            val raw = readFully(nameSize)
            return String(raw, 0, nameSize - 1, Charsets.UTF_8)
        }

        private fun readFully(n: Int): ByteArray {
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(buf, off, n - off)
                if (r < 0) throw EOFException("truncated cpio")
                off += r
            }
            return buf
        }
    }

    private class BoundedStream(input: InputStream, private var remaining: Long) : FilterInputStream(input) {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = super.read()
            if (b < 0) throw EOFException("truncated cpio entry")
            remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val r = super.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (r < 0) throw EOFException("truncated cpio entry")
            remaining -= r
            return r
        }

        override fun available(): Int = minOf(super.available().toLong(), remaining).toInt()

        override fun close() = Unit

        fun skipRest() = skipFully(`in`, remaining).also { remaining = 0 }
    }

    private fun align4(n: Long): Int = ((4 - (n % 4)) % 4).toInt()
    private fun align4(n: Int): Int = align4(n.toLong())

    private fun skipFully(input: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(8192)
        while (left > 0) {
            val r = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (r < 0) throw EOFException("truncated cpio")
            left -= r
        }
    }

    private fun copyExactly(input: InputStream, out: OutputStream, size: Long): Long {
        val buf = ByteArray(64 * 1024)
        var copied = 0L
        while (copied < size) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), size - copied).toInt())
            if (r < 0) break
            out.write(buf, 0, r)
            copied += r
        }
        return copied
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000
}
