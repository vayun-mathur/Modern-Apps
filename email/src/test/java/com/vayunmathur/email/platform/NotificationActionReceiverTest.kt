package com.vayunmathur.email.platform

import android.app.Application
import android.content.Intent
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.email.data.EmailAccount
import com.vayunmathur.email.data.EmailDatabase
import com.vayunmathur.email.data.EmailMessage
import com.vayunmathur.email.data.EmailRepository
import com.vayunmathur.email.imap.FakeImapServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [EmailNotificationActionReceiver]: mark-read and delete must change the
 * local row (and dismiss the notification) even though the server sync is
 * best-effort. Missing extras must be a no-op.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationActionReceiverTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: EmailDatabase
    private lateinit var server: FakeImapServer

    @BeforeTest
    fun open() {
        server = FakeImapServer(); server.start()
        db = Room.inMemoryDatabaseBuilder(context, EmailDatabase::class.java)
            .allowMainThreadQueries().build()
        resetRepositorySingleton()
        com.vayunmathur.library.util.databases[EmailDatabase::class] = db
        runBlocking {
            db.accountDao().insertAccount(
                EmailAccount(
                    email = "me@x.com", provider = "outlook", authType = "oauth2",
                    accessToken = "live-token", expiresAt = System.currentTimeMillis() + 3_600_000,
                    imapHost = "127.0.0.1", imapPort = server.port, imapUseSsl = false,
                ),
            )
            db.messageDao().insertMessages(
                listOf(
                    EmailMessage(
                        accountEmail = "me@x.com", folderName = "INBOX", id = 101L,
                        subject = "s", from = "f@x.com", date = "d", isRead = false,
                    ),
                ),
            )
        }
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

    private fun awaitRow(check: suspend () -> Boolean) {
        repeat(200) {
            if (runBlocking { check() }) return
            Thread.sleep(25)
        }
    }

    @Test
    fun `mark read action flips the local row`() {
        EmailNotificationActionReceiver().onReceive(
            context,
            EmailNotificationActionReceiver.intent(
                context, EmailNotificationActionReceiver.ACTION_MARK_READ, "me@x.com", "INBOX", 101L, 7,
            ),
        )
        awaitRow { db.messageDao().getMessage("me@x.com", "INBOX", 101L)?.isRead == true }
        assertEquals(true, runBlocking { db.messageDao().getMessage("me@x.com", "INBOX", 101L) }?.isRead)
    }

    @Test
    fun `delete action removes the local row`() {
        EmailNotificationActionReceiver().onReceive(
            context,
            EmailNotificationActionReceiver.intent(
                context, EmailNotificationActionReceiver.ACTION_DELETE, "me@x.com", "INBOX", 101L, 7,
            ),
        )
        awaitRow { db.messageDao().getMessage("me@x.com", "INBOX", 101L) == null }
        assertNull(runBlocking { db.messageDao().getMessage("me@x.com", "INBOX", 101L) })
    }

    @Test
    fun `missing extras are a no-op`() {
        EmailNotificationActionReceiver().onReceive(context, Intent())
        EmailNotificationActionReceiver().onReceive(
            context,
            Intent().putExtra(EmailNotificationActionReceiver.EXTRA_ACTION, EmailNotificationActionReceiver.ACTION_MARK_READ),
        )
        assertEquals(false, runBlocking { db.messageDao().getMessage("me@x.com", "INBOX", 101L) }?.isRead)
    }
}
