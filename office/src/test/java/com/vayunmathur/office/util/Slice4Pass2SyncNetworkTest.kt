package com.vayunmathur.office.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 4 pass 2: OfficeSync offline behaviour. No relay is reachable from
 * unit tests, and OfficeSync.raw() maps every transport failure to null —
 * these tests pin the graceful-degradation contract of each public entry
 * point plus the pure inbox/live-message sorting.
 */
@RunWith(RobolectricTestRunner::class)
class Slice4Pass2SyncNetworkTest {

    @Test fun documentKeysAndIdsAreFresh() {
        val k1 = OfficeSync.newDocumentKey()
        val k2 = OfficeSync.newDocumentKey()
        assertEquals(32, k1.size)
        assertFalse(k1.contentEquals(k2), "content keys are random")
        assertNotEquals(OfficeSync.newDocumentId(), OfficeSync.newDocumentId())
        assertTrue(OfficeSync.newDocumentId().isNotBlank())
    }

    @Test fun networkFailurePathsDegradeGracefully() = runBlocking {
        val key = OfficeSync.newDocumentKey()
        // Relay unreachable / NetworkClient uninitialised in tests: raw() -> null.
        assertNull(OfficeSync.getKey("device-x"))
        assertEquals(
            OfficeSync.DocActionsResult(emptyList(), 7).items,
            OfficeSync.pullDocActions("doc1", key, 7).items,
        )
        assertEquals(7, OfficeSync.pullDocActions("doc1", key, 7).seq)
        assertNull(OfficeSync.appendDocActions("doc1", key, listOf("op")))
        assertNull(OfficeSync.append("chan", listOf("blob")))
        assertNull(OfficeSync.pull("chan", 3))
        val inbox = OfficeSync.pullInbox(5)
        assertTrue(inbox.invites.isEmpty() && inbox.requests.isEmpty())
        assertEquals(5, inbox.seq)
        val invites = OfficeSync.pullInvites(2)
        assertTrue(invites.invites.isEmpty())
        assertEquals(2, invites.seq)
        assertFalse(
            OfficeSync.sendInvite("recip", "doc1", key, "Title", false, "editor", "", "")
        )
        assertFalse(OfficeSync.sendJoinRequest("owner", "doc1", "req", "Name"))
    }

    @Test fun liveAppendAndPresenceWithoutSession() = runBlocking {
        val key = OfficeSync.newDocumentKey()
        // No WebSocket session: append refuses, presence is a silent no-op.
        assertFalse(OfficeSync.liveAppend("chan", key, listOf("x")))
        OfficeSync.sendPresence("chan", key, "hello")
        OfficeSync.stopLive()
    }

    @Test fun startAndStopLiveDoesNotThrow() {
        val scope = CoroutineScope(Dispatchers.IO)
        try {
            var connected = false
            OfficeSync.startLive(scope, "chan-test", onConnected = { connected = true }, onMessage = {})
            // Same channel while active: second call is a no-op guard.
            OfficeSync.startLive(scope, "chan-test", onConnected = { connected = true }, onMessage = {})
            OfficeSync.stopLive()
            assertFalse(connected, "no relay in tests, so no connection callback")
        } finally {
            scope.cancel()
            OfficeSync.stopLive()
        }
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
