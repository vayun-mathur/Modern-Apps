package com.vayunmathur.communicate.telephony

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.rcs.RcsCachedMessage
import com.vayunmathur.communicate.data.rcs.RcsDatabase
import com.vayunmathur.communicate.data.rcs.buildEditBody
import com.vayunmathur.communicate.data.rcs.buildGeopushBody
import com.vayunmathur.communicate.data.rcs.buildRevokeBody
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pass 3: parseInbound's side-effecting routes — revoke/edit applied to
 * cached rows on the service scope, geopush rows, large-message chunk
 * reassembly through the SIP path, FT descriptors, IMDN receipts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InboundAsyncRcsPass3Test {
    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private lateinit var testDb: RcsDatabase

    @BeforeTest fun setUp() {
        testDb = Room.inMemoryDatabaseBuilder(app, RcsDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        RcsDatabase.overrideForTest = testDb
    }

    @AfterTest fun tearDown() {
        RcsDatabase.overrideForTest = null
        testDb.close()
    }

    private fun service() = Robolectric.setupService(RcsSyncService::class.java)

    private fun envelope(raw: String, contentType: String? = "text/plain", callId: String = "c-p3") =
        Envelope(from = "+15550004001", contentType = contentType, raw = raw, callId = callId, viaBranch = "br")

    private fun awaitDb(timeoutMs: Long = 10_000, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!runBlocking { condition() } && System.currentTimeMillis() < deadline) Thread.sleep(15)
        assertTrue(runBlocking { condition() }, "db condition not met")
    }

    @Test fun revokeBlanksCachedRowAndReturnsReceipt() = runBlocking {
        testDb.cachedMessageDao().upsert(
            RcsCachedMessage(messageId = "orig-1", conversationId = "+15550004001", body = "secret", timestamp = 1, outgoing = false),
        )
        val service = service()
        val parsed = service.parseInbound(envelope(buildRevokeBody("orig-1")))
        assertNotNull(parsed)
        assertEquals(InboundKind.Receipt, parsed.kind)
        awaitDb { testDb.cachedMessageDao().get("orig-1")?.body == "" }
        // Revoking an unknown row still returns a receipt and changes nothing.
        val again = service.parseInbound(envelope(buildRevokeBody("missing-id"), callId = "c-p3b"))
        assertEquals(InboundKind.Receipt, again?.kind)
        service.onDestroy()
    }

    @Test fun editSwapsCachedRowBody() = runBlocking {
        testDb.cachedMessageDao().upsert(
            RcsCachedMessage(messageId = "orig-2", conversationId = "+15550004001", body = "old text", timestamp = 1, outgoing = false),
        )
        val service = service()
        val parsed = service.parseInbound(envelope(buildEditBody("orig-2", "new text")))
        assertEquals(InboundKind.Receipt, parsed?.kind)
        awaitDb { testDb.cachedMessageDao().get("orig-2")?.body == "new text" }
        service.onDestroy()
    }

    @Test fun geopushBecomesLocationRow() {
        val service = service()
        val parsed = service.parseInbound(envelope(buildGeopushBody(37.42, -122.08, "HQ")))
        assertNotNull(parsed)
        assertEquals("HQ", parsed.body)
        assertEquals("geo:37.42,-122.08", parsed.ftUrl)
        assertEquals("application/vnd.gsma.rcs.geopush+xml", parsed.ftMime)
        // Unlabelled push falls back to the coordinate text.
        val plain = service.parseInbound(envelope(buildGeopushBody(1.5, 2.5), callId = "c-geo2"))
        assertEquals("📍 1.5, 2.5", plain?.body)
        service.onDestroy()
    }

    @Test fun largeMessageChunksReassembleInOrder() {
        val service = service()
        // Part 2 arrives first: only a receipt, nothing to show yet.
        val second = service.parseInbound(
            envelope("Message-ID: big-1\r\nPart: 2/2\r\n\r\nworld", callId = "c-chunk2"),
        )
        assertEquals(InboundKind.Receipt, second?.kind)
        val first = service.parseInbound(
            envelope("Message-ID: big-1\r\nPart: 1/2\r\n\r\nhello", callId = "c-chunk1"),
        )
        assertNotNull(first)
        assertEquals(InboundKind.Text, first.kind)
        assertEquals("helloworld", first.body) // parts are trimmed before joining
        assertEquals("in-big-1", first.messageId)
        service.onDestroy()
    }

    @Test fun bufferLargeChunkRejectsBadPartNumbers() {
        assertNull(bufferLargeChunk("conv", "id", 0, 2, "x"))
        assertNull(bufferLargeChunk("conv", "id", 3, 2, "x"))
        assertNull(bufferLargeChunk("conv", "id", 1, 0, "x"))
        assertNull(bufferLargeChunk("conv", "id2", 1, 2, "Message-ID: id2\r\nPart: 1/2\r\n\r\nonly"))
        assertEquals(
            "onlyb",
            bufferLargeChunk("conv", "id2", 2, 2, "Message-ID: id2\r\nPart: 2/2\r\n\r\nb"),
        )
    }

    @Test fun fileTransferDescriptorExposesUrlAndMime() {
        val service = service()
        val body = "File-URL: https://files.example.com/a.jpg\r\nFile-MIME: image/jpeg\r\n\r\nphoto"
        val parsed = service.parseInbound(envelope(body))
        assertNotNull(parsed)
        assertEquals("https://files.example.com/a.jpg", parsed.ftUrl)
        assertEquals("image/jpeg", parsed.ftMime)
        assertTrue(parsed.body.contains("photo"))
        service.onDestroy()
    }

    @Test fun imdnEnvelopeReturnsReceiptAndRecordsDisposition() {
        val service = service()
        val imdn = """
            <?xml version="1.0"?>
            <imdn xmlns="urn:ietf:params:xml:ns:imdn">
              <message-id>orig-9</message-id>
              <status><display/></status>
            </imdn>
        """.trimIndent()
        val parsed = service.parseInbound(
            envelope(imdn, contentType = "message/imdn+xml", callId = "c-imdn"),
        )
        assertEquals(InboundKind.Receipt, parsed?.kind)
        assertEquals(
            com.vayunmathur.communicate.data.rcs.ImdnDisposition.Displayed,
            com.vayunmathur.communicate.data.rcs.RcsImdn.statusFor("orig-9"),
        )
        service.onDestroy()
    }

    @Test fun decodeAndExtractHelpers() {
        // decodePayloadBody: raw, CRLF-wrapped, LF-wrapped, junk.
        assertEquals(
            "hello",
            decodePayloadBody("aGVsbG8=")?.toString(Charsets.UTF_8),
        )
        assertEquals(
            "hello",
            decodePayloadBody("Content-Type: x\r\n\r\naGVsbG8=")?.toString(Charsets.UTF_8),
        )
        assertEquals(
            "hello",
            decodePayloadBody("hdr\n\naGVsbG8=")?.toString(Charsets.UTF_8),
        )
        assertNull(decodePayloadBody("   "))
        assertNull(decodePayloadBody("!!!"))
        // extractTextBody: plain, cpim, blank, cpim without payload.
        assertEquals("plain", extractTextBody("plain".toByteArray()))
        assertNull(extractTextBody("   ".toByteArray()))
        assertNull(extractTextBody("Content-Type: message/cpim\r\n\r\n".toByteArray()))
        assertEquals(
            "inner",
            extractTextBody("Content-Type: message/cpim\r\n\r\nhdr\r\n\r\ninner".toByteArray()),
        )
    }
}
