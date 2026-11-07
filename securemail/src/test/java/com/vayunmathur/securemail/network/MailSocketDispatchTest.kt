package com.vayunmathur.securemail.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailSocketDispatchTest {

    @Test fun offlineOperationsFailWithoutSession() = runBlocking {
        MailSocket.stop()
        assertFalse(MailSocket.connected)
        assertFalse(MailSocket.publish("me@maemail.org", "bob@x.org", byteArrayOf(1)))
        assertEquals("no session (relay offline)", MailSocket.lastPublishError)
        assertNull(MailSocket.getKey("bob@x.org"))
        assertNull(MailSocket.fetch("msg-1"))
        assertNull(MailSocket.claim("me@maemail.org", "PUB") { byteArrayOf(1) })
    }

    @Test fun dispatchDeliversInboundMessages() = runBlocking {
        val received = mutableListOf<MailSocket.InboundMsg>()
        // Build a MSG frame using the same layout parseMsg expects:
        // op, flags, u32 idLen, id, u16 rcptLen, rcpt, raw.
        val id = "msg-42".toByteArray()
        val rcpt = "me@maemail.org".toByteArray()
        val raw = "hello".toByteArray()
        val frame = ByteArray(1 + 1 + 4 + id.size + 2 + rcpt.size + raw.size)
        frame[0] = MailSocket.OP_MSG
        frame[1] = MailSocket.FLAG_PLAIN
        frame[2] = 0; frame[3] = 0; frame[4] = 0; frame[5] = id.size.toByte()
        id.copyInto(frame, 6)
        frame[6 + id.size] = 0; frame[7 + id.size] = rcpt.size.toByte()
        rcpt.copyInto(frame, 8 + id.size)
        raw.copyInto(frame, 8 + id.size + rcpt.size)

        MailSocket.dispatch(frame) { received += it }
        assertEquals(1, received.size)
        assertEquals("msg-42", received.single().id)
        assertEquals("me@maemail.org", received.single().deliveredTo)
        assertTrue(received.single().flags.unencrypted)
        assertEquals("hello", received.single().raw.toString(Charsets.UTF_8))
    }

    @Test fun dispatchCompletesPendingWaiters() = runBlocking {
        val keyWaiter = CompletableDeferred<MailSocket.KeyResult?>()
        MailSocket.pendingKeys["bob@x.org"] = keyWaiter
        val fetchWaiter = CompletableDeferred<ByteArray?>()
        MailSocket.pendingFetches["m-1"] = fetchWaiter
        val subWaiter = CompletableDeferred<Int>()
        MailSocket.pendingSubs["me@maemail.org"] = subWaiter

        // GETKEY_RESP: op, status, u16 addrLen, addr, key bytes.
        val addr = "bob@x.org".toByteArray()
        val keyFrame = ByteArray(1 + 1 + 2 + addr.size + 3)
        keyFrame[0] = MailSocket.OP_GETKEY_RESP
        keyFrame[1] = MailSocket.KEY_FOUND.toByte()
        keyFrame[3] = addr.size.toByte()
        addr.copyInto(keyFrame, 4)
        "KEY".toByteArray().copyInto(keyFrame, 4 + addr.size)
        MailSocket.dispatch(keyFrame) {}

        // FETCH_RESP: op, u16 idLen, id, raw.
        val idBytes = "m-1".toByteArray()
        val fetchFrame = ByteArray(1 + 2 + idBytes.size + 2)
        fetchFrame[0] = MailSocket.OP_FETCH_RESP
        fetchFrame[2] = idBytes.size.toByte()
        idBytes.copyInto(fetchFrame, 3)
        "RA".toByteArray().copyInto(fetchFrame, 3 + idBytes.size)
        MailSocket.dispatch(fetchFrame) {}

        // SUBRESP: op, status, u16 addrLen, addr.
        val me = "me@maemail.org".toByteArray()
        val subFrame = ByteArray(1 + 1 + 2 + me.size)
        subFrame[0] = MailSocket.OP_SUBRESP
        subFrame[1] = MailSocket.SUB_OK.toByte()
        subFrame[3] = me.size.toByte()
        me.copyInto(subFrame, 4)
        MailSocket.dispatch(subFrame) {}

        assertEquals("KEY", withTimeout(5_000) { keyWaiter.await() }?.armoredPublic)
        assertEquals("RA", withTimeout(5_000) { fetchWaiter.await() }?.toString(Charsets.UTF_8))
        assertEquals(MailSocket.SUB_OK, withTimeout(5_000) { subWaiter.await() })
    }

    @Test fun dispatchIgnoresUnknownAndTruncatedFrames() = runBlocking {
        var messages = 0
        MailSocket.dispatch(byteArrayOf(0x7F, 1, 2, 3)) { messages++ }
        MailSocket.dispatch(byteArrayOf(MailSocket.OP_MSG, 0)) { messages++ }
        MailSocket.dispatch(byteArrayOf()) { messages++ }
        assertEquals(0, messages)
    }

    @Test fun stopClearsPendingWaiters() = runBlocking {
        val waiter = CompletableDeferred<MailSocket.KeyResult?>()
        MailSocket.pendingKeys["a@x.org"] = waiter
        MailSocket.stop()
        assertNull(withTimeout(5_000) { waiter.await() })
        assertTrue(MailSocket.pendingKeys.isEmpty())
    }

    @Test fun fetchFrameShape() {
        val frame = MailSocket.fetchFrame("Msg-9")
        assertEquals(MailSocket.OP_FETCH_REQ, frame[0])
        assertEquals("Msg-9", frame.copyOfRange(3, frame.size).toString(Charsets.UTF_8))
    }

    @Test fun asyncDispatchToSuspendedWaiter() = runBlocking {
        val waiter = async { MailSocket.pendingKeys["c@x.org"]?.await() }
        // Register the waiter the way getKey() does, then dispatch the reply.
        val deferred = CompletableDeferred<MailSocket.KeyResult?>()
        MailSocket.pendingKeys["c@x.org"] = deferred
        val addr = "c@x.org".toByteArray()
        val frame = ByteArray(1 + 1 + 2 + addr.size)
        frame[0] = MailSocket.OP_GETKEY_RESP
        frame[1] = MailSocket.KEY_NONE.toByte()
        frame[3] = addr.size.toByte()
        addr.copyInto(frame, 4)
        MailSocket.dispatch(frame) {}
        assertNull(withTimeout(5_000) { deferred.await() }?.armoredPublic)
        waiter.cancel()
    }
}
