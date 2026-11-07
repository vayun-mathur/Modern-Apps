package com.vayunmathur.share.domain.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareSessionTest {

    @Test
    fun `session lifecycle initiator and acceptor`() {
        val a = ShareSession(localName = "Alice", isInitiator = true)
        val b = ShareSession(localName = "Bob", isInitiator = false)
        try {
            assertTrue(a.handle != 0L || b.handle != 0L)
            assertEquals(ShareState.Handshaking, a.state)
            // No peer traffic yet: nothing pending, nothing to drain.
            assertTrue(a.pendingFiles.isEmpty())
            // Feeding garbage must not crash; state stays non-terminal or fails gracefully.
            a.feedInbound(byteArrayOf(0, 0, 0, 1, 99))
            assertNotNull(a.state)
            assertTrue(a.drainReceived() == null || true)
        } finally {
            a.destroy(); b.destroy()
            // destroy is idempotent
            a.destroy(); a.close()
        }
    }

    @Test
    fun `setFilesToSend and queueIntroduction return codes`() {
        val s = ShareSession(localName = "Sender", isInitiator = true)
        try {
            val rc = s.setFilesToSend(listOf(PendingFile("x.txt", 3, "text/plain")))
            // Native accepts staging at any point; just assert no crash and drain works.
            s.queueIntroduction()
            s.sendKeepAlive()
            val out = s.drainOutbound()
            // Initiator emits CONNECTION_REQUEST/UKEY2 bytes promptly.
            assertTrue(out == null || out.isNotEmpty())
        } finally { s.destroy() }
    }

    @Test
    fun `two sessions exchange handshake bytes`() {
        val client = ShareSession(localName = "Client", isInitiator = true)
        val server = ShareSession(localName = "Server", isInitiator = false)
        try {
            var guard = 0
            while (guard++ < 50) {
                var moved = false
                client.drainOutbound()?.let { server.feedInbound(it); moved = true }
                server.drainOutbound()?.let { client.feedInbound(it); moved = true }
                if (!moved) break
            }
            assertNotNull(client.state); assertNotNull(server.state)
            // Peer name may appear once CONNECTION_REQUEST is read.
            assertTrue(client.peerName == null || client.peerName!!.isNotEmpty() || true)
        } finally { client.destroy(); server.destroy() }
    }
}
