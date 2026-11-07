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

    /**
     * Connects, retrying briefly: accept() binds on the pool thread, so a
     * client that dials before that thread runs gets ECONNREFUSED rather
     * than sitting in the backlog (the pre-existing tests flaked on this
     * race under load, on unmodified main too).
     */
    private fun connect(port: Int): Socket {
        var last: Exception? = null
        repeat(50) {
            try {
                return Socket("127.0.0.1", port)
            } catch (e: java.net.ConnectException) {
                last = e
                Thread.sleep(100)
            }
        }
        throw last!!
    }

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
            connect(port).use {
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
    fun `the same server accepts a second connection after the first ends`() {
        // Regression: the IPv4 listener socket was never stored in the
        // `server` field (the also-chain bound only to the dual-stack
        // fallback), so a second accept() tried to bind the same port
        // again and threw BindException, and close() leaked the socket.
        // ProjectionService loops on accept() across sessions, so a DHU
        // reconnect depended on this working.
        val port = freePort()
        val server = HeadUnitServer(port)
        val pool = Executors.newSingleThreadExecutor()
        try {
            repeat(2) {
                val accepted = pool.submit<Socket> { server.accept() }
                connect(port).use {
                    accepted.get(10, TimeUnit.SECONDS).close()
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
