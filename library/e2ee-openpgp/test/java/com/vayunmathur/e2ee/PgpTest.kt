package com.vayunmathur.e2ee

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class PgpTest {

    @Test
    fun generateEncryptDecryptRoundtrip() {
        val identity = Pgp.generateIdentity("alice <alice@maemail.org>", "correct horse battery")
        assertTrue(identity.armoredSecret.contains("PGP PRIVATE KEY BLOCK"))
        assertTrue(identity.armoredPublic.contains("PGP PUBLIC KEY BLOCK"))
        assertEquals(40, identity.fingerprintHex.length)

        val plaintext = "Hello from SecureMail".toByteArray(Charsets.UTF_8)
        val raw = Pgp.encrypt(identity.armoredPublic, plaintext)
        assertTrue(raw.isNotEmpty())

        val back = Pgp.decrypt(identity.armoredSecret, "correct horse battery", raw)
        assertEquals(plaintext.toList(), back.toList())
    }

    @Test
    fun wrongPassphraseFails() {
        val identity = Pgp.generateIdentity("alice <alice@maemail.org>", "correct horse battery")
        val raw = Pgp.encrypt(identity.armoredPublic, "secret".toByteArray(Charsets.UTF_8))
        assertFailsWith<IllegalStateException> {
            Pgp.decrypt(identity.armoredSecret, "wrong passphrase", raw)
        }
    }

    @Test
    fun fingerprintStable() {
        val identity = Pgp.generateIdentity("alice <alice@maemail.org>", "correct horse battery")
        assertEquals(identity.fingerprintHex, Pgp.fingerprintHex(identity.armoredPublic))
    }

    @Test
    fun armorMessageRoundtrip() {
        val identity = Pgp.generateIdentity("alice <alice@maemail.org>", "correct horse battery")
        val raw = Pgp.encrypt(identity.armoredPublic, "federation".toByteArray(Charsets.UTF_8))
        val armored = Pgp.armorMessage(raw)
        assertTrue(armored.startsWith("-----BEGIN PGP MESSAGE-----"))
        assertTrue(armored.contains("-----END PGP MESSAGE-----"))
    }

    @Test
    fun signedEncryptVerifyRoundtrip() {
        val alice = Pgp.generateIdentity("alice <alice@maemail.org>", "alice-pw-123")
        val bob = Pgp.generateIdentity("bob <bob@maemail.org>", "bob-pw-456")
        val plaintext = "signed hello".toByteArray(Charsets.UTF_8)
        val raw = Pgp.encryptSigned(
            bob.armoredPublic, alice.armoredSecret, "alice-pw-123", plaintext,
        )
        assertTrue(raw.isNotEmpty())
        val verified = Pgp.decryptVerify(
            bob.armoredSecret, "bob-pw-456", raw, alice.armoredPublic,
        )
        assertEquals(plaintext.toList(), verified.payload.toList())
        assertEquals(alice.fingerprintHex, verified.signerFingerprintHex)
    }

    @Test
    fun unsignedMessageVerifiesEmpty() {
        val alice = Pgp.generateIdentity("alice <alice@maemail.org>", "alice-pw-123")
        val bob = Pgp.generateIdentity("bob <bob@maemail.org>", "bob-pw-456")
        // Empty sender secret = encrypt-only, same as encrypt().
        val raw = Pgp.encryptSigned(
            bob.armoredPublic, "", "alice-pw-123",
            "unsigned hello".toByteArray(Charsets.UTF_8),
        )
        val verified = Pgp.decryptVerify(
            bob.armoredSecret, "bob-pw-456", raw, alice.armoredPublic,
        )
        assertEquals("unsigned hello".toByteArray(Charsets.UTF_8).toList(), verified.payload.toList())
        assertTrue(verified.signerFingerprintHex.isEmpty())
    }

    @Test
    fun wrongVerifierKeyFails() {
        val alice = Pgp.generateIdentity("alice <alice@maemail.org>", "alice-pw-123")
        val bob = Pgp.generateIdentity("bob <bob@maemail.org>", "bob-pw-456")
        val carol = Pgp.generateIdentity("carol <carol@maemail.org>", "carol-pw-789")
        val raw = Pgp.encryptSigned(
            bob.armoredPublic, alice.armoredSecret, "alice-pw-123",
            "signed hello".toByteArray(Charsets.UTF_8),
        )
        assertFailsWith<IllegalStateException> {
            Pgp.decryptVerify(bob.armoredSecret, "bob-pw-456", raw, carol.armoredPublic)
        }
    }
}
