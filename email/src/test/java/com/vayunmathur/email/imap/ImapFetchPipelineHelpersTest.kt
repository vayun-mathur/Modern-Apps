package com.vayunmathur.email.imap

import com.vayunmathur.email.network.imap.ImapFetchPipeline
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the pure helpers on [ImapFetchPipeline]'s companion: the FETCH response
 * accumulator must tell header literals apart from body literals without
 * fetching twice. A misclassified literal stores a body where headers belong
 * (or vice versa), corrupting the message row.
 */
class ImapFetchPipelineHelpersTest {

    @Test
    fun `header block detected by From marker`() {
        assertTrue(
            ImapFetchPipeline.looksLikeHeaderBlock(
                "From: a@b.com\r\nSubject: hi\r\n\r\n".toByteArray()
            )
        )
    }

    @Test
    fun `header block detected by Subject marker`() {
        assertTrue(
            ImapFetchPipeline.looksLikeHeaderBlock(
                "Subject: hello\r\nDate: today\r\n\r\n".toByteArray()
            )
        )
    }

    @Test
    fun `header block detected by generic field separator plus CRLF`() {
        assertTrue(
            ImapFetchPipeline.looksLikeHeaderBlock(
                "Message-ID: <x@y>\r\nDate: Wed\r\n\r\n".toByteArray()
            )
        )
    }

    @Test
    fun `empty bytes are not a header block`() {
        assertFalse(ImapFetchPipeline.looksLikeHeaderBlock(ByteArray(0)))
    }

    @Test
    fun `plain body text is not a header block`() {
        assertFalse(ImapFetchPipeline.looksLikeHeaderBlock("hello world, no headers here".toByteArray()))
    }

    @Test
    fun `full body fetch markers are body literals`() {
        assertTrue(ImapFetchPipeline.isBodyLiteral("* 1 FETCH (UID 5 BODY[] {100}"))
        assertTrue(ImapFetchPipeline.isBodyLiteral("* 1 FETCH (UID 5 BODY.PEEK[] {100}"))
        assertTrue(ImapFetchPipeline.isBodyLiteral("* 1 FETCH (UID 5 BODY[1] {42}"))
        assertTrue(ImapFetchPipeline.isBodyLiteral("* 1 FETCH (UID 5 BODY[1.MIME] {42}"))
    }

    @Test
    fun `header fetch lines are not body literals`() {
        assertFalse(
            ImapFetchPipeline.isBodyLiteral(
                "* 1 FETCH (UID 5 BODY[HEADER.FIELDS (FROM SUBJECT)] {60}"
            )
        )
        assertFalse(ImapFetchPipeline.isBodyLiteral("TAG OK FETCH completed"))
    }
}
