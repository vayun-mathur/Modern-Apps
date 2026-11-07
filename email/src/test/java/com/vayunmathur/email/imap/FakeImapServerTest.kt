package com.vayunmathur.email.imap

import com.vayunmathur.email.platform.EmailManager
import com.vayunmathur.email.platform.ServerConfig
import com.vayunmathur.email.network.imap.ImapAuthException
import com.vayunmathur.email.network.imap.ImapClient
import com.vayunmathur.email.network.imap.RawImapConnection
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Scripted localhost IMAP server speaking just enough RFC 3501 for the raw
 * client: greeting, CAPABILITY, LOGIN/PLAIN/LOGIN/XOAUTH2 auth, LIST,
 * SELECT/EXAMINE, FETCH (headers/full/part), STORE, EXPUNGE and IDLE.
 * Every assertion is on client-visible results parsed from the wire.
 */
class FakeImapServer(
    var greeting: String = "* OK Fake IMAP ready",
    var failLogin: Boolean = false,
    var caps: String = "IMAP4rev1 AUTH=PLAIN",
    /** Unsolicited line pushed right after the IDLE continuation, e.g. "* 3 EXISTS". */
    var idlePushLine: String? = null,
) : AutoCloseable {
    private val serverSocket = ServerSocket(0)
    val port: Int get() = serverSocket.localPort
    val receivedCommands = mutableListOf<String>()

    private val header1 = "From: Alice <alice@x.com>\r\nTo: me@x.com\r\nSubject: First\r\n" +
        "Date: Thu, 21 Oct 2026 18:24:12 +0000\r\nMessage-ID: <m1@x>\r\n\r\n"
    private val header2 = "From: Bob <bob@x.com>\r\nTo: me@x.com\r\nSubject: Second\r\n" +
        "Date: Fri, 22 Oct 2026 18:24:12 +0000\r\nMessage-ID: <m2@x>\r\n\r\n"
    private val full1 = header1 + "Hello body one"
    private val full2 = header2 + "Hello body two"

    private var thread: Thread? = null

    fun start() {
        thread = thread(isDaemon = true) {
            try {
                while (!serverSocket.isClosed) {
                    val socket = serverSocket.accept()
                    // One thread per connection: IDLE keeps a connection
                    // open while the code under test opens another.
                    thread(isDaemon = true) {
                        try {
                            handle(socket)
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (out.size() == 0) null else out.toString(Charsets.US_ASCII.name())
            out.write(b)
            if (b == '\n'.code) return out.toString(Charsets.US_ASCII.name()).trimEnd('\r', '\n')
        }
    }

    private fun handle(socket: Socket) {
        socket.use {
            val input = BufferedInputStream(it.getInputStream())
            val output = BufferedOutputStream(it.getOutputStream())
            fun send(text: String) {
                output.write(text.toByteArray(Charsets.US_ASCII)); output.flush()
            }
            send("$greeting\r\n")
            if (greeting.startsWith("* BYE")) return
            var done = false
            while (!done) {
                val line = readLine(input) ?: return
                synchronized(receivedCommands) { receivedCommands.add(line) }
                if (line == "DONE") {
                    send("${lastIdleTag} OK IDLE terminated\r\n")
                    continue
                }
                val parts = line.split(" ", limit = 3)
                if (parts.size < 2) continue
                val tag = parts[0]
                when (parts[1].uppercase()) {
                    "CAPABILITY" -> send("* CAPABILITY $caps\r\n$tag OK CAPABILITY done\r\n")
                    "LOGIN" -> send(if (failLogin) "$tag NO LOGIN failed\r\n" else "$tag OK LOGIN done\r\n")
                    "AUTHENTICATE" -> handleAuth(line, tag, input, ::send)
                    "LIST" -> send(
                        "* LIST (\\HasNoChildren) \"/\" INBOX\r\n" +
                            "* LIST (\\HasNoChildren) \"/\" Sent\r\n" +
                            "* LIST (\\Noselect) \"/\" Archive\r\n" +
                            "$tag OK LIST done\r\n",
                    )
                    "SELECT", "EXAMINE" -> send(
                        "* 2 EXISTS\r\n* 1 RECENT\r\n* OK [UIDVALIDITY 12345] UIDs valid\r\n" +
                            "* OK [UIDNEXT 103] next\r\n* FLAGS (\\Seen \\Deleted)\r\n$tag OK [READ-WRITE] done\r\n",
                    )
                    "FETCH" -> handleFetch(line, tag, ::send, full = line.contains("BODY.PEEK[]") || line.contains(" BODY[]"))
                    "UID" -> handleUid(line, tag, input, ::send)
                    "EXPUNGE" -> send("$tag OK EXPUNGE done\r\n")
                    "IDLE" -> {
                        lastIdleTag = tag
                        send("+ idling\r\n")
                        idlePushLine?.let { send("$it\r\n") }
                    }
                    "LOGOUT" -> {
                        send("* BYE bye\r\n$tag OK LOGOUT done\r\n"); done = true
                    }
                    else -> send("$tag OK done\r\n")
                }
            }
        }
    }

    private var lastIdleTag: String = "A0000"

    private fun handleAuth(line: String, tag: String, input: BufferedInputStream, send: (String) -> Unit) {
        // Bad credentials fail every mechanism, not just LOGIN.
        if (failLogin) {
            send("$tag NO AUTHENTICATE failed\r\n")
            return
        }
        when {
            line.contains("PLAIN", ignoreCase = true) && line.split(" ").size > 3 ->
                send("$tag OK PLAIN done\r\n")
            line.contains("XOAUTH2", ignoreCase = true) && line.split(" ").size > 3 ->
                send("$tag OK XOAUTH2 done\r\n")
            line.contains("LOGIN", ignoreCase = true) -> {
                send("+ VXNlcm5hbWU6\r\n")
                readLine(input)
                send("+ UGFzc3dvcmQ6\r\n")
                readLine(input)
                send("$tag OK LOGIN auth done\r\n")
            }
            line.contains("XOAUTH2", ignoreCase = true) -> {
                send("+ \r\n")
                readLine(input)
                send("$tag OK XOAUTH2 done\r\n")
            }
            else -> send("$tag OK auth done\r\n")
        }
    }

    private fun handleUid(line: String, tag: String, input: BufferedInputStream, send: (String) -> Unit) {
        when {
            line.contains("FETCH") -> handleFetch(line, tag, send, full = line.contains("BODY.PEEK[]"))
            line.contains("STORE") -> send("$tag OK STORE done\r\n")
            line.contains("EXPUNGE") -> send("$tag OK EXPUNGE done\r\n")
            else -> send("$tag OK done\r\n")
        }
    }

    private fun handleFetch(line: String, tag: String, send: (String) -> Unit, full: Boolean) {
        when {
            line.contains("HEADER.FIELDS") -> {
                fetchEntry(send, 1, 101, header1, header = true)
                fetchEntry(send, 2, 102, header2, header = true)
            }
            line.contains("BODY.PEEK[") && !line.contains("BODY.PEEK[]") -> {
                val bytes = "PARTBYTES".toByteArray(Charsets.US_ASCII)
                send("* 1 FETCH (UID 101 BODY[2] {${bytes.size}}\r\n")
                sendBytes(send, bytes)
                send(")\r\n")
            }
            else -> {
                fetchEntry(send, 1, 101, full1, header = false)
            }
        }
        send("$tag OK FETCH done\r\n")
    }

    private fun sendBytes(send: (String) -> Unit, bytes: ByteArray) {
        // The fake's send is text-based; bytes here are ASCII-safe.
        send(String(bytes, Charsets.US_ASCII))
    }

    private fun fetchEntry(send: (String) -> Unit, seq: Int, uid: Long, payload: String, header: Boolean) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val kind = if (header) "BODY[HEADER.FIELDS (From To)]" else "BODY[]"
        send("* $seq FETCH (UID $uid FLAGS (\\Seen) INTERNALDATE \"21-Oct-2026 18:24:12 +0000\" $kind {${bytes.size}}\r\n")
        send(payload)
        send(")\r\n")
    }

    override fun close() {
        try { serverSocket.close() } catch (_: Exception) {}
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FakeImapServerTest {

    private lateinit var server: FakeImapServer

    @BeforeTest
    fun startServer() {
        server = FakeImapServer()
        server.start()
    }

    @AfterTest
    fun stopServer() = server.close()

    private fun config() = ServerConfig("127.0.0.1", server.port, useSsl = false)
    private val auth = EmailManager.AuthType.Password("secret")

    @Test
    fun `raw connection capability login list select and examine`() {
        RawImapConnection(config()).use { conn ->
            conn.connect()
            assertTrue(conn.capability().has("IMAP4rev1"))
            assertTrue(conn.login("me@x.com", "secret").contains("OK"))
            val entries = conn.list("", "*")
            assertEquals(listOf("INBOX", "Sent", "Archive"), entries.map { it.mailbox })
            val selected = conn.select("INBOX")
            assertEquals(2, selected.exists)
            assertEquals(12345L, selected.uidValidity)
            assertEquals(103L, selected.uidNext)
            assertEquals(2, conn.examine("INBOX").exists)
        }
    }

    @Test
    fun `raw auth plain login and xoauth2 succeed`() {
        RawImapConnection(config()).use { conn ->
            conn.connect()
            assertTrue(conn.authenticatePlain("me@x.com", "secret").contains("OK"))
        }
        RawImapConnection(config()).use { conn ->
            conn.connect()
            assertTrue(conn.authenticateLogin("me@x.com", "secret").contains("OK"))
        }
        RawImapConnection(config()).use { conn ->
            conn.connect()
            assertTrue(conn.authenticateXoauth2("me@x.com", "token").contains("OK"))
        }
    }

    @Test
    fun `fetch headers full body part store expunge and idle over the wire`() {
        RawImapConnection(config()).use { conn ->
            conn.connect()
            conn.login("me@x.com", "secret")
            conn.select("INBOX")
            val headers = conn.fetch.fetchHeadersForSeq("1:2")
            assertEquals(listOf(101L, 102L), headers.map { it.uid })
            assertTrue(headers.all { it.headerBytes != null })
            val full = conn.fetch.uidFetchFullSet("101")
            assertEquals("Hello body one", String(full.single().bodyBytes!!, Charsets.UTF_8).substringAfter("\r\n\r\n"))
            assertEquals("PARTBYTES", String(conn.fetch.uidFetchPartBytes(101L, "2")!!, Charsets.US_ASCII))
            conn.fetch.uidStoreFlags(101L, "\\Seen", add = true)
            conn.fetch.uidStoreFlagsSet("101,102", "\\Seen", add = false)
            conn.fetch.expunge()
            assertTrue(conn.fetch.uidExpunge(101L).contains("OK"))
            val idleTag = conn.sendIdle()
            conn.sendIdleDone()
            assertTrue(conn.readIdleResponseForTag(idleTag).contains("OK"))
        }
    }

    @Test
    fun `bye greeting is rejected`() {
        val bye = FakeImapServer(greeting = "* BYE go away")
        bye.start()
        try {
            assertFailsWith<IOException> {
                RawImapConnection(ServerConfig("127.0.0.1", bye.port, useSsl = false)).use { it.connect() }
            }
        } finally {
            bye.close()
        }
    }

    @Test
    fun `login failure raises auth exception`() {
        val failing = FakeImapServer(failLogin = true)
        failing.start()
        try {
            assertFailsWith<ImapAuthException> {
                RawImapConnection(ServerConfig("127.0.0.1", failing.port, useSsl = false)).use { conn ->
                    conn.connect()
                    conn.login("me@x.com", "wrong")
                }
            }
        } finally {
            failing.close()
        }
    }

    @Test
    fun `client fetch folders canonicalizes inbox and marks noselect`() = runBlocking {
        val folders = ImapClient.fetchFolders(config(), "me@x.com", auth)
        assertEquals(listOf("INBOX", "Sent", "Archive"), folders.map { it.fullName })
        assertEquals(listOf(true, true, false), folders.map { it.holdsMessages })
    }

    @Test
    fun `client fetch messages parses headers into messages`() = runBlocking {
        val (messages, attachments) = ImapClient.fetchMessages(config(), "me@x.com", auth, "INBOX", limit = 10)
        assertEquals(listOf("Second", "First"), messages.map { it.subject })
        assertEquals(listOf(102L, 101L), messages.map { it.id })
        assertTrue(messages.all { it.isRead })
        assertTrue(attachments.isEmpty())
        assertTrue(messages.first().dateMillis > 0)
    }

    @Test
    fun `client body raw seen delete and cid paths`() = runBlocking {
        val (body, isHtml, _) = ImapClient.fetchMessageBody(config(), "me@x.com", auth, "INBOX", 101L)
        assertEquals("Hello body one", body)
        assertEquals(false, isHtml)
        assertEquals("Hello body one", String(ImapClient.fetchRawMessageBytes(config(), "me@x.com", auth, "INBOX", 101L), Charsets.UTF_8).substringAfter("\r\n\r\n"))
        val out = java.io.ByteArrayOutputStream()
        ImapClient.fetchRawMessageTo(config(), "me@x.com", auth, "INBOX", 101L, out)
        assertTrue(String(out.toByteArray(), Charsets.UTF_8).contains("First"))
        ImapClient.setSeenFlag(config(), "me@x.com", auth, "INBOX", 101L, seen = true)
        ImapClient.deleteMessage(config(), "me@x.com", auth, "INBOX", 101L)
        assertTrue(server.receivedCommands.any { it.contains("STORE") })
        assertTrue(server.receivedCommands.any { it.contains("EXPUNGE") })
    }

    @Test
    fun `client oauth connection authenticates with xoauth2`() = runBlocking {
        val folders = ImapClient.fetchFolders(config(), "me@x.com", EmailManager.AuthType.OAuth("token"))
        assertEquals(3, folders.size)
        assertTrue(server.receivedCommands.any { it.contains("XOAUTH2") })
    }
}
