package com.vayunmathur.securemail.domain.mail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddressValidatorTest {

    @Test
    fun claimableLocalParts() {
        assertEquals("alice@maemail.org", AddressValidator.canonicalAddress("alice"))
        assertEquals("a.b-c_d1@maemail.org", AddressValidator.canonicalAddress("A.B-C_D1"))
        assertNull(AddressValidator.canonicalAddress("ab"))
        assertNull(AddressValidator.canonicalAddress("-bad"))
        assertNull(AddressValidator.canonicalAddress("bad!"))
    }

    @Test
    fun ownDomainDetection() {
        assertTrue(AddressValidator.isOwnDomain("alice@maemail.org"))
        assertTrue(AddressValidator.isOwnDomain("Alice@MAEMAIL.ORG"))
        assertFalse(AddressValidator.isOwnDomain("alice@proton.me"))
        assertFalse(AddressValidator.isOwnDomain("not-an-address"))
    }

    @Test
    fun recipientParsing() {
        assertEquals(
            listOf("a@maemail.org", "b@proton.me"),
            AddressValidator.parseRecipients("a@maemail.org, b@proton.me\n"),
        )
        assertEquals(emptyList(), AddressValidator.parseRecipients("  ,  "))
    }

    @Test
    fun validity() {
        assertTrue(AddressValidator.isValidAddress("alice@maemail.org"))
        assertFalse(AddressValidator.isValidAddress("alice"))
        assertFalse(AddressValidator.isValidAddress("alice@"))
    }
}

class KeyLookupPolicyTest {

    private fun trust(address: String, hasKey: Boolean, changed: Boolean = false) =
        KeyLookupPolicy.RecipientTrust(
            address = address,
            hasKey = hasKey,
            source = if (hasKey) KeyLookupPolicy.KeySource.OwnDirectory else KeyLookupPolicy.KeySource.None,
            keyChanged = changed,
        )

    @Test
    fun allKeyedSendsEncrypted() {
        val decision = KeyLookupPolicy.decide(
            listOf(trust("a@maemail.org", true), trust("b@proton.me", true)),
        )
        assertEquals(KeyLookupPolicy.SendDecision.Encrypted, decision)
    }

    @Test
    fun missingKeysFallBackUnencrypted() {
        val decision = KeyLookupPolicy.decide(
            listOf(trust("a@maemail.org", true), trust("b@gmail.com", false)),
        )
        assertTrue(decision is KeyLookupPolicy.SendDecision.UnencryptedFallback)
        assertEquals(listOf("b@gmail.com"), decision.missing)
    }

    @Test
    fun changedKeysBlockSend() {
        val decision = KeyLookupPolicy.decide(
            listOf(trust("a@maemail.org", true, changed = true)),
        )
        assertTrue(decision is KeyLookupPolicy.SendDecision.NeedsKeyChangeAck)
    }

    @Test
    fun wkdUrls() {
        // Well-known shape: advanced then direct.
        // https://openpgpkey.<domain>/.well-known/openpgpkey/<domain>/hu/<zbase32>
        // https://<domain>/.well-known/openpgpkey/hu/<zbase32>
        val urls = KeyLookupPolicy.wkdUrls("Alice@proton.me")
        assertEquals(2, urls.size)
        assertTrue(urls[0].startsWith("https://openpgpkey.proton.me/.well-known/openpgpkey/proton.me/hu/"))
        assertTrue(urls[1].startsWith("https://proton.me/.well-known/openpgpkey/hu/"))
        // Both methods share the same hash suffix.
        assertEquals(urls[0].substringAfter("/hu/"), urls[1].substringAfter("/hu/"))
        assertTrue(KeyLookupPolicy.wkdUrls("not-an-address").isEmpty())
        // RFC 7929 vector: hash from the lowercased local part, ?l= keeps the
        // original (Proton rejects lookups without it).
        assertEquals(
            "https://openpgpkey.example.org/.well-known/openpgpkey/example.org/hu/" +
                "iy9q119eutrkn8s1mk4r39qejnbu3n5q?l=Joe.Doe",
            KeyLookupPolicy.wkdUrls("Joe.Doe@Example.ORG")[0],
        )
        assertTrue(KeyLookupPolicy.wkdUrls("a+b@proton.me")[1].endsWith("?l=a%2Bb"))
    }
}
