package com.vayunmathur.email.platform

import com.vayunmathur.email.data.EmailAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the server-config derivation on [EmailAccount]: the wrong host/port or
 * protocol string silently points IMAP/SMTP at a dead endpoint, and the wrong
 * login user sends the full address where the provider expects the bare name
 * (or vice versa).
 */
class ServerConfigTest {

    private fun account(
        email: String = "me@gmail.com",
        username: String = "",
        imapHost: String = "imap.gmail.com",
        imapPort: Int = 993,
        imapUseSsl: Boolean = true,
        smtpHost: String = "smtp.gmail.com",
        smtpPort: Int = 587,
        smtpUseSsl: Boolean = false,
    ) = EmailAccount(
        email = email,
        username = username,
        imapHost = imapHost,
        imapPort = imapPort,
        imapUseSsl = imapUseSsl,
        smtpHost = smtpHost,
        smtpPort = smtpPort,
        smtpUseSsl = smtpUseSsl,
    )

    @Test
    fun `imap server carries the account host port and ssl flag`() {
        val server = account().imapServer()
        assertEquals("imap.gmail.com", server.host)
        assertEquals(993, server.port)
        assertTrue(server.useSsl)
    }

    @Test
    fun `smtp server carries the account host port and ssl flag`() {
        val server = account().smtpServer()
        assertEquals("smtp.gmail.com", server.host)
        assertEquals(587, server.port)
        assertFalse(server.useSsl)
    }

    @Test
    fun `protocol strings follow the ssl flag`() {
        val ssl = account(imapUseSsl = true, smtpUseSsl = true)
        assertEquals("imaps", ssl.imapServer().imapProtocol)
        assertEquals("smtps", ssl.smtpServer().smtpProtocol)
        val plain = account(imapUseSsl = false, smtpUseSsl = false)
        assertEquals("imap", plain.imapServer().imapProtocol)
        assertEquals("smtp", plain.smtpServer().smtpProtocol)
    }

    @Test
    fun `login user prefers the explicit username over the email`() {
        assertEquals("me@gmail.com", account(username = "").loginUser())
        assertEquals("me", account(username = "me").loginUser())
        // Whitespace-only counts as blank, so it falls back to the email too.
        assertEquals("me@gmail.com", account(username = "   ").loginUser())
    }

    @Test
    fun `auth type variants carry their credentials`() {
        val pw = EmailManager.AuthType.Password("s3cret")
        assertEquals("s3cret", pw.value)
        val oauth = EmailManager.AuthType.OAuth("tok123")
        assertEquals("tok123", oauth.token)
    }
}
