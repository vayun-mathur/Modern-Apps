package com.vayunmathur.email.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Built-in provider presets: the wrong host, port or auth type strands a
 * new account on a dead endpoint, and the custom escape hatch must stay
 * config-free so the manual form collects every value itself.
 */
class ProviderPresetsTest {

    @Test
    fun `preset ids are unique and cover the known providers`() {
        val ids = PROVIDER_PRESETS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(
            setOf(PROVIDER_GMAIL, PROVIDER_OUTLOOK, PROVIDER_YAHOO, PROVIDER_AOL, PROVIDER_ICLOUD, PROVIDER_FASTMAIL, PROVIDER_CUSTOM),
            ids.toSet(),
        )
        assertTrue(PROVIDER_PRESETS.all { it.displayName.isNotBlank() })
        assertTrue(PROVIDER_PRESETS.all { it.instructions.isNotEmpty() })
    }

    @Test
    fun `gmail preset points at google ssl endpoints with app password auth`() {
        val gmail = assertNotNull(providerPreset(PROVIDER_GMAIL))
        assertEquals("imap.gmail.com", gmail.imap?.host)
        assertEquals(993, gmail.imap?.port)
        assertEquals(true, gmail.imap?.useSsl)
        assertEquals("smtp.gmail.com", gmail.smtp?.host)
        assertEquals(465, gmail.smtp?.port)
        assertEquals("password", gmail.authType)
        assertNotNull(gmail.appPasswordHelpUrl)
    }

    @Test
    fun `outlook preset is the oauth preset with starttls smtp`() {
        val outlook = assertNotNull(providerPreset(PROVIDER_OUTLOOK))
        assertEquals("oauth2", outlook.authType)
        assertEquals("outlook.office365.com", outlook.imap?.host)
        assertEquals(587, outlook.smtp?.port)
        assertEquals(false, outlook.smtp?.useSsl)
        assertNull(outlook.appPasswordHelpUrl)
    }

    @Test
    fun `custom preset carries no endpoints so the form collects them`() {
        val custom = assertNotNull(providerPreset(PROVIDER_CUSTOM))
        assertNull(custom.imap)
        assertNull(custom.smtp)
        assertEquals("password", custom.authType)
    }

    @Test
    fun `non-custom presets all carry both endpoints`() {
        val configured = PROVIDER_PRESETS.filter { it.id != PROVIDER_CUSTOM }
        assertTrue(configured.all { it.imap != null && it.smtp != null })
        assertTrue(configured.all { (it.imap?.port ?: 0) > 0 && (it.smtp?.port ?: 0) > 0 })
    }

    @Test
    fun `unknown preset id resolves to null`() {
        assertNull(providerPreset("not-a-provider"))
        assertNull(providerPreset(""))
    }
}
