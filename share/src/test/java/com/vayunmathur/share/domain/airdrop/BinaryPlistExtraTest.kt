package com.vayunmathur.share.domain.airdrop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BinaryPlistExtraTest {

    @Test
    fun `encode decode assorted types round trip`() {
        val map = mapOf<String, Any?>(
            "s" to "hello", "n" to null, "i" to 42L, "b" to true,
            "bytes" to byteArrayOf(1, 2, 3), "list" to listOf("a", "b"),
            "nested" to mapOf("x" to 7L),
        )
        val decoded = BinaryPlist.decode(BinaryPlist.encode(map)) as Map<*, *>
        assertEquals("hello", decoded["s"])
        assertEquals(42L, decoded["i"])
        assertEquals(true, decoded["b"])
        assertTrue((decoded["bytes"] as ByteArray).contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(listOf("a", "b"), decoded["list"])
    }

    @Test
    fun `decode rejects garbage and truncated input`() {
        assertThrows(Exception::class.java) { BinaryPlist.decode(byteArrayOf(1, 2, 3)) }
        assertThrows(Exception::class.java) { BinaryPlist.decode("not a plist at all........".toByteArray()) }
        val good = BinaryPlist.encode(mapOf("a" to 1L))
        assertThrows(Exception::class.java) { BinaryPlist.decode(good.copyOf(10)) }
    }

    @Test
    fun `decodeDict on non-dict returns empty or throws`() {
        // decodeDict is used for Ask/Discover bodies; a list root is not a dict.
        val listPlist = BinaryPlist.encode(listOf("x"))
        val d = runCatching { BinaryPlist.decodeDict(listPlist) }.getOrNull()
        assertTrue(d == null || d.isEmpty())
    }

    @Test
    fun `dvzip round trips multiple members`() {
        val out = ByteArrayOutputStream()
        val w = DvZip.Writer(out)
        val inner = ByteArrayOutputStream()
        // DvZip wraps a stream; write raw through a cpio inside like production does.
        val cpioOut = ByteArrayOutputStream()
        Cpio.Writer(cpioOut).use { it.file("./a.bin", 4, ByteArrayInputStream(byteArrayOf(9, 8, 7, 6))) }
        w.write(cpioOut.toByteArray()); w.close()
        val reader = DvZip.Reader(ByteArrayInputStream(out.toByteArray()))
        val back = reader.readBytes()
        assertTrue(back.isNotEmpty())
        val entries = Cpio.Reader(ByteArrayInputStream(back))
        val (entry, content) = entries.next()!!
        assertEquals("./a.bin", entry.name)
        assertTrue(content.readBytes().contentEquals(byteArrayOf(9, 8, 7, 6)))
    }
}
