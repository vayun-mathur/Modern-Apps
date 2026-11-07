package com.vayunmathur.auto.network

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Auto module: [HeadUnitServer] — the DHU loopback listener. A real
 * client/server socket pair over loopback proves the accept path hands the
 * connection over with Nagle disabled; closing an unbound server is a
 * silent no-op.
 *
 * Robolectric (not plain JVM): the server logs through `android.util.Log`.
 */
@RunWith(RobolectricTestRunner::class)
class HeadUnitServerTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `dhu loopback port matches the head-unit default`() {
        assertEquals(5277, HeadUnitServer.DHU_PORT)
    }

    @Test
    fun `an accepted socket has nagle disabled`() {
        val port = freePort()
        val server = HeadUnitServer(port)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val accepted = pool.submit<Socket> { server.accept() }
            // The backlog holds the client until accept() runs: no sleep needed.
            Socket("127.0.0.1", port).use {
                val socket = accepted.get(10, TimeUnit.SECONDS)
                try {
                    assertTrue(socket.tcpNoDelay)
                } finally {
                    socket.close()
                }
            }
        } finally {
            server.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `closing an unbound server is a silent no-op`() {
        HeadUnitServer(freePort()).close()
    }
}
