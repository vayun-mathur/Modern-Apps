package com.vayunmathur.email.imap

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.email.platform.EmailManager
import com.vayunmathur.email.platform.ServerConfig
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [EmailManager] facade over the fake IMAP/SMTP servers: every delegate must reach the right client. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmailManagerFakeServerTest {

    private lateinit var imap: FakeImapServer
    private lateinit var smtp: FakeSmtpServer
    private val manager = EmailManager()
    private val auth = EmailManager.AuthType.Password("secret")

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest
    fun startServers() {
        imap = FakeImapServer(); imap.start()
        smtp = FakeSmtpServer(); smtp.start()
    }

    @AfterTest
    fun stopServers() {
        imap.close(); smtp.close()
    }

    private fun imapConfig() = ServerConfig("127.0.0.1", imap.port, useSsl = false)
    private fun smtpConfig() = ServerConfig("127.0.0.1", smtp.port, useSsl = false)

    @Test
    fun `manager fetches folders messages body and raw bytes`() = runBlocking {
        val folders = manager.fetchFolders(imapConfig(), "me@x.com", auth)
        assertEquals(listOf("INBOX", "Sent", "Archive"), folders.map { it.fullName })

        val (messages, _) = manager.fetchMessages(imapConfig(), "me@x.com", auth, "INBOX", limit = 10)
        assertEquals(listOf("Second", "First"), messages.map { it.subject })

        val (body, _, _) = manager.fetchMessageBody(imapConfig(), "me@x.com", auth, "INBOX", 101L)
        assertEquals("Hello body one", body)

        val full = manager.fetchFullForBody(context, imapConfig(), "me@x.com", auth, "INBOX", 101L)
        assertEquals("Hello body one", full.contentTriple.first)

        assertTrue(manager.fetchCidMap(context, imapConfig(), "me@x.com", auth, "INBOX", 101L).isEmpty())
        assertTrue(String(manager.fetchRawMessageBytes(imapConfig(), "me@x.com", auth, "INBOX", 101L), Charsets.UTF_8).contains("First"))

        val out = java.io.ByteArrayOutputStream()
        manager.fetchRawMessageTo(imapConfig(), "me@x.com", auth, "INBOX", 101L, out)
        assertTrue(String(out.toByteArray(), Charsets.UTF_8).contains("Hello body one"))
    }

    @Test
    fun `manager seen delete and send delegate to the wire`() = runBlocking {
        manager.setSeenFlag(imapConfig(), "me@x.com", auth, "INBOX", 101L, seen = false)
        manager.deleteMessage(imapConfig(), "me@x.com", auth, "INBOX", 101L)
        assertTrue(imap.receivedCommands.any { it.contains("EXPUNGE") })

        manager.sendMessage(
            context = context,
            server = smtpConfig(),
            user = "me@x.com",
            auth = auth,
            to = "you@y.com",
            subject = "Manager mail",
            body = "sent by manager",
            from = "me@x.com",
        )
        assertTrue(smtp.dataBodies.single().contains("Manager mail"))
    }
}
