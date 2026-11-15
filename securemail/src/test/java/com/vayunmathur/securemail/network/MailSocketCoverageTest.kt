package com.vayunmathur.securemail.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Remaining [MailSocket] branches: truncated frames, dispatch routing, offline APIs. */
@RunWith(RobolectricTestRunner::class)
class MailSocketCoverageTest {

    @Test fun `fetch frame shape and u32 reader`() {
        val frame = MailSocket.fetchFrame("msg-42")
        assertEquals(MailSocket.OP_FETCH_REQ, frame[0])
        assertEquals(6, MailSocket.readU16(frame, 1))
        assertEquals("msg-42", frame.copyOfRange(3, frame.size).toString(Charsets.UTF_8))
        val buf = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        assertEquals(0x12345678, MailSocket.readU32(buf, 0))
        assertEquals(0, MailSocket.readU32(byteArrayOf(0, 0, 0, 0), 0))
    }

    @Test fun `parseMsg rejects oversized negative and truncated lengths`() {
        assertNull(MailSocket.parseMsg(ByteArray(0)))
        assertNull(MailSocket.parseMsg(byteArrayOf(MailSocket.OP_MSG, 0, 0, 0)))
        // idLen = -1 (0xFFFFFFFF)
        val negative = byteArrayOf(MailSocket.OP_MSG, 0, -1, -1, -1, -1, 0, 0)
        assertNull(MailSocket.parseMsg(negative))
        // idLen claims 10 bytes, frame carries 2
        val truncatedId = byteArrayOf(MailSocket.OP_MSG, 0, 0, 0, 0, 10, 'a'.code.toByte(), 'b'.code.toByte(), 0, 1, 'x'.code.toByte())
        assertNull(MailSocket.parseMsg(truncatedId))
        // Valid id but recipient length overruns the frame
        val id = "i1".toByteArray()
        val buf = ByteArray(6 + id.size + 2 + 1)
        buf[0] = MailSocket.OP_MSG
        buf[5] = id.size.toByte()
        id.copyInto(buf, 6)
        MailSocket.putU16(buf, 6 + id.size, 99)
        buf[buf.size - 1] = 'x'.code.toByte()
        assertNull(MailSocket.parseMsg(buf))
    }

    @Test fun `parseMsg with empty payload and empty recipient`() {
        val id = "e".toByteArray()
        val buf = ByteArray(6 + id.size + 2)
        buf[0] = MailSocket.OP_MSG
        buf[1] = MailSocket.FLAG_PLAIN
        buf[5] = id.size.toByte()
        id.copyInto(buf, 6)
        val parsed = MailSocket.parseMsg(buf)!!
        assertEquals("e", parsed.id)
        assertEquals("", parsed.deliveredTo)
        assertEquals(0, parsed.raw.size)
        assertTrue(parsed.flags.unencrypted)
        assertFalse(parsed.flags.spam)
    }

    @Test fun `parseSubResp and parseKeyResp reject truncated frames`() {
        assertNull(MailSocket.parseSubResp(ByteArray(0)))
        assertNull(MailSocket.parseSubResp(byteArrayOf(MailSocket.OP_SUBRESP, 0)))
        assertNull(MailSocket.parseKeyResp(ByteArray(0)))
        assertNull(MailSocket.parseKeyResp(byteArrayOf(MailSocket.OP_GETKEY_RESP, 1, 0)))
        // Address length overruns frame
        val buf = byteArrayOf(MailSocket.OP_SUBRESP, 0, 0, 9, 'a'.code.toByte())
        assertNull(MailSocket.parseSubResp(buf))
        assertNull(MailSocket.parseKeyResp(byteArrayOf(MailSocket.OP_GETKEY_RESP, 1, 0, 9, 'a'.code.toByte())))
    }

    @Test fun `parseKeyResp found without key bytes yields null key`() {
        val addr = "a@maemail.org".toByteArray()
        val frame = ByteArray(4 + addr.size)
        frame[0] = MailSocket.OP_GETKEY_RESP
        frame[1] = MailSocket.KEY_FOUND.toByte()
        MailSocket.putU16(frame, 2, addr.size)
        addr.copyInto(frame, 4)
        val (parsedAddr, key) = MailSocket.parseKeyResp(frame)!!
        assertEquals("a@maemail.org", parsedAddr)
        assertNull(key)
    }

    @Test fun `parseFetched rejects truncated and keeps empty payload`() {
        assertNull(MailSocket.parseFetched(ByteArray(0)))
        assertNull(MailSocket.parseFetched(byteArrayOf(MailSocket.OP_FETCH_RESP, 0)))
        assertNull(MailSocket.parseFetched(byteArrayOf(MailSocket.OP_FETCH_RESP, 0, 5, 'a'.code.toByte())))
        val frame = byteArrayOf(MailSocket.OP_FETCH_RESP, 0, 2, 'x'.code.toByte(), 'y'.code.toByte())
        val (id, raw) = MailSocket.parseFetched(frame)!!
        assertEquals("xy", id)
        assertEquals(0, raw.size)
    }

    @Test fun `subFrame with empty signature and getKeyFrame lowercases`() {
        val frame = MailSocket.subFrame("ME@MAEMAIL.ORG", ByteArray(0), "KEY")
        val addrLen = MailSocket.readU16(frame, 1)
        assertEquals("me@maemail.org", frame.copyOfRange(3, 3 + addrLen).toString(Charsets.UTF_8))
        assertEquals(0, MailSocket.readU16(frame, 3 + addrLen))
        assertEquals("KEY", frame.copyOfRange(5 + addrLen, frame.size).toString(Charsets.UTF_8))
    }

    @Test fun `offline socket apis return null or false and record publish error`() = runBlocking {
        MailSocket.stop()
        assertFalse(MailSocket.connected)
        assertNull(MailSocket.getKey("ada@example.org"))
        assertNull(MailSocket.fetch("msg-1"))
        assertNull(MailSocket.claim("me@maemail.org", "KEY") { byteArrayOf(1) })
        assertFalse(MailSocket.publish("me@maemail.org", "ada@example.org", byteArrayOf(1)))
        assertEquals("no session (relay offline)", MailSocket.lastPublishError)
        assertFalse(MailSocket.publish("me@maemail.org", "ada@example.org", byteArrayOf(1), unencrypted = true))
    }

    @Test fun `stop completes pending key and fetch waiters and cancels subs`() = runBlocking {
        val keyWaiter = CompletableDeferred<MailSocket.KeyResult?>()
        val fetchWaiter = CompletableDeferred<ByteArray?>()
        val subWaiter = CompletableDeferred<Int>()
        MailSocket.pendingKeys["stop@x.org"] = keyWaiter
        MailSocket.pendingFetches["stop-1"] = fetchWaiter
        MailSocket.pendingSubs["stop@x.org"] = subWaiter
        MailSocket.stop()
        assertNull(keyWaiter.await())
        assertNull(fetchWaiter.await())
        assertTrue(subWaiter.isCancelled)
        assertTrue(MailSocket.pendingKeys.isEmpty())
        assertTrue(MailSocket.pendingFetches.isEmpty())
        assertTrue(MailSocket.pendingSubs.isEmpty())
        assertFalse(MailSocket.connected)
    }

    @Test fun `start then stop leaves socket disconnected`() {
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.Job(),
        )
        MailSocket.start(
            scope = scope,
            identities = { emptyList() },
            sign = { _, _ -> null },
            onMessage = {},
        )
        Thread.sleep(300)
        MailSocket.stop()
        scope.cancel()
        assertFalse(MailSocket.connected)
        assertFalse(MailSocket.connectedFlow.value)
    }

    @Test fun `dispatch routes msg subresp keyresp and fetched to waiters`() = runBlocking {
        val received = mutableListOf<MailSocket.InboundMsg>()
        // MSG frame built the same way as the existing parse tests.
        val raw = byteArrayOf(4, 5)
        val idBytes = "m9".toByteArray()
        val rcptBytes = "me@maemail.org".toByteArray()
        val msg = ByteArray(6 + idBytes.size + 2 + rcptBytes.size + raw.size)
        msg[0] = MailSocket.OP_MSG
        msg[5] = idBytes.size.toByte()
        idBytes.copyInto(msg, 6)
        MailSocket.putU16(msg, 6 + idBytes.size, rcptBytes.size)
        rcptBytes.copyInto(msg, 8 + idBytes.size)
        raw.copyInto(msg, 8 + idBytes.size + rcptBytes.size)
        MailSocket.dispatch(msg) { received += it }
        assertEquals(1, received.size)
        assertEquals("m9", received.single().id)
        assertContentEquals(raw, received.single().raw)

        val subWaiter = CompletableDeferred<Int>()
        MailSocket.pendingSubs["me@maemail.org"] = subWaiter
        val addr = "me@maemail.org".toByteArray()
        val subResp = ByteArray(4 + addr.size)
        subResp[0] = MailSocket.OP_SUBRESP
        subResp[1] = MailSocket.SUB_OK.toByte()
        MailSocket.putU16(subResp, 2, addr.size)
        addr.copyInto(subResp, 4)
        MailSocket.dispatch(subResp) { }
        assertEquals(MailSocket.SUB_OK, subWaiter.await())
        assertFalse(MailSocket.pendingSubs.containsKey("me@maemail.org"))

        val keyWaiter = CompletableDeferred<MailSocket.KeyResult?>()
        MailSocket.pendingKeys["ada@example.org"] = keyWaiter
        val keyAddr = "ada@example.org".toByteArray()
        val keyResp = ByteArray(4 + keyAddr.size + 5)
        keyResp[0] = MailSocket.OP_GETKEY_RESP
        keyResp[1] = MailSocket.KEY_FOUND.toByte()
        MailSocket.putU16(keyResp, 2, keyAddr.size)
        keyAddr.copyInto(keyResp, 4)
        "ARMOR".toByteArray().copyInto(keyResp, 4 + keyAddr.size)
        MailSocket.dispatch(keyResp) { }
        assertEquals(MailSocket.KeyResult("ada@example.org", "ARMOR"), keyWaiter.await())

        val fetchWaiter = CompletableDeferred<ByteArray?>()
        MailSocket.pendingFetches["m9"] = fetchWaiter
        val fetched = byteArrayOf(MailSocket.OP_FETCH_RESP, 0, 2, 'm'.code.toByte(), '9'.code.toByte(), 7, 8)
        MailSocket.dispatch(fetched) { }
        assertContentEquals(byteArrayOf(7, 8), fetchWaiter.await()!!)

        // Unknown opcode and malformed MSG are ignored, not delivered.
        MailSocket.dispatch(byteArrayOf(0x7F, 1, 2)) { received += it }
        MailSocket.dispatch(byteArrayOf(MailSocket.OP_MSG)) { received += it }
        assertEquals(1, received.size)
    }
}
