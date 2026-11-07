package com.vayunmathur.email.imap

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import android.net.Uri
import com.vayunmathur.email.platform.EmailManager
import com.vayunmathur.email.platform.ServerConfig
import com.vayunmathur.email.network.smtp.RawSmtpConnection
import com.vayunmathur.email.network.smtp.SmtpClient
import com.vayunmathur.email.ui.composer.InlineAttachment
import java.io.File
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Scripted localhost SMTP server: greeting, EHLO, AUTH, MAIL/RCPT, DATA, QUIT. */
class FakeSmtpServer(
    var failAuth: Boolean = false,
    var greeting: String = "220 fake ESMTP ready",
) : AutoCloseable {
    private val serverSocket = ServerSocket(0)
    val port: Int get() = serverSocket.localPort
    val received = mutableListOf<String>()
    val dataBodies = mutableListOf<String>()

    fun start() {
        thread(isDaemon = true) {
            try {
                while (!serverSocket.isClosed) handle(serverSocket.accept())
            } catch (_: Exception) {
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use {
            val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(it.getOutputStream(), Charsets.UTF_8))
            fun send(line: String) {
                writer.write(line); writer.write("\r\n"); writer.flush()
            }
            send(greeting)
            if (!greeting.startsWith("220")) return
            var inData = false
            val body = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val current = line!!
                if (inData) {
                    if (current == ".") {
                        inData = false
                        synchronized(dataBodies) { dataBodies.add(body.toString()) }
                        send("250 queued")
                    } else {
                        body.append(current).append("\n")
                    }
                    continue
                }
                synchronized(received) { received.add(current) }
                when {
                    current.startsWith("EHLO") -> {
                        send("250-localhost")
                        send("250 AUTH PLAIN LOGIN XOAUTH2")
                    }
                    current.startsWith("AUTH PLAIN") -> send(if (failAuth) "535 nope" else "235 authenticated")
                    current.startsWith("AUTH XOAUTH2") && current.split(" ").size > 3 ->
                        send(if (failAuth) "535 nope" else "235 authenticated")
                    current == "AUTH XOAUTH2" -> send("334 ")
                    current.startsWith("AUTH LOGIN") -> send("334 VXNlcm5hbWU6")
                    // Bare Base64 lines during AUTH LOGIN challenge/response.
                    current.matches(Regex("[A-Za-z0-9+/=]+")) && received.count { it.startsWith("AUTH LOGIN") } > 0 &&
                        received.takeLast(2).any { it.startsWith("AUTH LOGIN") || it.matches(Regex("[A-Za-z0-9+/=]+")) } -> {
                        // First bare line is the username, second is the password.
                        val bareCount = received.count { it.matches(Regex("[A-Za-z0-9+/=]+")) }
                        if (bareCount <= 1) send("334 UGFzc3dvcmQ6") else send("235 authenticated")
                    }
                    current.startsWith("MAIL FROM") -> send("250 sender ok")
                    current.startsWith("RCPT TO") -> send("250 recipient ok")
                    current == "DATA" -> {
                        inData = true
                        send("354 end with dot")
                    }
                    current == "QUIT" -> {
                        send("221 bye")
                        return
                    }
                    current.startsWith("STARTTLS") -> send("454 not here")
                    else -> send("250 ok")
                }
            }
        }
    }

    override fun close() {
        try { serverSocket.close() } catch (_: Exception) {}
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FakeSmtpServerTest {

    private lateinit var server: FakeSmtpServer

    @BeforeTest
    fun startServer() {
        server = FakeSmtpServer(); server.start()
    }

    @AfterTest
    fun stopServer() = server.close()

    private fun config() = ServerConfig("127.0.0.1", server.port, useSsl = false)
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `raw smtp ehlo auth mail rcpt data quit`() {
        RawSmtpConnection(config()).use { conn ->
            conn.connect()
            conn.ehlo("test.local")
            assertTrue(conn.hasCap("PLAIN"))
            conn.authPlain("me@x.com", "secret")
            conn.mailFrom("me@x.com")
            conn.rcptTo("you@y.com")
            conn.data("Subject: Hi\r\n\r\nBody line\r\n.dotted")
            conn.quit()
        }
        assertTrue(server.dataBodies.single().contains("Body line"))
        assertTrue(server.dataBodies.single().contains("..dotted"))
    }

    @Test
    fun `raw smtp auth login and xoauth2`() {
        RawSmtpConnection(config()).use { conn ->
            conn.connect(); conn.ehlo(); conn.authLogin("me@x.com", "secret")
        }
        RawSmtpConnection(config()).use { conn ->
            conn.connect(); conn.ehlo(); conn.authXoauth2("me@x.com", "token")
        }
    }

    @Test
    fun `smtp client sends a built message to all recipients`() = runBlocking {
        SmtpClient.sendMessage(
            context = context,
            server = config(),
            user = "me@x.com",
            auth = EmailManager.AuthType.Password("secret"),
            to = "you@y.com, Other <other@y.com>",
            subject = "Greetings",
            body = "Hello there",
            cc = "cc@y.com",
            from = "me@x.com",
        )
        val rcpts = server.received.filter { it.startsWith("RCPT TO") }
        assertEquals(3, rcpts.size)
        assertTrue(server.dataBodies.single().contains("Greetings"), server.dataBodies.single())
        // Body travels quoted-printable on the wire.
        assertTrue(server.dataBodies.single().contains("Hello=20there"), server.dataBodies.single())
    }

    @Test
    fun `smtp client sends attachments and inline images as multipart`() = runBlocking {
        val attachment = File(context.cacheDir, "report.txt").apply { writeText("attachment-body") }
        val inline = File(context.cacheDir, "logo.jpg").apply { writeText("jpegbytes") }
        SmtpClient.sendMessage(
            context = context,
            server = config(),
            user = "me@x.com",
            auth = EmailManager.AuthType.Password("secret"),
            to = "you@y.com",
            subject = "With files",
            body = "<p>see files</p>",
            attachments = listOf(Uri.fromFile(attachment)),
            inlineImages = listOf(
                InlineAttachment(
                    cid = "logo@inline",
                    uri = Uri.fromFile(inline),
                    mimeType = "image/jpeg",
                    fileName = "logo.jpg",
                )
            ),
            from = "me@x.com",
            asHtml = true,
        )
        val wire = server.dataBodies.single()
        assertTrue(wire.contains("multipart/mixed"), wire.take(600))
        assertTrue(wire.contains("report.txt"), wire.take(1200))
        // Attachment payloads travel base64 on the wire.
        assertTrue(
            wire.contains(java.util.Base64.getEncoder().encodeToString("attachment-body".toByteArray())),
            wire.take(2000),
        )
        assertTrue(wire.contains("Content-ID: <logo@inline>"), wire.take(2000))
        assertTrue(wire.contains("logo.jpg"), wire.take(2000))
    }

    @Test
    fun `smtp client oauth sends`() = runBlocking {
        SmtpClient.sendMessage(
            context = context,
            server = config(),
            user = "me@x.com",
            auth = EmailManager.AuthType.OAuth("token"),
            to = "you@y.com",
            subject = "OAuth mail",
            body = "via oauth",
        )
        assertTrue(server.received.any { it.startsWith("AUTH XOAUTH2") })
        assertTrue(server.dataBodies.single().contains("OAuth mail"))
    }
}
