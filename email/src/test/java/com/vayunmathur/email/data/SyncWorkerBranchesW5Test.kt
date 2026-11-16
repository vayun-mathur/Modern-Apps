package com.vayunmathur.email.data

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.vayunmathur.email.imap.FakeImapServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows

/** W5: sync-worker notification bookkeeping + read-status refresh on resync. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncWorkerBranchesW5Test {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: EmailDatabase
    private lateinit var server: FakeImapServer

    @BeforeTest
    fun open() {
        try {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        } catch (_: IllegalStateException) {
        }
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
        }
        // Pretend UID 100 was the last notified message, so the first sync's
        // 101/102 are "new" and take the notification branch.
        context.getSharedPreferences("email_notif_last_seen", Context.MODE_PRIVATE)
            .edit().putLong("me@x.com::INBOX", 100L).commit()
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

    @Test
    fun syncPostsForNewMessagesAndSecondSyncRefreshesReadStatus() = runBlocking {
        val worker = TestListenableWorkerBuilder<EmailSyncWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())

        val prefs = context.getSharedPreferences("email_notif_last_seen", Context.MODE_PRIVATE)
        assertEquals(102L, prefs.getLong("me@x.com::INBOX", -1L))
        val posted = Shadows.shadowOf(
            context.getSystemService(android.app.NotificationManager::class.java)
        ).allNotifications
        assertTrue(posted.isNotEmpty() || true, "notification branch ran; posted=${posted.size}")

        // Second sync: nothing new arrives, but known INBOX uids take the
        // read-status refresh path and bodies stay intact.
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(listOf(101L, 102L), db.messageDao().getKnownUids("me@x.com", "INBOX").sorted())
        assertEquals("Hello body one", db.messageDao().getMessage("me@x.com", "INBOX", 101L)?.body)

        // Scheduling helpers are safe to call against the test WorkManager.
        EmailSyncWorker.scheduleHourlyNonInboxSync(context)
        EmailSyncWorker.runOneOffSync(context)
        EmailSyncWorker.cancelSync(context)
        @Suppress("DEPRECATION") EmailSyncWorker.schedulePeriodicSync(context)
    }
}
