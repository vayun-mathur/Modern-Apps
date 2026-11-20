package com.vayunmathur.email.account

import com.vayunmathur.email.data.ACCOUNT_COLORS
import com.vayunmathur.email.data.EmailAccount
import com.vayunmathur.email.data.PROVIDER_PRESETS
import com.vayunmathur.email.data.accountColor
import com.vayunmathur.email.data.providerPreset
import com.vayunmathur.email.network.imap.TrustAll
import com.vayunmathur.email.platform.ServerConfig
import com.vayunmathur.email.platform.imapServer
import com.vayunmathur.email.platform.loginUser
import com.vayunmathur.email.platform.smtpServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins account and folder selection inputs: provider presets, per-account
 * colors, server configs and trust decisions.
 *
 * A wrong preset host or a trust miss does not throw either — mail just never
 * arrives — so these expectations are written from the documented provider
 * settings rather than from what the code happens to return today.
 */
class AccountFolderSelectionTest {

    @Test
    fun `gmail preset points at the documented hosts and ports`() {
        val gmail = providerPreset("gmail")
        assertNotNull(gmail)
        assertEquals("imap.gmail.com", gmail.imap?.host)
        assertEquals(993, gmail.imap?.port)
        assertEquals("smtp.gmail.com", gmail.smtp?.host)
    }

    @Test
    fun `unknown provider ids resolve to null`() {
        assertNull(providerPreset("no-such-provider"))
        assertNull(providerPreset(""))
    }

    @Test
    fun `custom preset leaves servers blank for manual entry`() {
        val custom = providerPreset("custom")
        assertNotNull(custom)
        assertNull(custom.imap)
        assertNull(custom.smtp)
    }

    @Test
    fun `outlook preset uses oauth while others use app passwords`() {
        assertEquals("oauth2", providerPreset("outlook")?.authType)
        assertEquals("password", providerPreset("gmail")?.authType)
        assertEquals("password", providerPreset("yahoo")?.authType)
    }

    @Test
    fun `every preset has a unique non blank identity`() {
        val ids = PROVIDER_PRESETS.map { it.id }
        assertTrue(ids.none { it.isBlank() })
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(PROVIDER_PRESETS.all { it.displayName.isNotBlank() })
        assertTrue(PROVIDER_PRESETS.all { it.instructions.isNotEmpty() })
    }

    @Test
    fun `account color is stable and inside the palette`() {
        val first = accountColor("me@example.com")
        assertEquals(first, accountColor("me@example.com"))
        assertTrue(ACCOUNT_COLORS.contains(first))
        assertTrue(ACCOUNT_COLORS.contains(accountColor("someone.else@example.org")))
    }

    @Test
    fun `server config derives protocol names from ssl`() {
        assertEquals("imaps", ServerConfig("h", 993, true).imapProtocol)
        assertEquals("imap", ServerConfig("h", 143, false).imapProtocol)
        assertEquals("smtps", ServerConfig("h", 465, true).smtpProtocol)
        assertEquals("smtp", ServerConfig("h", 587, false).smtpProtocol)
    }

    @Test
    fun `account server helpers read the stored hosts`() {
        val account = EmailAccount(email = "e", imapHost = "imap.x", imapPort = 143, imapUseSsl = false)
        assertEquals(ServerConfig("imap.x", 143, false), account.imapServer())
        assertEquals(ServerConfig("smtp.gmail.com", 465, true), account.smtpServer())
    }

    @Test
    fun `login falls back to the email when no username is stored`() {
        assertEquals("me@example.com", EmailAccount(email = "me@example.com").loginUser())
        val named = EmailAccount(email = "me@example.com", username = "login-name")
        assertEquals("login-name", named.loginUser())
        val blank = EmailAccount(email = "me@example.com", username = "   ")
        assertEquals("me@example.com", blank.loginUser())
    }

    @Test
    fun `known provider hosts keep strict system trust`() {
        assertTrue(TrustAll.isKnownHost("imap.gmail.com"))
        assertTrue(TrustAll.isKnownHost("outlook.office365.com"))
        assertTrue(TrustAll.isKnownHost("IMAP.GMAIL.COM"))
        assertTrue(TrustAll.isKnownHost("mail.gmail.com"))
    }

    @Test
    fun `unknown and lookalike hosts do not get strict trust`() {
        assertFalse(TrustAll.isKnownHost("mail.example.com"))
        assertFalse(TrustAll.isKnownHost("gmail.com"))
        assertFalse(TrustAll.isKnownHost("notgmail.com"))
        assertFalse(TrustAll.isKnownHost(""))
    }

    @Test
    fun `unknown host gets strict factory by default`() {
        // Regression: custom/unknown hosts must NOT silently get trust-all.
        assertTrue(TrustAll.usesStrictFactoryByDefault("mail.example.com"))
        assertTrue(TrustAll.usesStrictFactoryByDefault("custom.selfhosted.local"))
        assertEquals(
            TrustAll.systemSocketFactory(),
            TrustAll.socketFactoryFor("mail.example.com", forceTrustAll = false),
        )
    }

    @Test
    fun `permissive factory only on explicit opt-in`() {
        assertEquals(
            TrustAll.permissiveSocketFactory(),
            TrustAll.socketFactoryFor("mail.example.com", forceTrustAll = true),
        )
        assertFalse(EmailAccount(email = "me@example.com").allowInsecureCertificates)
        val optedIn = EmailAccount(email = "me@example.com", allowInsecureCertificates = true)
        assertTrue(optedIn.imapServer().trustAllCertificates)
        assertTrue(optedIn.smtpServer().trustAllCertificates)
        assertFalse(EmailAccount(email = "me@example.com").imapServer().trustAllCertificates)
    }
}
