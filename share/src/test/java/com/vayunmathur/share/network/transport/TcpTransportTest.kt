package com.vayunmathur.share.network.transport

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.vayunmathur.share.domain.protocol.ShareState
import com.vayunmathur.share.platform.ReceivedFileStore
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TcpTransportTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `loopback file transfer completes end to end`() = runBlocking {
        val dir = File(ctx.cacheDir, "tcp-test-${System.nanoTime()}").apply { mkdirs() }
        val src = File(dir, "hello.txt").apply { writeText("hello tcp world") }
        val serverStore = ReceivedFileStore(ctx)
        val clientStore = ReceivedFileStore(ctx)
        val server = TcpTransport("ServerDevice", serverStore)
        val client = TcpTransport("ClientDevice", clientStore)
        try {
            val port = server.listen()
            assertTrue(port > 0)
            assertEquals(port, server.listenPort.value)
            // listen is idempotent
            assertEquals(port, server.listen())
            val conn = client.connect("127.0.0.1", port)
            assertNotNull(client.connectionFor(conn.sessionHandle))
            assertEquals(false, conn.incoming)
            // Wait for incoming on server
            val incoming = withTimeoutOrNull(15_000) {
                server.incomingConnections.first { it.isNotEmpty() }.first()
            }
            assertNotNull("server should see incoming connection", incoming)
            assertTrue(incoming!!.incoming)
            // Send in background, accept when asked
            val sendJob = async { client.sendFiles(conn, listOf(src)) }
            // Poll for AwaitingAccept then accept
            var accepted = false
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline && !accepted) {
                if (incoming.state.value == ShareState.AwaitingAccept) {
                    val rc = server.acceptIncoming(incoming.sessionHandle, true)
                    assertTrue("accept rc=$rc", rc >= 0)
                    accepted = true
                }
                delay(50)
            }
            assertTrue("should reach AwaitingAccept", accepted)
            withTimeoutOrNull(20_000) { sendJob.await() }
            // Receiver should get the file
            val got = withTimeoutOrNull(15_000) {
                incoming.receivedFiles.first { it.isNotEmpty() }.first()
            }
            assertNotNull("receiver should publish received file", got)
            assertEquals("hello.txt", got!!.name)
            // displayName falls back to endpoint until peer name known
            assertTrue(conn.displayName.isNotBlank())
            assertNull(client.connectionFor(999999L))
            assertEquals(-1, server.acceptIncoming(999999L, true))
        } finally {
            client.release(); server.release()
        }
    }

    @Test
    fun `setLocalIdentity and retire by handle`() = runBlocking {
        val store = ReceivedFileStore(ctx)
        val t = TcpTransport("A", store)
        try {
            t.setLocalIdentity("Renamed", ByteArray(0), "ABCD")
            assertNull(t.connectionFor(42L))
            t.retire(42L) // no-op
            t.stopListening()
            assertNull(t.listenPort.value)
        } finally { t.release() }
    }

    @Test
    fun `awaitIdle returns immediately with no connections`() = runBlocking {
        val t = TcpTransport("Idle", ReceivedFileStore(ctx))
        try {
            withTimeoutOrNull(3_000) { t.awaitIdle() }
            assertTrue(t.connections.value.isEmpty())
            assertTrue(t.incomingConnections.value.isEmpty())
        } finally { t.release() }
    }
}
