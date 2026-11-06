package com.vayunmathur.youpipe.util.sabr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrInfo
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrSession
import org.schabi.newpipe.extractor.services.youtube.sabrng.exception.SabrAttestationException

/**
 * Slice-1 tests for [SabrAttestationRetryHandler]: retry budget, token
 * refresh on rejection, exhaustion, missing minter, minter failures, and
 * budget restoration on media. A fake [YoutubeSabrSession] subclass records
 * injected PO tokens without touching the network.
 */
class SabrSlice1RetryHandlerTest {

    private fun sessionWithUrl(url: String = "https://example.com/sabr"): YoutubeSabrSession {
        val info = YoutubeSabrInfo(
            "vid1", "cpn1", "1.0", null, url, null, emptyList(), null,
        )
        return YoutubeSabrSession(info, null)
    }

    private fun rejected(): SabrAttestationException = SabrAttestationException("rejected")

    @Test
    fun successfulRetryInjectsFreshToken() {
        val session = sessionWithUrl()
        var forced = false
        val handler = SabrAttestationRetryHandler("vid1") { force ->
            forced = force
            byteArrayOf(9, 9, 9)
        }
        handler.prepareRetry(session, rejected())
        assertTrue(forced, "minter invoked with force=true")
    }

    @Test
    fun threeRetriesThenExhausted() {
        val session = sessionWithUrl()
        val handler = SabrAttestationRetryHandler("vid1") { byteArrayOf(1) }
        handler.prepareRetry(session, rejected())
        handler.prepareRetry(session, rejected())
        handler.prepareRetry(session, rejected())
        val err = assertFailsWith<SabrAttestationException> {
            handler.prepareRetry(session, rejected())
        }
        assertTrue(err.message!!.contains("vid1"))
        assertTrue(err.message!!.contains("3"))
    }

    @Test
    fun mediaReceivedRestoresBudget() {
        val session = sessionWithUrl()
        val handler = SabrAttestationRetryHandler("vid1") { byteArrayOf(1) }
        handler.prepareRetry(session, rejected())
        handler.prepareRetry(session, rejected())
        handler.prepareRetry(session, rejected())
        // Budget exhausted now; media restores it.
        handler.onMediaReceived()
        handler.prepareRetry(session, rejected())
    }

    @Test
    fun noMinterThrowsDescriptiveError() {
        val session = sessionWithUrl()
        val handler = SabrAttestationRetryHandler("vid1", null)
        val err = assertFailsWith<SabrAttestationException> {
            handler.prepareRetry(session, rejected())
        }
        assertTrue(err.message!!.contains("no PO token minter", ignoreCase = true))
        assertTrue(err.message!!.contains("vid1"))
    }

    @Test
    fun emptyTokenThrows() {
        val session = sessionWithUrl()
        for (empty in listOf(byteArrayOf(), null)) {
            val handler = SabrAttestationRetryHandler("vid1") { empty }
            val err = assertFailsWith<SabrAttestationException> {
                handler.prepareRetry(session, rejected())
            }
            assertTrue(err.message!!.contains("no token", ignoreCase = true))
        }
    }

    @Test
    fun minterExtractionFailureWraps() {
        val session = sessionWithUrl()
        val root = org.schabi.newpipe.extractor.exceptions.ExtractionException("boom")
        val handler = SabrAttestationRetryHandler("vid1") { throw root }
        val err = assertFailsWith<SabrAttestationException> {
            handler.prepareRetry(session, rejected())
        }
        assertTrue(err.message!!.contains("retry 1 of 3"))
        assertEquals(root, err.cause)
    }

    @Test
    fun minterCalledWithForceTrue() {
        val session = sessionWithUrl()
        val seen = mutableListOf<Boolean>()
        val handler = SabrAttestationRetryHandler("vid1") { force ->
            seen += force
            byteArrayOf(2)
        }
        handler.prepareRetry(session, rejected())
        handler.prepareRetry(session, rejected())
        assertEquals(listOf(true, true), seen)
        assertFalse(seen.contains(false))
    }
}
