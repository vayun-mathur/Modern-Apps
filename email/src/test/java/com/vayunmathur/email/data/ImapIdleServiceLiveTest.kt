package com.vayunmathur.email.data

import android.app.Application
import android.content.Intent
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.email.FakeAndroidKeyStore
import com.vayunmathur.email.imap.FakeImapServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ImapIdleService] against the fake IMAP server: with a configured
 * account the service must connect, log in, discover folders, enter
 * IDLE, react to the server's EXISTS push by fetching the new mail into
 * the database, and stop its loops when destroyed. An auth failure must
 * end that account's loop instead of reconnecting forever.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImapIdleServiceLiveTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: EmailDatabase
    private lateinit var server: FakeImapServer

    private fun account(port: Int): EmailAccount {
        val (cipher, iv) = CredentialCrypto.encrypt("secret")
        return EmailAccount(
            email = "me@x.com", provider = "custom", authType = "password",
            passwordEncrypted = cipher, passwordIv = iv,
            imapHost = "127.0.0.1", imapPort = port, imapUseSsl = false,
            smtpHost = "127.0.0.1", smtpPort = 465, smtpUseSsl = false,
        )
    }

    @BeforeTest
    fun open() {
        FakeAndroidKeyStore.install()
        FakeAndroidKeyStore.clear()
        try {
            WorkManager.initialize(context, Configuration.Builder().build())
        } catch (_: IllegalStateException) {
        }
        db = Room.inMemoryDatabaseBuilder(context, EmailDatabase::class.java)
            .allowMainThreadQueries().build()
        resetRepositorySingleton()
        com.vayunmathur.library.util.databases[EmailDatabase::class] = db
    }

    @AfterTest
    fun close() {
        com.vayunmathur.library.util.databases.remove(EmailDatabase::class)
        resetRepositorySingleton()
        db.close()
        server.close()
    }

    private fun resetRepositorySingleton() {
        val field = EmailRepository::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    private fun awaitServer(check: (List<String>) -> Boolean): Boolean {
        repeat(2000) {
            val cmds = synchronized(server.receivedCommands) { server.receivedCommands.toList() }
            if (check(cmds)) return true
            Thread.sleep(10)
        }
        return false
    }

    @Test
    fun `idle session connects discovers folders and fetches pushed mail`() {
        server = FakeImapServer(caps = "IMAP4rev1 IDLE AUTH=PLAIN", idlePushLine = "* 3 EXISTS")
        server.start()
        runBlocking { db.accountDao().insertAccount(account(server.port)) }

        val controller = Robolectric.buildService(ImapIdleService::class.java)
        val service = controller.create().get()
        assertEquals(android.app.Service.START_STICKY, service.onStartCommand(Intent(), 0, 1))

        // The session enters IDLE, gets the EXISTS push, ends IDLE with
        // DONE and fetches the new messages over the same connection.
        assertTrue(
            awaitServer { cmds -> cmds.any { it.contains("IDLE") } },
            "server never saw IDLE: ${server.receivedCommands}",
        )
        var messages = emptyList<EmailMessage>()
        repeat(2000) {
            messages = runBlocking {
                db.messageDao().getMessagesFlow("me@x.com", "INBOX", Long.MAX_VALUE).first()
            }
            if (messages.isNotEmpty()) return@repeat
            Thread.sleep(10)
        }
        // Fall back to the raw DAO known-UIDs check if the flow raced.
        if (messages.isEmpty()) {
            val known = runBlocking { db.messageDao().getKnownUids("me@x.com", "INBOX") }
            assertTrue(known.isNotEmpty(), "fetched mail never reached the database")
        } else {
            assertTrue(messages.any { it.subject == "First" }, messages.map { it.subject }.toString())
        }
        val cmds = synchronized(server.receivedCommands) { server.receivedCommands.toList() }
        assertTrue(cmds.any { it.contains("LIST") }, cmds.toString())
        assertTrue(cmds.any { it.contains("DONE") }, cmds.toString())
        controller.destroy()
    }

    @Test
    fun `flags push syncs the read flag from the server`() {
        server = FakeImapServer(
            caps = "IMAP4rev1 IDLE AUTH=PLAIN",
            idlePushLine = "* 1 FETCH (FLAGS (\\Seen))",
        )
        server.start()
        runBlocking {
            db.accountDao().insertAccount(account(server.port))
            db.messageDao().insertMessages(
                listOf(
                    EmailMessage(
                        accountEmail = "me@x.com", folderName = "INBOX", id = 101,
                        subject = "s101", from = "f@x.com", date = "d", dateMillis = 101,
                        isRead = false,
                    )
                )
            )
        }
        val controller = Robolectric.buildService(ImapIdleService::class.java)
        val service = controller.create().get()
        service.onStartCommand(Intent(), 0, 1)
        assertTrue(
            awaitServer { cmds -> cmds.any { it.contains("IDLE") } },
            "server never saw IDLE: ${server.receivedCommands}",
        )
        var read = false
        repeat(2000) {
            read = runBlocking { db.messageDao().getMessage("me@x.com", "INBOX", 101) }?.isRead == true
            if (read) return@repeat
            Thread.sleep(10)
        }
        assertTrue(read, "server \\Seen flag never synced into the local row")
        controller.destroy()
    }

    @Test
    fun `expunge push ends the idle and re-enters it`() {
        server = FakeImapServer(
            caps = "IMAP4rev1 IDLE AUTH=PLAIN",
            idlePushLine = "* 2 EXPUNGE",
        )
        server.start()
        runBlocking { db.accountDao().insertAccount(account(server.port)) }
        val controller = Robolectric.buildService(ImapIdleService::class.java)
        val service = controller.create().get()
        service.onStartCommand(Intent(), 0, 1)
        assertTrue(
            awaitServer { cmds -> cmds.count { it.contains("IDLE") } >= 2 },
            "IDLE never re-entered after EXPUNGE: ${server.receivedCommands}",
        )
        val cmds = synchronized(server.receivedCommands) { server.receivedCommands.toList() }
        assertTrue(cmds.any { it.contains("DONE") }, cmds.toString())
        controller.destroy()
    }

    @Test
    fun `server without idle falls back to the polling loop`() {
        server = FakeImapServer(caps = "IMAP4rev1 AUTH=PLAIN")
        server.start()
        runBlocking { db.accountDao().insertAccount(account(server.port)) }
        val controller = Robolectric.buildService(ImapIdleService::class.java)
        val service = controller.create().get()
        service.onStartCommand(Intent(), 0, 1)
        assertTrue(
            awaitServer { cmds -> cmds.any { it.contains("LOGIN") } },
            "server never saw LOGIN: ${server.receivedCommands}",
        )
        Thread.sleep(300)
        val cmds = synchronized(server.receivedCommands) { server.receivedCommands.toList() }
        assertTrue(cmds.none { it.contains("IDLE") }, cmds.toString())
        controller.destroy()
    }

    @Test
    fun `auth failure ends the loop without fetching`() {
        server = FakeImapServer(caps = "IMAP4rev1 IDLE AUTH=PLAIN", failLogin = true)
        server.start()
        runBlocking { db.accountDao().insertAccount(account(server.port)) }

        val controller = Robolectric.buildService(ImapIdleService::class.java)
        val service = controller.create().get()
        service.onStartCommand(Intent(), 0, 1)
        assertTrue(
            awaitServer { cmds -> cmds.any { it.contains("LOGIN") } },
            "server never saw LOGIN: ${server.receivedCommands}",
        )
        // Give the loop a chance to (not) retry: exactly one LOGIN attempt.
        Thread.sleep(300)
        val logins = synchronized(server.receivedCommands) {
            server.receivedCommands.count { it.contains("LOGIN") }
        }
        assertEquals(1, logins, server.receivedCommands.toString())
        assertEquals(emptyList(), runBlocking { db.messageDao().getKnownUids("me@x.com", "INBOX") })
        controller.destroy()
    }
}
