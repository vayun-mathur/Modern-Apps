package com.vayunmathur.share.network.transport

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.vayunmathur.share.domain.protocol.ShareState
import com.vayunmathur.share.platform.ReceivedFileStore
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TcpTransportExtraTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `reject path and cancel handle`() = runBlocking {
        val server = TcpTransport("Srv", ReceivedFileStore(ctx))
        val client = TcpTransport("Cli", ReceivedFileStore(ctx))
        try {
            val port = server.listen()
            val conn = client.connect("127.0.0.1", port)
            val incoming = withTimeoutOrNull(10_000) { server.incomingConnections.first { it.isNotEmpty() }.first() }!!
            val src = File(ctx.cacheDir, "rej-${System.nanoTime()}.txt").apply { writeText("reject me") }
            val sendJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                client.sendFiles(conn, listOf(src))
            }
            var rejected = false
            val dl = System.currentTimeMillis() + 12_000
            while (System.currentTimeMillis() < dl && !rejected) {
                if (incoming.state.value == ShareState.AwaitingAccept) {
                    assertTrue(server.acceptIncoming(incoming.sessionHandle, false) >= 0)
                    rejected = true
                }
                delay(50)
            }
            assertTrue(rejected)
            withTimeoutOrNull(10_000) { sendJob.join() }
            // cancel an unknown handle is a no-op; cancel live then disconnect
            client.cancel(999999L)
            client.cancel(conn.sessionHandle)
            server.retire(incoming.sessionHandle)
            assertTrue(server.connectionFor(incoming.sessionHandle) == null)
        } finally { client.release(); server.release() }
    }

    @Test
    fun `second sendFiles on same connection is ignored`() = runBlocking {
        val server = TcpTransport("Srv2", ReceivedFileStore(ctx))
        val client = TcpTransport("Cli2", ReceivedFileStore(ctx))
        try {
            val port = server.listen()
            val conn = client.connect("127.0.0.1", port)
            val incoming = withTimeoutOrNull(10_000) { server.incomingConnections.first { it.isNotEmpty() }.first() }!!
            val f1 = File(ctx.cacheDir, "one-${System.nanoTime()}.txt").apply { writeText("first") }
            val f2 = File(ctx.cacheDir, "two-${System.nanoTime()}.txt").apply { writeText("second") }
            val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { client.sendFiles(conn, listOf(f1)) }
            var accepted = false
            val dl = System.currentTimeMillis() + 12_000
            while (System.currentTimeMillis() < dl && !accepted) {
                if (incoming.state.value == ShareState.AwaitingAccept) { server.acceptIncoming(incoming.sessionHandle, true); accepted = true }
                delay(40)
            }
            assertTrue(accepted)
            withTimeoutOrNull(15_000) { job.join() }
            // introduction already sent: second call returns immediately, no crash
            client.sendFiles(conn, listOf(f2))
            val got = withTimeoutOrNull(8_000) { incoming.receivedFiles.first { it.isNotEmpty() }.first() }
            assertNotNull(got)
            assertEquals(f1.name, got!!.name)
            client.disconnect(conn)
            assertTrue(client.connectionFor(conn.sessionHandle) == null)
        } finally { client.release(); server.release() }
    }

    @Test
    fun `empty sendFiles is a no-op`() = runBlocking {
        val t = TcpTransport("Empty", ReceivedFileStore(ctx))
        try {
            val server = TcpTransport("EmptySrv", ReceivedFileStore(ctx))
            try {
                val port = server.listen()
                val conn = t.connect("127.0.0.1", port)
                t.sendFiles(conn, emptyList())
                assertNotNull(t.connectionFor(conn.sessionHandle))
            } finally { server.release() }
        } finally { t.release() }
    }
}
