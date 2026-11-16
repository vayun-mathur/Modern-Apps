package com.vayunmathur.email.imap

import com.vayunmathur.email.network.imap.ImapParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** W5: ImapParser LIST/UID/flags/date and modified UTF-7 branches. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImapParserW5Test {

    @Test
    fun parseListCoversQuotedNilUnquotedAndMalformed() {
        val inbox = ImapParser.parseList("* LIST (\\HasNoChildren) \"/\" \"INBOX\"")
        assertEquals(listOf("\\HasNoChildren"), inbox?.flags)
        assertEquals("/", inbox?.delimiter)
        assertEquals("INBOX", inbox?.mailbox)

        val gmail = ImapParser.parseList("* LIST (\\Noselect \\HasChildren) \"/\" \"[Gmail]\"")
        assertEquals(listOf("\\Noselect", "\\HasChildren"), gmail?.flags)
        assertEquals("[Gmail]", gmail?.mailbox)

        val nil = ImapParser.parseList("* LIST (\\Seen) NIL INBOX.Archive")
        assertNull(nil?.delimiter)
        assertEquals("INBOX.Archive", nil?.mailbox)

        val unquoted = ImapParser.parseList("* LIST () . INBOX")
        assertEquals(emptyList(), unquoted?.flags)
        assertEquals(".", unquoted?.delimiter)
        assertEquals("INBOX", unquoted?.mailbox)

        val escaped = ImapParser.parseList("* LIST (\\HasNoChildren) \"/\" \"Sent \\\"old\\\"\"")
        assertEquals("Sent \"old\"", escaped?.mailbox)

        val unterminated = ImapParser.parseList("* LIST (\\HasNoChildren) \"/\" \"Sent")
        assertEquals("Sent", unterminated?.mailbox)

        assertNull(ImapParser.parseList("* FLAGS (\\Seen)"))
        assertNull(ImapParser.parseList("* LIST \\HasNoChildren \"/\" \"INBOX\""))
        assertNull(ImapParser.parseList("* LIST (\\HasNoChildren \"/\" \"INBOX\""))
        assertNull(ImapParser.parseList("random"))
    }

    @Test
    fun parseUidFlagsDatesAndThreadIds() {
        assertEquals(12345L, ImapParser.parseUid("* 9 FETCH (UID 12345 FLAGS (\\Seen))"))
        assertNull(ImapParser.parseUid("* 9 FETCH (FLAGS (\\Seen))"))
        assertEquals(
            listOf("\\Answered", "\\Seen"),
            ImapParser.parseFlags("* FLAGS (\\Answered \\Seen)"),
        )
        assertEquals(emptyList(), ImapParser.parseFlags("* OK no flags here"))
        assertEquals(
            listOf("\\Seen", "\\Answered"),
            ImapParser.parseFlagsFromFetch("* 1 FETCH (FLAGS (\\Seen \\Answered) UID 7)"),
        )
        assertEquals(emptyList(), ImapParser.parseFlagsFromFetch("* 1 FETCH (FLAGS () UID 7)"))
        assertNull(ImapParser.parseFlagsFromFetch("* 1 FETCH (UID 7)"))
        assertEquals(
            "17-Jul-1996 02:44:25 -0700",
            ImapParser.parseInternalDate("* 1 FETCH (INTERNALDATE \"17-Jul-1996 02:44:25 -0700\")"),
        )
        assertNull(ImapParser.parseInternalDate("* 1 FETCH (UID 7)"))
        assertEquals(
            "123456789",
            ImapParser.parseGmailThreadIdFromHeaderBlock(
                "From: a@b\r\nX-GM-THRID: 123456789\r\nSubject: hi\r\n".toByteArray()
            ),
        )
        assertNull(ImapParser.parseGmailThreadIdFromHeaderBlock("From: a@b\n".toByteArray()))
        assertEquals(listOf("\\Noselect"), ImapParser.extractListFlags("* LIST (\\Noselect) \"/\" X"))
        assertEquals(emptyList(), ImapParser.extractListFlags("* LIST () \"/\" X"))
        assertEquals(emptyList(), ImapParser.extractListFlags("no parens"))
        assertEquals("/", ImapParser.extractListDelimiter("* LIST (\\Seen) \"/\" \"INBOX\""))
        assertNull(ImapParser.extractListDelimiter("* LIST (\\Seen) NIL \"INBOX\""))
    }

    @Test
    fun modifiedUtf7RoundTripsAndToleratesMalformed() {
        val samples = listOf(
            "INBOX", "INBOX/日本語", "Sent & Drafts", "a+b", "~peter/mail/台北/日本語",
            "INBOX.émojis-✓", "",
        )
        for (sample in samples) {
            val encoded = ImapParser.encodeModifiedUtf7(sample)
            assertEquals(sample, ImapParser.decodeModifiedUtf7(encoded), "roundtrip $sample -> $encoded")
        }
        // Known modified UTF-7 form for 台北 (from RFC 3501 examples).
        assertEquals("台北", ImapParser.decodeModifiedUtf7("&U,BTFw-"))
        assertEquals("INBOX.&U,BTFw-", ImapParser.encodeModifiedUtf7("INBOX.台北").let {
            if (it == "INBOX.&U,BTFw-") "INBOX.&U,BTFw-" else it
        })
        // A bare ampersand and a truncated section decode literally, not crash.
        assertEquals("a&b", ImapParser.decodeModifiedUtf7("a&-b"))
        assertEquals("&", ImapParser.decodeModifiedUtf7("&"))
    }
}
