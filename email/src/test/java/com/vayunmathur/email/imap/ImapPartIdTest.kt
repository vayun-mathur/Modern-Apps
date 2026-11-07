package com.vayunmathur.email.imap

import com.vayunmathur.email.network.imap.ImapClient
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [ImapClient.partIdToSection]: our attachment rows store zero-based MIME
 * part ids, but IMAP BODY[...] sections are one-based. An off-by-one here
 * downloads the wrong attachment (or fails the fetch), so each mapping is
 * asserted exactly.
 */
class ImapPartIdTest {

    @Test
    fun `root and blank ids map to the first body section`() {
        assertEquals("1", ImapClient.partIdToSection("0"))
        assertEquals("1", ImapClient.partIdToSection(""))
        assertEquals("1", ImapClient.partIdToSection("   "))
    }

    @Test
    fun `single part index shifts by one`() {
        assertEquals("1", ImapClient.partIdToSection("0"))
        assertEquals("2", ImapClient.partIdToSection("1"))
        assertEquals("3", ImapClient.partIdToSection("2"))
    }

    @Test
    fun `nested multipart paths shift every level`() {
        assertEquals("1.1", ImapClient.partIdToSection("0.0"))
        assertEquals("1.2", ImapClient.partIdToSection("0.1"))
        assertEquals("2.3.4", ImapClient.partIdToSection("1.2.3"))
    }

    @Test
    fun `non-numeric ids fall back to the first section`() {
        assertEquals("1", ImapClient.partIdToSection("abc"))
        assertEquals("1", ImapClient.partIdToSection("a.b"))
    }

    @Test
    fun `mixed numeric and non-numeric segments keep the numeric ones`() {
        // Non-numeric segments are dropped; a fully-numeric result still shifts.
        assertEquals("2", ImapClient.partIdToSection("x.1"))
    }
}
