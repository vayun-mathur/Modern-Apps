package com.vayunmathur.email.imap

import com.vayunmathur.email.network.imap.RawImapConnection
import com.vayunmathur.email.network.smtp.RawSmtpConnection
import com.vayunmathur.email.platform.ServerConfig
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * W5: STARTTLS and XOAUTH2 branches of the raw connections against scripted
 * loopback servers — advertised-but-refused TLS, accepted-then-unupgradable
 * TLS, and the XOAUTH2 challenge fallback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RawConnectionsW5Test {

    /** One-shot scripted server: [handler] writes/reads lines on the accepted socket. */
    private class ScriptServer(private val handler: (BufferedReader, BufferedWriter, (String) -> Unit) -> Unit) {
        private val serverSocket = ServerSocket(0)
        val port: Int get() = serverSocket.localPort
        val received = mutableListOf<String>()

        fun start() {
            thread(isDaemon = true) {
                try {
                    serverSocket.accept().use { socket ->
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                        val send: (String) -> Unit = { line ->
                            writer.write(line); writer.write("\r\n"); writer.flush()
                        }
                        handler(reader, writer, send)
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { serverSocket.close() }
                }
            }
        }

        fun readLine(reader: BufferedReader): String? = reader.readLine()?.also {
            synchronized(received) { received += it }
        }
    }

    @Test
    fun smtpStartTlsNotAdvertisedThrows() {
        val server = ScriptServer { reader, _, send ->
            send("220 fake ESMTP")
            reader.readLine() // EHLO
            send("250-localhost")
            send("250 AUTH PLAIN LOGIN")
            reader.readLine() // STARTTLS should never arrive, QUIT may
        }
        server.start()
        RawSmtpConnection(ServerConfig("127.0.0.1", server.port, false)).use { conn ->
            conn.connect()
            conn.ehlo()
            assertFailsWith<java.io.IOException> { conn.startTls("127.0.0.1") }
        }
    }

    @Test
    fun smtpStartTlsRefusedByServerThrows() {
        lateinit var server: ScriptServer
        server = ScriptServer { reader, _, send ->
            send("220 fake ESMTP")
            server.readLine(reader) // EHLO
            send("250-localhost")
            send("250 STARTTLS")
            server.readLine(reader) // STARTTLS
            send("454 TLS not available")
        }
        server.start()
        RawSmtpConnection(ServerConfig("127.0.0.1", server.port, false)).use { conn ->
            conn.connect()
            conn.ehlo()
            assertFailsWith<java.io.IOException> { conn.startTls("127.0.0.1") }
        }
        assertTrue(server.received.any { it.startsWith("STARTTLS") })
    }

    @Test
    fun smtpStartTlsAcceptedThenPlainServerFailsUpgrade() {
        lateinit var server: ScriptServer
        server = ScriptServer { reader, _, send ->
            send("220 fake ESMTP")
            server.readLine(reader)
            send("250-localhost")
            send("250 STARTTLS")
            server.readLine(reader)
            send("220 ready to start TLS")
            // Stay plain: the client's TLS handshake must fail, not hang forever
            // (socket timeouts bound it, and the server closing helps it along).
            Thread.sleep(500)
        }
        server.start()
        RawSmtpConnection(ServerConfig("127.0.0.1", server.port, false), trustAll = true).use { conn ->
            conn.connect()
            conn.ehlo()
            assertFailsWith<Exception> { conn.startTls("127.0.0.1") }
        }
    }

    @Test
    fun smtpXoauth2InlineAcceptedAndChallengeFallback() {
        // Inline AUTH XOAUTH2 accepted immediately.
        lateinit var server1: ScriptServer
        server1 = ScriptServer { reader, _, send ->
            send("220 fake ESMTP")
            server1.readLine(reader)
            send("250-localhost")
            send("250 AUTH XOAUTH2")
            server1.readLine(reader)
            send("235 authenticated")
        }
        server1.start()
        RawSmtpConnection(ServerConfig("127.0.0.1", server1.port, false)).use { conn ->
            conn.connect()
            conn.ehlo()
            conn.authXoauth2("me@x.com", "token123")
        }
        assertTrue(server1.received.any { it.startsWith("AUTH XOAUTH2 ") })

        // Inline rejected, challenge flow succeeds.
        lateinit var server2: ScriptServer
        server2 = ScriptServer { reader, _, send ->
            send("220 fake ESMTP")
            server2.readLine(reader)
            send("250-localhost")
            send("250 AUTH XOAUTH2")
            server2.readLine(reader) // AUTH XOAUTH2 <b64>
            send("535 nope")
            server2.readLine(reader) // AUTH XOAUTH2
            send("334 ")
            server2.readLine(reader) // <b64>
            send("235 authenticated")
        }
        server2.start()
        RawSmtpConnection(ServerConfig("127.0.0.1", server2.port, false)).use { conn ->
            conn.connect()
            conn.ehlo()
            conn.authXoauth2("me@x.com", "token123")
        }
        assertEquals(2, server2.received.count { it.startsWith("AUTH XOAUTH2") })
    }

    @Test
    fun smtpXoauth2RejectedEverywhereThrows() {
        lateinit var server: ScriptServer
        server = ScriptServer { reader, _, send ->
            send("220 fake ESMTP")
            server.readLine(reader)
            send("250-localhost")
            send("250 AUTH XOAUTH2")
            server.readLine(reader)
            send("535 nope")
            server.readLine(reader)
            send("535 still nope")
        }
        server.start()
        RawSmtpConnection(ServerConfig("127.0.0.1", server.port, false)).use { conn ->
            conn.connect()
            conn.ehlo()
            assertFailsWith<java.io.IOException> { conn.authXoauth2("me@x.com", "bad") }
        }
    }

    @Test
    fun imapStartTlsAcceptedThenPlainServerFailsUpgrade() {
        lateinit var server: ScriptServer
        server = ScriptServer { reader, _, send ->
            send("* OK fake IMAP ready")
            val line = server.readLine(reader) // A0001 STARTTLS
            val tag = line?.substringBefore(" ") ?: "A0001"
            send("$tag OK Begin TLS")
            Thread.sleep(500)
        }
        server.start()
        RawImapConnection(ServerConfig("127.0.0.1", server.port, false), trustAll = true).use { conn ->
            conn.connect()
            assertFailsWith<Exception> { conn.startTls() }
        }
        assertTrue(server.received.any { it.contains("STARTTLS") })
    }

    @Test
    fun imapStartTlsRefusedThrows() {
        lateinit var server: ScriptServer
        server = ScriptServer { reader, _, send ->
            send("* OK fake IMAP ready")
            val line = server.readLine(reader)
            val tag = line?.substringBefore(" ") ?: "A0001"
            send("$tag NO cannot start TLS")
        }
        server.start()
        RawImapConnection(ServerConfig("127.0.0.1", server.port, false)).use { conn ->
            conn.connect()
            assertFailsWith<java.io.IOException> { conn.startTls() }
        }
    }
}
