package com.vayunmathur.office.util

import com.vayunmathur.e2ee.E2ee
import com.vayunmathur.library.network.SimpleResponse
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 4 pass 2: OfficeSync relay behaviour through the fake [OfficeSync.HttpTransport]
 * seam — tests must never reach the real relay (CI has internet), so every response
 * below is scripted: success bodies, HTTP errors, and transport failure (null).
 */
@RunWith(RobolectricTestRunner::class)
class Slice4Pass2SyncNetworkTest {

    @AfterTest fun tearDown() {
        OfficeSync.httpTransport = null
    }

    private fun response(status: Int, body: String, url: String = "") =
        SimpleResponse(status, "", body, emptyMap(), url)

    private fun installFake(handler: (String, String) -> SimpleResponse?) {
        val urls = mutableListOf<String>()
        OfficeSync.httpTransport = OfficeSync.HttpTransport { url, body ->
            urls += url
            handler(url, body)
        }
    }

    @Test fun documentKeysAndIdsAreFresh() {
        val k1 = OfficeSync.newDocumentKey()
        val k2 = OfficeSync.newDocumentKey()
        assertEquals(32, k1.size)
        assertFalse(k1.contentEquals(k2), "content keys are random")
        assertNotEquals(OfficeSync.newDocumentId(), OfficeSync.newDocumentId())
        assertTrue(OfficeSync.newDocumentId().isNotBlank())
    }

    @Test fun transportFailureDegradesGracefully() = runBlocking {
        installFake { _, _ -> null }
        val key = OfficeSync.newDocumentKey()
        assertNull(OfficeSync.getKey("device-x"))
        assertEquals(emptyList(), OfficeSync.pullDocActions("doc1", key, 7).items)
        assertEquals(7, OfficeSync.pullDocActions("doc1", key, 7).seq)
        assertNull(OfficeSync.appendDocActions("doc1", key, listOf("op")))
        assertNull(OfficeSync.append("chan", listOf("blob")))
        assertNull(OfficeSync.pull("chan", 3))
        val inbox = OfficeSync.pullInbox(5)
        assertTrue(inbox.invites.isEmpty() && inbox.requests.isEmpty())
        assertEquals(5, inbox.seq)
        assertTrue(OfficeSync.pullInvites(2).invites.isEmpty())
        assertFalse(OfficeSync.sendInvite("recip", "doc1", key, "Title", false, "editor", "", ""))
        assertFalse(OfficeSync.sendJoinRequest("owner", "doc1", "req", "Name"))
    }

    @Test fun scriptedRelaySuccessPaths() = runBlocking {
        val key = OfficeSync.newDocumentKey()
        val encrypted = Base64.encode(E2ee.aesEncrypt(key, "hello op".encodeToByteArray()))
        val seenPaths = mutableListOf<String>()
        OfficeSync.httpTransport = OfficeSync.HttpTransport { url, _ ->
            seenPaths += url
            when {
                url.endsWith("/getkey") -> response(200, Base64.encode(byteArrayOf(9, 8, 7)))
                url.endsWith("/append") -> response(200, """{"seq":42}""")
                url.endsWith("/pull") -> response(
                    200,
                    """{"actions":["$encrypted","!!not-base64!!"],"seq":9}""",
                )
                else -> response(404, "")
            }
        }

        assertEquals(byteArrayOf(9, 8, 7).toList(), OfficeSync.getKey("device-y")!!.toList())
        assertEquals(42, OfficeSync.appendDocActions("doc1", key, listOf("op")))
        assertEquals(42, OfficeSync.append("chan", listOf("blob")))

        // Garbage action in the batch is skipped, the valid one decrypts.
        val pulled = OfficeSync.pullDocActions("doc1", key, 0)
        assertEquals(listOf("hello op"), pulled.items)
        assertEquals(9, pulled.seq)

        // Inbox blobs cannot be decrypted without an identity: batch is emptied, seq kept.
        val inbox = OfficeSync.pullInbox(0)
        assertTrue(inbox.invites.isEmpty() && inbox.requests.isEmpty())
        assertEquals(9, inbox.seq)

        assertTrue(seenPaths.all { it.startsWith("https://api.maproj.org/office/") }, seenPaths.toString())
    }

    @Test fun httpErrorStatusIsFailure() = runBlocking {
        installFake { _, _ -> response(500, "boom") }
        val key = OfficeSync.newDocumentKey()
        assertNull(OfficeSync.getKey("device-x"))
        assertNull(OfficeSync.append("chan", listOf("blob")))
        assertNull(OfficeSync.pull("chan", 0))
        // getKey fails first, so invite/join never reach PQC sealing.
        assertFalse(OfficeSync.sendInvite("recip", "doc1", key, "T", false, "editor", "", ""))
        assertFalse(OfficeSync.sendJoinRequest("owner", "doc1", "req", "N"))
    }

    @Test fun liveAppendAndPresenceWithoutSession() = runBlocking {
        val key = OfficeSync.newDocumentKey()
        // No WebSocket session: append refuses, presence is a silent no-op.
        assertFalse(OfficeSync.liveAppend("chan", key, listOf("x")))
        OfficeSync.sendPresence("chan", key, "hello")
        OfficeSync.stopLive()
    }

    @Test fun sortInboxTextSortsKindsAndSkipsGarbage() {
        val inviteJson = """{"docId":"d1","key":"a2V5","title":"Report","charMode":true,"role":"viewer","ownerKey":"b3du","charKind":"text"}"""
        val reqJson = """{"kind":"req","docId":"d2","requesterId":"dev1","name":"Carol"}"""
        val kindlessReq = """{"docId":"d3","requesterId":"dev2","name":"Dan"}"""
        val (invites, requests) = OfficeSync.sortInboxText(
            listOf(inviteJson, reqJson, kindlessReq, "not json", "{}", """{"kind":"req","bad":true}""")
        )
        assertEquals(1, invites.size)
        assertEquals("d1", invites[0].docId)
        assertEquals("Report", invites[0].title)
        assertTrue(invites[0].charMode)
        assertEquals("viewer", invites[0].role)
        assertEquals(listOf("d2", "d3"), requests.map { it.docId })
        assertEquals("Carol", requests[0].name)

        val (noInvites, noRequests) = OfficeSync.sortInboxText(emptyList())
        assertTrue(noInvites.isEmpty() && noRequests.isEmpty())
    }

    @Test fun parseLiveEdgeCases() {
        assertNull(OfficeSync.parseLive("""{"t":"append","seq":"NaN"}"""))
        val msg = OfficeSync.parseLive("""{"channel":"c","actions":[],"extra":42}""")
        assertTrue(msg != null)
        assertEquals("c", msg!!.channel)
        assertEquals("", msg.t)
    }
}
