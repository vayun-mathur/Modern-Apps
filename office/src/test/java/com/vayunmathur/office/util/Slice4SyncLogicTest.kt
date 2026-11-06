package com.vayunmathur.office.util

import com.vayunmathur.e2ee.E2ee
import com.vayunmathur.e2ee.E2eeKeyStore
import com.vayunmathur.e2ee.PqcIdentity
import kotlinx.coroutines.runBlocking
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 4/4 OfficeSync pure-logic tests: no network, no identity —
 * live-message parsing, AES helpers, id/key generation, wire-type JSON.
 */
class Slice4SyncLogicTest {

    // ---- parseLive ----

    @Test fun parseLiveValid() {
        val msg = OfficeSync.parseLive(
            """{"t":"append","channel":"doc:1","actions":["a"],"seq":7,"data":"x"}""")
        assertNotNull(msg)
        assertEquals("append", msg.t)
        assertEquals("doc:1", msg.channel)
        assertEquals(listOf("a"), msg.actions)
        assertEquals(7, msg.seq)
        assertEquals("x", msg.data)
    }

    @Test fun parseLiveDefaults() {
        val msg = OfficeSync.parseLive("{}")
        assertNotNull(msg)
        assertEquals("", msg.t)
        assertEquals(0, msg.seq)
        assertTrue(msg.actions.isEmpty())
    }

    @Test fun parseLiveMalformedIsNull() {
        assertNull(OfficeSync.parseLive("not json"))
        assertNull(OfficeSync.parseLive(""))
        assertNull(OfficeSync.parseLive("""{"t":12345,"channel":[]}"""))
    }

    @Test fun parseLivePresenceData() {
        val msg = OfficeSync.parseLive("""{"t":"presence","channel":"c","data":"blob"}""")
        assertEquals("blob", assertNotNull(msg).data)
    }

    // ---- decrypt (AES round-trip + failure) ----

    @Test fun decryptRoundTrip() {
        val key = E2ee.newContentKey()
        val cipher = Base64.encode(E2ee.aesEncrypt(key, "hello office".encodeToByteArray()))
        assertEquals("hello office", OfficeSync.decrypt(key, cipher))
    }

    @Test fun decryptWrongKeyIsNull() {
        val key = E2ee.newContentKey()
        val other = E2ee.newContentKey()
        val cipher = Base64.encode(E2ee.aesEncrypt(key, "secret".encodeToByteArray()))
        assertNull(OfficeSync.decrypt(other, cipher))
    }

    @Test fun decryptGarbageIsNull() {
        val key = E2ee.newContentKey()
        assertNull(OfficeSync.decrypt(key, "!!!not-base64!!!"))
        assertNull(OfficeSync.decrypt(key, Base64.encode("short".encodeToByteArray())))
    }

    // ---- id/key generation ----

    @Test fun newDocumentIdUnique() {
        val a = OfficeSync.newDocumentId()
        val b = OfficeSync.newDocumentId()
        assertTrue(a.isNotBlank())
        assertNotEquals(a, b)
    }

    @Test fun newDocumentKeySizedAndUnique() {
        val a = OfficeSync.newDocumentKey()
        val b = OfficeSync.newDocumentKey()
        assertEquals(32, a.size)
        assertTrue(!a.contentEquals(b))
    }

    // ---- wire types ----

    @Test fun inviteJsonRoundTrip() {
        val invite = OfficeSync.Invite(
            docId = "doc-1",
            key = "k",
            title = "Report",
            charMode = true,
            role = "editor",
            ownerKey = "ok",
            charKind = "text")
        val json = OfficeSync.json.encodeToString(OfficeSync.Invite.serializer(), invite)
        val back = OfficeSync.json.decodeFromString(OfficeSync.Invite.serializer(), json)
        assertEquals(invite, back)
    }

    @Test fun inviteDefaultsForBackCompat() {
        val back = OfficeSync.json.decodeFromString(
            OfficeSync.Invite.serializer(),
            """{"docId":"d","key":"k","title":"t"}""")
        assertEquals(false, back.charMode)
        assertEquals("editor", back.role)
        assertEquals("", back.ownerKey)
        assertEquals("", back.charKind)
    }

    @Test fun joinRequestKindDiscriminator() {
        val req = OfficeSync.JoinRequest(docId = "d", requesterId = "r", name = "Ann")
        assertEquals("req", req.kind)
        // The shared Json omits defaulted fields on encode, so the kind marker is
        // NOT on the wire; decode restores it from the default. sortInboxText must
        // therefore still route such blobs to requests (see test below).
        val json = OfficeSync.json.encodeToString(OfficeSync.JoinRequest.serializer(), req)
        assertEquals(req, OfficeSync.json.decodeFromString(OfficeSync.JoinRequest.serializer(), json))
    }

    @Test fun sortInboxTextRoutesJoinRequestsWithoutKindMarker() {
        // Exactly what sendJoinRequest puts on the wire (no "kind" field).
        val reqJson = OfficeSync.json.encodeToString(
            OfficeSync.JoinRequest.serializer(),
            OfficeSync.JoinRequest(docId = "d", requesterId = "r", name = "Ann"))
        val inviteJson = OfficeSync.json.encodeToString(
            OfficeSync.Invite.serializer(),
            OfficeSync.Invite(docId = "d2", key = "k", title = "t"))
        val (invites, requests) = OfficeSync.sortInboxText(listOf(reqJson, inviteJson, "garbage"))
        assertEquals(1, invites.size)
        assertEquals("d2", invites[0].docId)
        assertEquals(1, requests.size)
        assertEquals("r", requests[0].requesterId)
        assertEquals("d", requests[0].docId)
    }

    @Test fun sortInboxTextSkipsForeignBlobs() {
        val (invites, requests) = OfficeSync.sortInboxText(
            listOf("garbage", "{}", """{"docId":"x"}"""))
        assertTrue(invites.isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test fun docActionDefaults() {
        val a = OfficeSync.json.decodeFromString(
            OfficeSync.DocAction.serializer(), "{}")
        assertEquals("snapshot", a.type)
        assertEquals("", a.flat)
    }

    @Test fun resultsHolders() {
        val inv = OfficeSync.InvitesResult(emptyList(), 3)
        assertEquals(3, inv.seq)
        val inbox = OfficeSync.InboxResult(listOf(), listOf(), 5)
        assertEquals(5, inbox.seq)
        val docs = OfficeSync.DocActionsResult(listOf("x"), 9)
        assertEquals(listOf("x"), docs.items)
        assertEquals(9, docs.seq)
    }

    @Test fun liveMsgRoundTrip() {
        val m = OfficeSync.LiveMsg(t = "append", channel = "c", actions = listOf("a"), seq = 2)
        val json = OfficeSync.json.encodeToString(OfficeSync.LiveMsg.serializer(), m)
        val back = OfficeSync.json.decodeFromString(OfficeSync.LiveMsg.serializer(), json)
        assertEquals(m, back)
    }

    // ---- identity-backed crypto (OfficeSyncCryptoKt) ----

    private class MemStore : E2eeKeyStore {
        private val m = HashMap<String, ByteArray>()
        override suspend fun getBytes(name: String) = m[name]
        override suspend fun setBytes(name: String, value: ByteArray, onlyIfAbsent: Boolean) {
            if (onlyIfAbsent && m.containsKey(name)) return
            m[name] = value
        }
    }

    private fun withIdentity(): PqcIdentity {
        val id = runBlocking { PqcIdentity.loadOrCreate(MemStore(), "slice4") }
        OfficeSync.identity = id
        return id
    }

    @Test fun sealUnsealRoundTrip() = runBlocking {
        val me = withIdentity()
        val peer = PqcIdentity.loadOrCreate(MemStore(), "slice4peer")
        val sealed = OfficeSync.seal(peer.publicBundle, "hello".encodeToByteArray())
        // Sealed to the peer: I cannot open it, the peer's own unseal path can.
        // Here assert the OfficeSync.decrypt-style failure is not applicable;
        // instead seal to self and unseal.
        val toSelf = OfficeSync.seal(me.publicBundle, "self-msg".encodeToByteArray())
        assertContentEquals("self-msg".encodeToByteArray(), OfficeSync.unseal(toSelf))
        assertTrue(sealed.isNotEmpty())
    }

    @Test fun signVerifyRoundTrip() = runBlocking {
        val me = withIdentity()
        val data = "doc-bytes".encodeToByteArray()
        val sig = OfficeSync.sign(data)
        assertTrue(OfficeSync.verify(me.publicBundle, data, sig))
        assertFalse(OfficeSync.verify(me.publicBundle, "other".encodeToByteArray(), sig))
    }

    @Test fun securityCodeStable() = runBlocking {
        val me = withIdentity()
        val peer = PqcIdentity.loadOrCreate(MemStore(), "slice4peer2")
        val a = OfficeSync.securityCode(peer.publicBundle)
        val b = OfficeSync.securityCode(peer.publicBundle)
        assertNotNull(a)
        assertEquals(a, b)
        assertTrue(a!!.isNotBlank())
    }

    @Test fun liveOpsWithoutSessionAreSafe() = runBlocking {
        OfficeSync.stopLive()
        // No session: presence send is a no-op, append returns false. No network.
        OfficeSync.sendPresence("ch", E2ee.newContentKey(), "typing")
        assertEquals(false, OfficeSync.liveAppend("ch", E2ee.newContentKey(), listOf("x")))
        OfficeSync.stopLive()
    }
}
