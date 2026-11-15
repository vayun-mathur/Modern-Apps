package com.vayunmathur.passwords.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DomainPureExtraTest {

    // ---- Cbor encoder: argument widths and containers ----

    @Test fun cborArgumentWidths() {
        fun ints(b: ByteArray) = b.map { it.toInt() and 0xFF }
        assertEquals(listOf(0x18, 0xFF), ints(Cbor.encode(255)))
        assertEquals(listOf(0x19, 0x01, 0x00), ints(Cbor.encode(256)))
        assertEquals(listOf(0x19, 0xFF, 0xFF), ints(Cbor.encode(65_535)))
        assertEquals(listOf(0x1A, 0x00, 0x01, 0x00, 0x00), ints(Cbor.encode(65_536)))
        assertEquals(listOf(0x1A, 0xFF, 0xFF, 0xFF, 0xFF), ints(Cbor.encode(4_294_967_295L)))
        // Negative: -24 encodes as 0x37 (major 1, arg 23), -256 as 0x38 0xFF.
        assertEquals(listOf(0x37), ints(Cbor.encode(-24)))
        assertEquals(listOf(0x38, 0xFF), ints(Cbor.encode(-256)))
        assertEquals(listOf(0x39, 0x01, 0x00), ints(Cbor.encode(-257)))
    }

    @Test fun cborEmptyContainersAndNestedSorting() {
        fun ints(b: ByteArray) = b.map { it.toInt() and 0xFF }
        assertEquals(listOf(0x40), ints(Cbor.encode(ByteArray(0))))
        assertEquals(listOf(0x60), ints(Cbor.encode("")))
        assertEquals(listOf(0x80), ints(Cbor.encode(emptyList<Any>())))
        assertEquals(listOf(0xA0), ints(Cbor.encode(emptyMap<Any, Any>())))
        // Map keys sort by encoded length first: key 1 (1 byte) before "ab" (3 bytes).
        val nested = Cbor.encode(mapOf("ab" to listOf(true, -1L), 1L to byteArrayOf(9)))
        assertEquals(0xA2.toByte(), nested[0])
        assertEquals(0x01.toByte(), nested[1])
        // A Double is a Number: it is truncated through toLong like other numbers.
        assertEquals(Cbor.encode(3L).toList(), Cbor.encode(3.9).toList())
        assertFailsWith<IllegalArgumentException> { Cbor.encode('x') }
    }

    // ---- TOTP: decoding tolerance and counter behaviour ----

    @Test fun totpIgnoresInvalidBase32CharactersAndCountsWholeSteps() {
        val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        // Characters outside the base32 alphabet are skipped, not fatal.
        assertEquals(TOTP.generate(secret, 59), TOTP.generate("GEZD GNBV!GY3TQOJQ GEZDGNBVGY3TQOJQ", 59))
        // Epoch 0 and 29 share counter 0; 30 flips to counter 1 (RFC vector value at 59s).
        assertEquals(TOTP.generate(secret, 0), TOTP.generate(secret, 29))
        assertEquals("287082", TOTP.generate(secret, 30))
        // Every code is exactly six digits, including when the value needs leading zeros.
        assertEquals(6, TOTP.generate(secret, 1_111_111_109).length)
        assertEquals("081804", TOTP.generate(secret, 1_111_111_109))
        assertFailsWith<IllegalArgumentException> { TOTP.generate("   ", 0) }
        assertFailsWith<IllegalArgumentException> { TOTP.generate("====", 0) }
    }

    // ---- DomainMatch: normalisation edges ----

    @Test fun normalizeSiteEdges() {
        assertEquals("example.com", DomainMatch.normalizeSite("HTTPS://User:PW@WWW.Example.COM.:8443/path?q=1"))
        assertEquals("example.com", DomainMatch.normalizeSite("example.com."))
        assertEquals("", DomainMatch.normalizeSite("   "))
        assertEquals("sub.example.com", DomainMatch.normalizeSite("https://sub.example.com"))
        assertEquals("example.com", DomainMatch.normalizeSite("www.example.com"))
        // Only one leading www. label is stripped.
        assertEquals("www.example.com", DomainMatch.normalizeSite("www.www.example.com"))
    }

    @Test fun androidPackageDetectionEdges() {
        assertTrue(DomainMatch.isAndroidPackageSite("android-app://com.example.app"))
        assertTrue(DomainMatch.isAndroidPackageSite("com.example.app"))
        assertFalse(DomainMatch.isAndroidPackageSite("com.example")) // only one dot
        assertFalse(DomainMatch.isAndroidPackageSite("org.example.app")) // does not start with com.
        assertFalse(DomainMatch.isAndroidPackageSite("https://com.example.app/")) // a URL, not a package
        assertFalse(DomainMatch.isAndroidPackageSite("example.com"))
    }

    @Test fun sameSiteEdges() {
        assertTrue(DomainMatch.isSameSiteOrSubdomain("example.com", "example.com"))
        assertTrue(DomainMatch.isSameSiteOrSubdomain("a.b.example.com", "example.com"))
        assertFalse(DomainMatch.isSameSiteOrSubdomain("example.com", "a.example.com"))
        assertFalse(DomainMatch.isSameSiteOrSubdomain("", "example.com"))
        assertFalse(DomainMatch.isSameSiteOrSubdomain("example.com", ""))
        assertFalse(DomainMatch.isSameSiteOrSubdomain("badexample.com", "example.com"))
    }

    // ---- ImportSource: header tables are complete and distinct ----

    @Test fun importSourceHeaderTables() {
        // Every source can find at least a name, username, password and url column in its own table.
        for (source in ImportSource.entries) {
            assertTrue(source.nameHeaders.isNotEmpty(), "$source name")
            assertTrue(source.usernameHeaders.isNotEmpty(), "$source username")
            assertTrue(source.passwordHeaders.isNotEmpty(), "$source password")
            assertTrue(source.urlHeaders.isNotEmpty(), "$source url")
            assertTrue(source.label != 0, "$source label")
        }
        assertEquals(listOf("login_uri", "uri"), ImportSource.BITWARDEN.urlHeaders.toList())
        assertEquals(listOf("username", "username2", "username3"), ImportSource.DASHLANE.usernameHeaders.toList())
        assertTrue(ImportSource.PROTON_PASS.splitUrlsOnComma)
        assertFalse(ImportSource.BITWARDEN.splitUrlsOnComma)
        assertEquals(listOf("email"), ImportSource.PROTON_PASS.emailHeaders.toList())
        assertEquals(listOf("type"), ImportSource.PROTON_PASS.typeHeaders.toList())
        assertTrue(ImportSource.FIREFOX.totpHeaders.isEmpty())
        assertEquals(8, ImportSource.entries.size)
    }

    @Test fun cborMapKeyOrderingByEncodedBytes() {
        fun ints(b: ByteArray) = b.map { it.toInt() and 0xFF }
        // Byte-string keys of equal encoded length order by their bytes.
        val byBytes = Cbor.encode(mapOf(byteArrayOf(2) to "b", byteArrayOf(1) to "a"))
        assertEquals(listOf(0xA2, 0x41, 1, 0x61, 'a'.code, 0x41, 2, 0x61, 'b'.code), ints(byBytes))
        // Shorter encodings sort first even when the byte value is larger:
        // key 24 encodes in 2 bytes, key 1 in 1 byte, so 1 comes first.
        val byLength = Cbor.encode(mapOf(24L to "x", 1L to "y"))
        assertEquals(0x01, ints(byLength)[1])
    }

    @Test fun totpNegativeEpochCounters() {
        val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        // Kotlin Long division truncates toward zero: -29s is still counter 0,
        // while -30s and -31s are both counter -1.
        assertEquals(TOTP.generate(secret, 0), TOTP.generate(secret, -29))
        assertEquals(TOTP.generate(secret, -30), TOTP.generate(secret, -31))
        assertEquals(6, TOTP.generate(secret, -30).length)
    }
}
