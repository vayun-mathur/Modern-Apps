package com.vayunmathur.communicate.telephony

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** Slice-4 pass-2: RCS inbound envelope parsing on a Robolectric-created [RcsSyncService].
 *  (SipMessage itself is a hidden-API class with no test-classpath stub, so envelope
 *  construction is exercised through [Envelope] directly; header parsing is covered by
 *  RcsInboundHelpersSlice4Test.) */
@RunWith(RobolectricTestRunner::class)
class RcsInboundParseSlice4Pass2Test {

    private fun service() = Robolectric.setupService(RcsSyncService::class.java)

    @Test
    fun parseInboundPlainTextBecomesInboxRow() {
        val service = service()
        val parsed = service.parseInbound(
            Envelope(from = "+1555", contentType = "text/plain", raw = "hello world", callId = "c1", viaBranch = "br1"),
        )
        assertNotNull(parsed)
        assertEquals("+1555", parsed.conversationId)
        assertEquals("hello world", parsed.body)
        assertEquals(InboundKind.Text, parsed.kind)
        assertTrue(parsed.messageId.startsWith("in-c1"))
        service.onDestroy()
    }

    @Test
    fun parseInboundTypingAndReceiptKinds() {
        val service = service()
        val typing = service.parseInbound(
            Envelope(
                from = "+1555", contentType = "application/im-iscomposing+xml",
                raw = "<?xml version=\"1.0\"?><isComposing xmlns=\"urn:ietf:params:xml:ns:im-composing\"><state>active</state></isComposing>",
                callId = "c2", viaBranch = "br",
            ),
        )
        assertNotNull(typing)
        assertEquals(InboundKind.Typing, typing.kind)
        assertEquals("", typing.body)

        val blank = service.parseInbound(
            Envelope(from = "+1555", contentType = "text/plain", raw = "   ", callId = "c3", viaBranch = "br"),
        )
        assertNull(blank)
        service.onDestroy()
    }

    @Test
    fun parseInboundCpimWrappedTextIsUnwrapped() {
        val service = service()
        val cpim = "Content-Type: message/cpim\r\n\r\n" +
            "From: <sip:+1555@x>\r\nContent-Type: text/plain\r\n\r\nwrapped hello"
        val parsed = service.parseInbound(
            Envelope(from = "+1555", contentType = "message/cpim", raw = cpim, callId = "c4", viaBranch = "br"),
        )
        assertNotNull(parsed)
        assertEquals("wrapped hello", parsed.body)
        service.onDestroy()
    }

    @Test
    fun rcsSyncServiceNullIntentStartsRegistrationAttempt() {
        val service = service()
        // Dev builds have RcsFeature enabled, so a plain start goes sticky and kicks off
        // registration in the service scope (which fails harmlessly with no IMS stack).
        val result = service.onStartCommand(null, 0, 7)
        assertEquals(android.app.Service.START_STICKY, result)
        Thread.sleep(300)
        service.onDestroy()
    }

    @Test
    fun rcsSyncServiceStopAndTimeoutPaths() {
        val service = service()
        val result = service.onStartCommand(
            android.content.Intent().apply { action = "com.vayunmathur.communicate.rcs.STOP_SYNC" }, 0, 1,
        )
        assertEquals(android.app.Service.START_NOT_STICKY, result)
        service.onTimeout(1, 0)
        assertNull(service.onBind(android.content.Intent()))
        service.onDestroy()
    }
}
