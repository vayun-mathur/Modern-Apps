package com.vayunmathur.securemail.domain.mail

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.securemail.data.MailConfig
import com.vayunmathur.securemail.domain.mail.KeyLookupPolicy.KeySource
import com.vayunmathur.securemail.domain.mail.KeyLookupPolicy.RecipientTrust
import com.vayunmathur.securemail.domain.mail.KeyLookupPolicy.SendDecision
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class KeyLookupPolicyAndConfigTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private fun trust(address: String, hasKey: Boolean, changed: Boolean = false, lost: Boolean = false) =
        RecipientTrust(address, hasKey, if (hasKey) KeySource.Wkd else KeySource.None, keyChanged = changed, keyLost = lost)

    @Test fun `send decision encrypts, falls back, and gates key changes`() {
        assertEquals(SendDecision.Encrypted, KeyLookupPolicy.decide(emptyList()))
        assertEquals(SendDecision.Encrypted, KeyLookupPolicy.decide(listOf(trust("a@x.org", true))))
        val fallback = KeyLookupPolicy.decide(listOf(trust("a@x.org", true), trust("b@x.org", false)))
        assertEquals(SendDecision.UnencryptedFallback(listOf("b@x.org")), fallback)
        val changed = KeyLookupPolicy.decide(listOf(trust("a@x.org", true, changed = true), trust("b@x.org", false)))
        assertTrue(changed is SendDecision.NeedsKeyChangeAck)
        assertEquals(listOf("a@x.org"), (changed as SendDecision.NeedsKeyChangeAck).changed.map { it.address })
        val lost = KeyLookupPolicy.decide(listOf(trust("a@x.org", false, lost = true)))
        assertTrue(lost is SendDecision.NeedsKeyChangeAck)
    }

    @Test fun `wkd urls follow rfc7929 advanced then direct`() {
        assertTrue(KeyLookupPolicy.wkdUrls("no-at-sign").isEmpty())
        assertTrue(KeyLookupPolicy.wkdUrls("@x.org").isEmpty())
        assertTrue(KeyLookupPolicy.wkdUrls("a@").isEmpty())
        val urls = KeyLookupPolicy.wkdUrls("Ada@Example.ORG")
        assertEquals(2, urls.size)
        assertTrue(urls[0].startsWith("https://openpgpkey.example.org/.well-known/openpgpkey/example.org/hu/"))
        assertTrue(urls[0].endsWith("?l=Ada"))
        assertTrue(urls[1].startsWith("https://example.org/.well-known/openpgpkey/hu/"))
        assertEquals(urls[1], KeyLookupPolicy.wkdUrl("Ada@Example.ORG"))
        // z-base32 of SHA-1("ada") is deterministic and uses only the z-base32 alphabet
        val hash = KeyLookupPolicy.wkdHash("ada")
        assertEquals(32, hash.length)
        assertTrue(hash.all { it in "ybndrfg8ejkmcpqxot1uwisza345h769" })
    }

    @Test fun `mail config round trips identity and sync time`() = runBlocking {
        val config = MailConfig(ctx)
        config.clear()
        assertFalse(config.settings.first().isConfigured)
        config.setIdentity("me@maemail.org", "pub-armor", "AABB")
        config.setLastSync(12345L)
        val settings = config.settings.first()
        assertEquals("me@maemail.org", settings.address)
        assertEquals("pub-armor", settings.publicArmor)
        assertEquals("AABB", settings.fingerprintHex)
        assertEquals(12345L, settings.lastSyncMillis)
        assertTrue(settings.isConfigured)
        config.clear()
        assertFalse(config.settings.first().isConfigured)
    }

    @Test fun `address validator accepts normal addresses and rejects malformed`() {
        assertTrue(AddressValidator.isValidAddress("ada@example.org"))
        assertTrue(AddressValidator.isValidAddress("ada.lovelace+tag@sub.example.org"))
        assertFalse(AddressValidator.isValidAddress("not-an-address"))
        assertFalse(AddressValidator.isValidAddress("@example.org"))
        assertFalse(AddressValidator.isValidAddress("ada@"))
        assertTrue(AddressValidator.isOwnDomain("ada@maemail.org"))
        assertFalse(AddressValidator.isOwnDomain("ada@example.org"))
    }
}
