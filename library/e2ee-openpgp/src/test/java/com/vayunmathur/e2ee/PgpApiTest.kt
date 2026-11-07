package com.vayunmathur.e2ee

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Exercises the real native OpenPGP surface (libe2ee_pgp via host Rust build)
 * through the public [Pgp] API: generation, fingerprint/normalize, encrypt,
 * signed encrypt + verify, detached signing, armor, and the error mapping
 * (native null -> IllegalStateException).
 */
class PgpApiTest {

    private companion object {
        const val ALICE_PW = "alice-pw-123"
        const val BOB_PW = "bob-pw-456"
        val alice: Pgp.Identity by lazy { Pgp.generateIdentity("alice <alice@maemail.org>", ALICE_PW) }
        val bob: Pgp.Identity by lazy { Pgp.generateIdentity("bob <bob@maemail.org>", BOB_PW) }
    }

    @Test
    fun generateIdentityShapeAndUniqueness() {
        assertTrue(alice.armoredSecret.contains("PGP PRIVATE KEY BLOCK"))
        assertTrue(alice.armoredPublic.contains("PGP PUBLIC KEY BLOCK"))
        assertFalse(alice.armoredSecret.contains("PUBLIC KEY BLOCK") && alice.armoredSecret == alice.armoredPublic)
        assertEquals(40, alice.fingerprintHex.length)
        assertTrue(alice.fingerprintHex.all { it in '0'..'9' || it in 'A'..'F' }, alice.fingerprintHex)
        assertNotEquals(alice.fingerprintHex, bob.fingerprintHex)
        val other = Pgp.generateIdentity("alice <alice@maemail.org>", ALICE_PW)
        assertNotEquals(alice.fingerprintHex, other.fingerprintHex, "each generation makes fresh key material")
        assertEquals(alice, alice.copy())
        assertEquals("x", alice.copy(fingerprintHex = "x").fingerprintHex)
    }

    @Test
    fun fingerprintMatchesGeneratedAndRejectsGarbage() {
        assertEquals(alice.fingerprintHex, Pgp.fingerprintHex(alice.armoredPublic))
        assertEquals(bob.fingerprintHex, Pgp.fingerprintHex(bob.armoredPublic))
        val error = assertFailsWith<IllegalStateException> { Pgp.fingerprintHex("not a key") }
        assertEquals("Invalid OpenPGP public key", error.message)
        assertFailsWith<IllegalStateException> { Pgp.fingerprintHex("") }
        assertFailsWith<IllegalStateException> { Pgp.fingerprintHex(alice.armoredSecret) }
    }

    @Test
    fun normalizeKeyAcceptsArmorAndBinaryAndRejectsGarbage() {
        val normalized = Pgp.normalizeKey(alice.armoredPublic.toByteArray(Charsets.UTF_8))
        assertTrue(normalized.contains("PGP PUBLIC KEY BLOCK"))
        assertEquals(alice.fingerprintHex, Pgp.fingerprintHex(normalized))

        // Binary (WKD-style) form: decode the armor body ourselves.
        val body = alice.armoredPublic.lines()
            .filter { it.isNotBlank() && !it.startsWith("-----") && !it.startsWith("=") && ':' !in it }
            .joinToString("")
        val binary = Base64.getDecoder().decode(body)
        assertEquals(alice.fingerprintHex, Pgp.fingerprintHex(Pgp.normalizeKey(binary)))

        assertFailsWith<IllegalStateException> { Pgp.normalizeKey(byteArrayOf(1, 2, 3)) }
        assertFailsWith<IllegalStateException> { Pgp.normalizeKey("junk".toByteArray()) }
        assertFailsWith<IllegalStateException> { Pgp.normalizeKey(ByteArray(0)) }
    }

    @Test
    fun encryptDecryptRoundtripsEmptyUnicodeAndBinary() {
        for (plaintext in listOf(
            ByteArray(0),
            "Hello from SecureMail".toByteArray(Charsets.UTF_8),
            "ünïcode ✉ payload".toByteArray(Charsets.UTF_8),
            ByteArray(256) { it.toByte() },
        )) {
            val raw = Pgp.encrypt(bob.armoredPublic, plaintext)
            assertTrue(raw.isNotEmpty())
            assertContentEquals(plaintext, Pgp.decrypt(bob.armoredSecret, BOB_PW, raw))
        }
        val raw = Pgp.encrypt(bob.armoredPublic, "secret".toByteArray())
        assertFalse(raw.contentEquals("secret".toByteArray()), "ciphertext must not be the plaintext")
    }

    @Test
    fun decryptFailuresThrow() {
        val raw = Pgp.encrypt(bob.armoredPublic, "secret".toByteArray())
        assertFailsWith<IllegalStateException> { Pgp.decrypt(bob.armoredSecret, "wrong", raw) }
        assertFailsWith<IllegalStateException> { Pgp.decrypt(alice.armoredSecret, ALICE_PW, raw) }
        assertFailsWith<IllegalStateException> { Pgp.decrypt(bob.armoredSecret, BOB_PW, byteArrayOf(9, 9, 9)) }
        assertFailsWith<IllegalStateException> { Pgp.decrypt(bob.armoredSecret, BOB_PW, ByteArray(0)) }
        val tampered = raw.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertFailsWith<IllegalStateException> { Pgp.decrypt(bob.armoredSecret, BOB_PW, tampered) }
        assertFailsWith<IllegalStateException> { Pgp.encrypt("garbage", "x".toByteArray()) }
    }

    @Test
    fun signedEncryptVerifyRoundtripAndUnverifiedPath() {
        val plaintext = "signed hello".toByteArray(Charsets.UTF_8)
        val raw = Pgp.encryptSigned(bob.armoredPublic, alice.armoredSecret, ALICE_PW, plaintext)

        val verified = Pgp.decryptVerify(bob.armoredSecret, BOB_PW, raw, alice.armoredPublic)
        assertContentEquals(plaintext, verified.payload)
        assertEquals(alice.fingerprintHex, verified.signerFingerprintHex)

        // Empty verifier: signed but unverified, accepted with empty fingerprint.
        val unverified = Pgp.decryptVerify(bob.armoredSecret, BOB_PW, raw, "")
        assertContentEquals(plaintext, unverified.payload)
        assertEquals("", unverified.signerFingerprintHex)

        // Plain decrypt of a signed message returns just the payload.
        assertContentEquals(plaintext, Pgp.decrypt(bob.armoredSecret, BOB_PW, raw))
    }

    @Test
    fun unsignedMessagesVerifyWithEmptyFingerprint() {
        val plaintext = "unsigned hello".toByteArray(Charsets.UTF_8)
        // Empty sender secret disables signing at the JNI boundary.
        val raw = Pgp.encryptSigned(bob.armoredPublic, "", ALICE_PW, plaintext)
        val verified = Pgp.decryptVerify(bob.armoredSecret, BOB_PW, raw, alice.armoredPublic)
        assertContentEquals(plaintext, verified.payload)
        assertEquals("", verified.signerFingerprintHex)

        val plain = Pgp.encrypt(bob.armoredPublic, plaintext)
        assertEquals("", Pgp.decryptVerify(bob.armoredSecret, BOB_PW, plain, alice.armoredPublic).signerFingerprintHex)
    }

    @Test
    fun wrongVerifierAndBadSignerFail() {
        val carol = Pgp.generateIdentity("carol <carol@maemail.org>", "carol-pw-789")
        val raw = Pgp.encryptSigned(
            bob.armoredPublic, alice.armoredSecret, ALICE_PW, "signed".toByteArray(),
        )
        assertFailsWith<IllegalStateException> {
            Pgp.decryptVerify(bob.armoredSecret, BOB_PW, raw, carol.armoredPublic)
        }
        assertFailsWith<IllegalStateException> {
            Pgp.decryptVerify(bob.armoredSecret, BOB_PW, raw, "garbage verifier")
        }
        assertFailsWith<IllegalStateException> {
            Pgp.encryptSigned(bob.armoredPublic, alice.armoredSecret, "wrong-pw", "x".toByteArray())
        }
        assertFailsWith<IllegalStateException> {
            Pgp.encryptSigned(bob.armoredPublic, "not a secret key", ALICE_PW, "x".toByteArray())
        }
        assertFailsWith<IllegalStateException> {
            Pgp.encryptSigned("garbage", alice.armoredSecret, ALICE_PW, "x".toByteArray())
        }
    }

    @Test
    fun detachedSignaturesAreDeterministicAndDataDependent() {
        val data = "authenticate me".toByteArray(Charsets.UTF_8)
        val sig = Pgp.signDetached(alice.armoredSecret, ALICE_PW, data)
        assertTrue(sig.isNotEmpty())
        assertContentEquals(sig, Pgp.signDetached(alice.armoredSecret, ALICE_PW, data))
        val other = Pgp.signDetached(alice.armoredSecret, ALICE_PW, "authenticate me!".toByteArray())
        assertFalse(sig.contentEquals(other))
        assertFailsWith<IllegalStateException> { Pgp.signDetached(alice.armoredSecret, "wrong", data) }
        assertFailsWith<IllegalStateException> { Pgp.signDetached("garbage", ALICE_PW, data) }
    }

    @Test
    fun armorMessageFramingDecodesBackToRaw() {
        val raw = Pgp.encrypt(bob.armoredPublic, "federation".toByteArray())
        val armored = Pgp.armorMessage(raw)
        assertTrue(armored.startsWith("-----BEGIN PGP MESSAGE-----\n"))
        assertTrue(armored.trimEnd().endsWith("-----END PGP MESSAGE-----"))
        val body = armored.lines()
            .filter { it.isNotBlank() && !it.startsWith("-----") && !it.startsWith("=") }
            .joinToString("")
        assertContentEquals(raw, Base64.getDecoder().decode(body))
        assertTrue(armored.lines().any { it.startsWith("=") }, "CRC24 footer line present")
        val empty = Pgp.armorMessage(ByteArray(0))
        assertTrue(empty.startsWith("-----BEGIN PGP MESSAGE-----"))
    }
}
