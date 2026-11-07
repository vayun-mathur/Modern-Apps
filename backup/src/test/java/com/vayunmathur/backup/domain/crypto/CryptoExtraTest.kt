package com.vayunmathur.backup.domain.crypto

import com.vayunmathur.e2ee.E2ee
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CryptoExtraTest {
    private val key = ByteArray(32) { it.toByte() }
    private val crypto = Crypto(key)

    @Test fun streamRoundTripExactSegmentAndChunkedReads() {
        val plain = ByteArray(Crypto.SEGMENT_SIZE) { (it % 251).toByte() }
        val blob = crypto.encrypt(plain)
        assertContentEquals(plain, crypto.decrypt(blob))
        // Chunked input stream (returns few bytes per read) still fills segments.
        val chunked = object : java.io.InputStream() {
            private var pos = 0
            override fun read(): Int = if (pos < plain.size) plain[pos++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= plain.size) return -1
                val n = minOf(7, len, plain.size - pos)
                plain.copyInto(b, off, pos, pos + n); pos += n; return n
            }
        }
        val out = ByteArrayOutputStream(); crypto.encrypt(chunked, out)
        assertContentEquals(plain, crypto.decrypt(out.toByteArray()))
        val bout = ByteArrayOutputStream(); crypto.encrypt(ByteArrayInputStream("abc".toByteArray()), bout)
        val dout = ByteArrayOutputStream(); crypto.decrypt(ByteArrayInputStream(bout.toByteArray()), dout)
        assertEquals("abc", dout.toString())
    }

    @Test fun rejectsEmptyBadVersionAndTruncation() {
        assertFailsWith<IOException> { crypto.decrypt(ByteArray(0)) }
        val badVersion = crypto.encrypt("x".toByteArray()); badVersion[0] = 99
        assertFailsWith<IllegalArgumentException> { crypto.decrypt(badVersion) }
        val blob = crypto.encrypt("hello world hello".toByteArray())
        assertFailsWith<Exception> { crypto.decrypt(blob.copyOf(blob.size - 3)) }
        assertFailsWith<Exception> { crypto.decrypt(blob.copyOf(3)) } // truncated header
        val badLen = byteArrayOf(1, 0x7F, 0, 0, 0, 1, 2)
        assertFailsWith<IOException> { crypto.decrypt(badLen) }
        val zeroLen = byteArrayOf(1, 0, 0, 0, 0)
        assertFailsWith<IOException> { crypto.decrypt(zeroLen) }
    }

    @Test fun detectsReorderedSegments() {
        val plain = ByteArray(Crypto.SEGMENT_SIZE * 2 + 10) { (it * 7).toByte() }
        val blob = crypto.encrypt(plain)
        // Parse segments, swap first two, expect failure (index check or GCM).
        val segs = mutableListOf<ByteArray>()
        var pos = 1
        while (pos < blob.size) {
            val len = ((blob[pos].toInt() and 0xFF) shl 24) or ((blob[pos+1].toInt() and 0xFF) shl 16) or
                ((blob[pos+2].toInt() and 0xFF) shl 8) or (blob[pos+3].toInt() and 0xFF)
            segs.add(blob.copyOfRange(pos, pos + 4 + len)); pos += 4 + len
        }
        assertEquals(4, segs.size) // 3 data segments + terminator
        val swapped = ByteArrayOutputStream()
        swapped.write(Crypto.VERSION); swapped.write(segs[1]); swapped.write(segs[0]); swapped.write(segs[2])
        assertFailsWith<Exception> { crypto.decrypt(swapped.toByteArray()) }
        assertFalse(blob.contentEquals(plain))
        assertTrue(blob.size > plain.size)
    }

    private fun segmentBounds(blob: ByteArray): List<Int> {
        val bounds = mutableListOf(1)
        var pos = 1
        while (pos < blob.size) {
            val len = ((blob[pos].toInt() and 0xFF) shl 24) or ((blob[pos + 1].toInt() and 0xFF) shl 16) or
                ((blob[pos + 2].toInt() and 0xFF) shl 8) or (blob[pos + 3].toInt() and 0xFF)
            pos += 4 + len; bounds.add(pos)
        }
        return bounds
    }

    @Test fun detectsTruncationAtSegmentBoundary() {
        val plain = ByteArray(Crypto.SEGMENT_SIZE + 10) { (it * 3).toByte() }
        val blob = crypto.encrypt(plain)
        val bounds = segmentBounds(blob)
        assertEquals(4, bounds.size) // version + 2 data segments + terminator
        // Cut exactly at a segment boundary (drop the terminator, then also
        // the last data segment): previously this decrypted silently to a
        // shorter plaintext.
        assertFailsWith<IOException> { crypto.decrypt(blob.copyOf(bounds[2])) }
        assertFailsWith<IOException> { crypto.decrypt(blob.copyOf(bounds[1])) }
        assertFailsWith<IOException> { crypto.decrypt(byteArrayOf(Crypto.VERSION.toByte())) }
    }

    @Test fun legacyV1StreamStillDecrypts() {
        val plain = "legacy backup".toByteArray()
        val segPlain = ByteArray(4 + plain.size) // index bytes default to 0
        plain.copyInto(segPlain, 4)
        val segment = E2ee.aesEncrypt(key, segPlain)
        val blob = ByteArrayOutputStream()
        blob.write(1)
        blob.write(byteArrayOf((segment.size ushr 24).toByte(), (segment.size ushr 16).toByte(), (segment.size ushr 8).toByte(), segment.size.toByte()))
        blob.write(segment)
        assertContentEquals(plain, crypto.decrypt(blob.toByteArray()))
    }

    @Test fun rejectsShortAndLongKeys() {
        assertFailsWith<IllegalArgumentException> { Crypto(ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { Crypto(ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { Crypto(ByteArray(33)) }
    }
}
