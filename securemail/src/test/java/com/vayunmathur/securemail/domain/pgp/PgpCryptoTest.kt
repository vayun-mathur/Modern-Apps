package com.vayunmathur.securemail.domain.pgp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PgpCryptoTest {

    @Test fun `identity encrypt decrypt and sign round trip`() {
        val identity = PgpCrypto.generateIdentity("Ada <ada@maemail.org>", "correct horse battery")
        assertTrue(identity.armoredPublic.contains("PGP PUBLIC KEY"))
        assertTrue(identity.armoredSecret.contains("PGP PRIVATE KEY"))
        assertEquals(identity.fingerprintHex.lowercase(), PgpCrypto.fingerprintHex(identity.armoredPublic).lowercase())

        val plain = "meet at noon".toByteArray()
        val cipher = PgpCrypto.encrypt(plain, identity.armoredPublic)
        assertFalse(cipher.contentEquals(plain))
        assertEquals("meet at noon", PgpCrypto.decrypt(cipher, identity.armoredSecret, "correct horse battery").decodeToString())

        val signed = PgpCrypto.encryptSigned(plain, identity.armoredPublic, identity.armoredSecret, "correct horse battery")
        val opened = PgpCrypto.decryptVerify(signed, identity.armoredSecret, "correct horse battery", identity.armoredPublic)
        assertEquals("meet at noon", opened.payload.decodeToString())
        assertEquals(identity.fingerprintHex.lowercase(), opened.signerFingerprintHex.lowercase())

        val signature = PgpCrypto.signDetached(identity.armoredSecret, "correct horse battery", plain)
        assertTrue(signature.isNotEmpty())
        assertTrue(PgpCrypto.armorMessage(cipher).contains("PGP MESSAGE"))
    }

    @Test fun `wrong passphrase and malformed keys fail`() {
        val identity = PgpCrypto.generateIdentity("Bob <bob@maemail.org>", "passphrase-one")
        val cipher = PgpCrypto.encrypt("secret".toByteArray(), identity.armoredPublic)
        assertFails { PgpCrypto.decrypt(cipher, identity.armoredSecret, "passphrase-two") }
        assertFails { PgpCrypto.fingerprintHex("not a key") }
        assertFails { PgpCrypto.encrypt("x".toByteArray(), "not a key") }
    }
}
