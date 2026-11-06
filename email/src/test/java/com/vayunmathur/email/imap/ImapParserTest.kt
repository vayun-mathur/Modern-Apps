package com.vayunmathur.email.imap

import com.vayunmathur.email.network.imap.ImapListEntry
import com.vayunmathur.email.network.imap.ImapParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins IMAP LIST/FETCH line parsing and modified-UTF7 mailbox decoding against
 * fixed wire vectors (RFC 3501 shapes).
 *
 * [ImapParser] is pure string code except for `android.util.Base64`, which
 * returns defaults on plain JVM unit tests — so these run under Robolectric
 * where the real decoder resolves. A disagreement here silently misfiles
 * mail (wrong folder), drops flags, or mangles non-ASCII mailbox names —
 * each test names the wire shape that would expose the regression.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImapParserTest {

    // ---------- parseList ----------

    @Test
    fun `standard list line parses flags delimiter and mailbox`() {
        val entry: ImapListEntry? = ImapParser.parseList("* LIST (\\HasNoChildren) \"/\" \"INBOX\"")
        assertEquals(listOf("\\HasNoChildren"), entry!!.flags)
        assertEquals("/", entry.delimiter)
        assertEquals("INBOX", entry.mailbox)
    }

    @Test
    fun `noselect parent folder keeps all flags`() {
        val entry = ImapParser.parseList("* LIST (\\Noselect \\HasChildren) \"/\" \"[Gmail]\"")
        assertEquals(listOf("\\Noselect", "\\HasChildren"), entry!!.flags)
        assertEquals("[Gmail]", entry.mailbox)
    }

    @Test
    fun `nil delimiter maps to null`() {
        val entry = ImapParser.parseList("* LIST (\\Seen) NIL \"INBOX\"")
        assertEquals(null, entry!!.delimiter)
        assertEquals("INBOX", entry.mailbox)
    }

    @Test
    fun `non-list or malformed lines return null`() {
        assertNull(ImapParser.parseList("* FLAGS (\\Answered \\Flagged)"))
        assertNull(ImapParser.parseList("A001 OK done"))
        assertNull(ImapParser.parseList("* LIST \\NoFlags \"/\" \"INBOX\""))
        assertNull(ImapParser.parseList("* LIST (\\HasNoChildren"))
    }

    @Test
    fun `mailbox with spaces survives quoted parse`() {
        val entry = ImapParser.parseList("* LIST (\\HasNoChildren) \"/\" \"Deleted Items\"")
        assertEquals("Deleted Items", entry!!.mailbox)
    }

    @Test
    fun `escaped quote inside mailbox unescapes`() {
        val entry = ImapParser.parseList("* LIST (\\HasNoChildren) \"/\" \"We\\\"ird\"")
        assertEquals("We\"ird", entry!!.mailbox)
    }

    // ---------- modified UTF-7 ----------

    @Test
    fun `ascii names pass through untouched`() {
        assertEquals("INBOX", ImapParser.decodeModifiedUtf7("INBOX"))
        assertEquals("Sent/2024", ImapParser.decodeModifiedUtf7("Sent/2024"))
    }

    @Test
    fun `ampersand escape round-trips`() {
        assertEquals("A&B", ImapParser.decodeModifiedUtf7("A&-B"))
        assertEquals("A&-B", ImapParser.encodeModifiedUtf7("A&B"))
    }

    @Test
    fun `non-ascii mailbox decodes and re-encodes`() {
        // "é" (U+00E9) is UTF-16BE 0x00E9 -> modified-base64 "AOk-".
        assertEquals("Café", ImapParser.decodeModifiedUtf7("Caf&AOk-"))
        assertEquals("Caf&AOk-", ImapParser.encodeModifiedUtf7("Café"))
    }

    @Test
    fun `ascii strings encode unchanged`() {
        assertEquals("INBOX", ImapParser.encodeModifiedUtf7("INBOX"))
    }

    @Test
    fun `unterminated section degrades to literal`() {
        assertEquals("Foo&bar", ImapParser.decodeModifiedUtf7("Foo&bar"))
    }

    @Test
    fun `list line with encoded mailbox decodes`() {
        val entry = ImapParser.parseList("* LIST (\\HasNoChildren) \"/\" \"Caf&AOk-\"")
        assertEquals("Café", entry!!.mailbox)
    }

    // ---------- UID / flags / dates ----------

    @Test
    fun `uid extracts from fetch line`() {
        assertEquals(12345L, ImapParser.parseUid("* 1 FETCH (UID 12345 RFC822.SIZE 999)"))
        assertNull(ImapParser.parseUid("* 1 FETCH (RFC822.SIZE 999)"))
    }

    @Test
    fun `flags parse from flags response`() {
        assertEquals(
            listOf("\\Answered", "\\Flagged"),
            ImapParser.parseFlags("* FLAGS (\\Answered \\Flagged)"),
        )
        assertTrue(ImapParser.parseFlags("A001 OK done").isEmpty())
    }

    @Test
    fun `fetch flags extract case-insensitively`() {
        assertEquals(
            listOf("\\Seen", "\\Answered"),
            ImapParser.parseFlagsFromFetch("* 1 FETCH (FLAGS (\\Seen \\Answered))"),
        )
        assertEquals(
            emptyList(),
            ImapParser.parseFlagsFromFetch("* 1 FETCH (FLAGS ())"),
        )
        assertNull(ImapParser.parseFlagsFromFetch("* 1 FETCH (RFC822.SIZE 10)"))
    }

    @Test
    fun `internaldate extracts quoted value`() {
        assertEquals(
            "17-Jul-2024 12:00:00 +0000",
            ImapParser.parseInternalDate("* 1 FETCH (INTERNALDATE \"17-Jul-2024 12:00:00 +0000\")"),
        )
        assertNull(ImapParser.parseInternalDate("* 1 FETCH (RFC822.SIZE 10)"))
    }

    @Test
    fun `gmail thread id extracts from header block`() {
        val block = "Subject: hi\r\nX-GM-THRID: 1787263451234567890\r\nFrom: a@b".toByteArray()
        assertEquals("1787263451234567890", ImapParser.parseGmailThreadIdFromHeaderBlock(block))
        assertNull(ImapParser.parseGmailThreadIdFromHeaderBlock("Subject: hi\r\n".toByteArray()))
    }

    // ---------- list helpers ----------

    @Test
    fun `extractListFlags tolerates missing parens`() {
        assertEquals(
            listOf("\\HasNoChildren"),
            ImapParser.extractListFlags("* LIST (\\HasNoChildren) \"/\" \"INBOX\""),
        )
        assertTrue(ImapParser.extractListFlags("* LIST no flags here").isEmpty())
    }

    @Test
    fun `extractListDelimiter handles quoted nil and bare`() {
        assertEquals("/", ImapParser.extractListDelimiter("* LIST (\\X) \"/\" \"INBOX\""))
        assertEquals(null, ImapParser.extractListDelimiter("* LIST (\\X) NIL \"INBOX\""))
        assertEquals(".", ImapParser.extractListDelimiter("* LIST (\\X) . INBOX"))
    }
}
