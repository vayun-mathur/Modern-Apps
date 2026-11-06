package com.vayunmathur.communicate.rcs

import com.vayunmathur.communicate.data.rcs.RcsImdn
import com.vayunmathur.communicate.data.rcs.RcsSipResponse
import com.vayunmathur.communicate.data.rcs.buildImdnBody
import com.vayunmathur.communicate.data.rcs.ImdnDisposition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice-3 tests for [RcsSipResponse] (pure SIP response parsing over the
 * framework `android.telephony.ims.SipMessage`, real under Robolectric) and
 * [RcsImdn] (receipt tracker fed by parsed IMDN bodies).
 *
 * The framework class is absent from the unit-test android.jar stub AND from
 * the compile-only aosp-stubs (which would shadow Robolectric's instrumented
 * copy), so messages are constructed reflectively against the runtime class
 * that Robolectric's android-all provides. The parse entry point takes
 * `SipMessage`, so reflection builds the arg and the typed call is made via
 * `RcsSipResponse.parse` through a reflective bridge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RcsSlice3SipResponseTest {

    /** Construct a framework SipMessage reflectively (runtime class from android-all). */
    private fun sipMessage(startLine: String, headers: String, body: ByteArray): Any {
        val cls = Class.forName("android.telephony.ims.SipMessage")
        val ctor = cls.getConstructor(String::class.java, String::class.java, ByteArray::class.java)
        return ctor.newInstance(startLine, headers, body)
    }

    private val parseMethod: java.lang.reflect.Method by lazy {
        val cls = Class.forName("android.telephony.ims.SipMessage")
        RcsSipResponse::class.java.methods.first { it.name == "parse" && it.parameterTypes[0] == cls }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(msg: Any): RcsSipResponse.Parsed? =
        parseMethod.invoke(RcsSipResponse, msg) as RcsSipResponse.Parsed?

    private fun response(
        startLine: String = "SIP/2.0 200 OK",
        branch: String = "z9hG4bKabc123",
        callId: String = "call-1@rcs",
        toTag: String? = "remote-tag",
        fromTag: String? = "local-tag",
        cseq: String = "1 INVITE",
        extraHeaders: String = "",
        body: String = "",
    ): Any {
        val headers = buildString {
            append("Via: SIP/2.0/TCP 10.0.0.1;branch=$branch\r\n")
            append("To: <sip:peer@rcs>;tag=$toTag\r\n")
            append("From: <sip:me@rcs>;tag=$fromTag\r\n")
            append("Call-ID: $callId\r\n")
            append("CSeq: $cseq\r\n")
            if (extraHeaders.isNotEmpty()) append(extraHeaders)
        }
        return sipMessage(startLine, headers, body.toByteArray(Charsets.UTF_8))
    }

    @Test
    fun parsesFullInviteResponse() {
        val parsed = parse(response()) ?: error("expected parse")
        assertEquals(200, parsed.statusCode)
        assertEquals("OK", parsed.reason)
        assertEquals("z9hG4bKabc123", parsed.branch)
        assertEquals("call-1@rcs", parsed.callId)
        assertEquals("remote-tag", parsed.toTag)
        assertEquals("local-tag", parsed.fromTag)
        assertEquals("INVITE", parsed.cseqMethod)
        assertEquals(1L, parsed.cseqNumber)
    }

    @Test
    fun parsesRequestAsNull() {
        val invite = sipMessage(
            "INVITE sip:peer@rcs SIP/2.0",
            "Via: SIP/2.0/TCP x;branch=z9hG4bK1\r\nCall-ID: c\r\n",
            ByteArray(0),
        )
        assertNull(parse(invite))
    }

    @Test
    fun rejectsNonNumericStatus() {
        val msg = response(startLine = "SIP/2.0 OKAY whatever")
        assertNull(parse(msg))
    }

    @Test
    fun requiresViaBranchAndCallId() {
        // The framework constructor itself rejects a Via-less header section,
        // so only the missing-Call-ID path reaches the parser (null).
        val noCallId = sipMessage(
            "SIP/2.0 200 OK",
            "Via: SIP/2.0/TCP x;branch=z9hG4bK1\r\nCSeq: 1 MESSAGE\r\n",
            ByteArray(0),
        )
        assertNull(parse(noCallId))
    }

    @Test
    fun carriesBodyAndHeaders() {
        val sdp = "v=0\r\nm=message 2855 TCP/MSRP *\r\n"
        val parsed = parse(response(body = sdp)) ?: error("expected parse")
        assertEquals(sdp, parsed.body)
        assertTrue(parsed.headers.contains("Call-ID: call-1@rcs"))
    }

    @Test
    fun parsesProvisionalAndFailureCodes() {
        assertEquals(180, parse(response(startLine = "SIP/2.0 180 Ringing"))?.statusCode)
        assertEquals(401, parse(response(startLine = "SIP/2.0 401 Unauthorized"))?.statusCode)
        assertEquals(481, parse(response(startLine = "SIP/2.0 481 Call Leg Does Not Exist"))?.statusCode)
    }

    @Test
    fun cseqMethodAndNumberForBye() {
        val parsed = parse(response(cseq = "42 BYE")) ?: error("expected parse")
        assertEquals("BYE", parsed.cseqMethod)
        assertEquals(42L, parsed.cseqNumber)
    }

    @Test
    fun missingTagsAreNull() {
        val msg = sipMessage(
            "SIP/2.0 200 OK",
            "Via: SIP/2.0/TCP x;branch=z9hG4bK1\r\n" +
                "To: <sip:peer@rcs>\r\n" +
                "From: <sip:me@rcs>\r\n" +
                "Call-ID: c@rcs\r\n" +
                "CSeq: 1 MESSAGE\r\n",
            ByteArray(0),
        )
        val parsed = parse(msg) ?: error("expected parse")
        assertNull(parsed.toTag)
        assertNull(parsed.fromTag)
    }

    @Test
    fun isResponseDetectsStartLines() {
        assertTrue(RcsSipResponse.isResponse("SIP/2.0 200 OK"))
        assertTrue(RcsSipResponse.isResponse("  sip/2.0 404 Not Found"))
        assertFalse(RcsSipResponse.isResponse("INVITE sip:peer@rcs SIP/2.0"))
        assertFalse(RcsSipResponse.isResponse("MESSAGE sip:peer@rcs SIP/2.0"))
    }

    @Test
    fun imdnReportsRoundTripThroughTracker() = runTest {
        val body = buildImdnBody("slice3-msg-1", ImdnDisposition.Delivered)
        RcsImdn.onReportReceived(body)
        assertEquals(ImdnDisposition.Delivered, RcsImdn.statusFor("slice3-msg-1"))
        assertEquals(ImdnDisposition.Delivered, RcsImdn.reports.first()["slice3-msg-1"])
    }

    @Test
    fun imdnTrackerUpdatesAndIgnoresGarbage() = runTest {
        RcsImdn.onReportReceived(buildImdnBody("slice3-msg-2", ImdnDisposition.Displayed))
        assertEquals(ImdnDisposition.Displayed, RcsImdn.statusFor("slice3-msg-2"))
        // Latest disposition wins.
        RcsImdn.onReportReceived(buildImdnBody("slice3-msg-2", ImdnDisposition.Failed))
        assertEquals(ImdnDisposition.Failed, RcsImdn.statusFor("slice3-msg-2"))
        // Garbage is ignored, unknown ids are null.
        RcsImdn.onReportReceived("not an imdn body")
        assertNull(RcsImdn.statusFor("slice3-unknown-id"))
    }
}
