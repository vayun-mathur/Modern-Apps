package com.vayunmathur.backup.domain.crypto

import com.vayunmathur.e2ee.E2ee
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Segmented AES-256-GCM encryption for backup blobs. A payload is split into
 * fixed-size segments, each sealed independently with [E2ee.aesEncrypt] (fresh
 * random IV, `iv||ciphertext||tag`) under the same master key. Each segment's
 * plaintext is prefixed with its 4-byte index, so any reordering, duplication, or
 * truncation of segments is detected on decrypt (in addition to GCM's per-segment
 * integrity). Wrong key or tampering surfaces as an [javax.crypto.AEADBadTagException].
 *
 * On-disk stream layout (version 2):
 * ```
 * [1 byte  version = 2]
 * repeated: [4-byte BE segmentLen][segmentLen bytes: E2ee(iv||ct||tag)]
 * [terminator segment: plaintext is just the 4-byte next index, no data]
 * ```
 * where each data segment plaintext = `[4-byte BE index][data chunk ≤ SEGMENT_SIZE]`.
 * The terminator makes truncation at a segment boundary detectable: without it, a
 * stream cut short between segments is indistinguishable from a complete shorter
 * backup. Version 1 streams (no terminator) are still accepted on decrypt for
 * compatibility with backups written before the terminator existed, but new
 * writes are always version 2.
 *
 * Ported concept (Seedvault segmented backup format) — see backup/LICENSE-Seedvault.
 */
class Crypto(private val masterKey: ByteArray) {
    init {
        require(masterKey.size == 32) { "master key must be 32 bytes (AES-256)" }
    }

    fun encrypt(input: InputStream, output: OutputStream) {
        output.write(VERSION)
        val buffer = ByteArray(SEGMENT_SIZE)
        var index = 0
        while (true) {
            val read = fill(input, buffer)
            if (read == 0) break
            val plain = ByteArray(4 + read)
            writeIntBE(plain, 0, index)
            buffer.copyInto(plain, 4, 0, read)
            val segment = E2ee.aesEncrypt(masterKey, plain)
            writeIntTo(output, segment.size)
            output.write(segment)
            index++
            if (read < SEGMENT_SIZE) break
        }
        // Terminator: a segment whose plaintext is only the next index. Its
        // presence proves the stream was not cut short at a segment boundary.
        val terminator = ByteArray(4)
        writeIntBE(terminator, 0, index)
        val segment = E2ee.aesEncrypt(masterKey, terminator)
        writeIntTo(output, segment.size)
        output.write(segment)
        output.flush()
    }

    fun decrypt(input: InputStream, output: OutputStream) {
        val version = input.read()
        if (version < 0) throw IOException("empty backup stream")
        require(version == VERSION || version == VERSION_V1) {
            "unsupported backup version: $version"
        }
        var expectedIndex = 0
        while (true) {
            val length = readIntFrom(input) ?: run {
                // Clean EOF between segments. Only a legacy v1 stream may end
                // here; a v2 stream must end with its terminator segment.
                if (version == VERSION) throw IOException("truncated backup stream: missing terminator")
                break
            }
            if (length <= 0 || length > MAX_SEGMENT_BLOB) throw IOException("invalid segment length: $length")
            val segment = ByteArray(length)
            if (fill(input, segment) != length) throw IOException("truncated segment")
            val plain = E2ee.aesDecrypt(masterKey, segment)
            if (plain.size < 4) throw IOException("corrupt segment")
            val index = readIntBE(plain, 0)
            if (index != expectedIndex) throw IOException("segment out of order: $index != $expectedIndex")
            if (plain.size == 4) {
                // Terminator segment (v2 only; a v1 writer never emitted one).
                if (version == VERSION_V1) throw IOException("corrupt segment")
                if (readIntFrom(input) != null) throw IOException("trailing data after terminator")
                output.flush()
                return
            }
            output.write(plain, 4, plain.size - 4)
            expectedIndex++
        }
        output.flush()
    }

    fun encrypt(plain: ByteArray): ByteArray =
        ByteArrayOutputStream().also { encrypt(ByteArrayInputStream(plain), it) }.toByteArray()

    fun decrypt(blob: ByteArray): ByteArray =
        ByteArrayOutputStream().also { decrypt(ByteArrayInputStream(blob), it) }.toByteArray()

    companion object {
        const val VERSION = 2
        private const val VERSION_V1 = 1
        const val SEGMENT_SIZE = 1 shl 20 // 1 MiB plaintext per segment
        // Sanity bound so a corrupt length can't request a huge allocation.
        private const val MAX_SEGMENT_BLOB = SEGMENT_SIZE + 1024

        /** Reads exactly [buf].size bytes, or fewer at EOF; returns bytes read. */
        private fun fill(input: InputStream, buf: ByteArray): Int {
            var off = 0
            while (off < buf.size) {
                val r = input.read(buf, off, buf.size - off)
                if (r < 0) break
                off += r
            }
            return off
        }

        private fun writeIntBE(buf: ByteArray, offset: Int, value: Int) {
            buf[offset] = (value ushr 24).toByte()
            buf[offset + 1] = (value ushr 16).toByte()
            buf[offset + 2] = (value ushr 8).toByte()
            buf[offset + 3] = value.toByte()
        }

        private fun readIntBE(buf: ByteArray, offset: Int): Int =
            ((buf[offset].toInt() and 0xFF) shl 24) or
                ((buf[offset + 1].toInt() and 0xFF) shl 16) or
                ((buf[offset + 2].toInt() and 0xFF) shl 8) or
                (buf[offset + 3].toInt() and 0xFF)

        private fun writeIntTo(output: OutputStream, value: Int) {
            output.write(value ushr 24)
            output.write(value ushr 16)
            output.write(value ushr 8)
            output.write(value)
        }

        /** Reads a 4-byte BE int, or null at a clean EOF between segments. */
        private fun readIntFrom(input: InputStream): Int? {
            val b0 = input.read()
            if (b0 < 0) return null
            val b1 = input.read()
            val b2 = input.read()
            val b3 = input.read()
            if (b1 < 0 || b2 < 0 || b3 < 0) throw IOException("truncated segment header")
            return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        }
    }
}
