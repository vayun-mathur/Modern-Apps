package com.vayunmathur.email.platform

import android.app.Application
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.email.FakeAndroidKeyStore
import com.vayunmathur.email.data.CredentialCrypto
import com.vayunmathur.email.data.EmailAccount
import com.vayunmathur.email.data.EmailDatabase
import com.vayunmathur.email.data.EmailRepository
import com.vayunmathur.email.data.OutboxEntry
import com.vayunmathur.email.imap.FakeSmtpServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [EmailSendActions]: a successful send must reach the SMTP wire and
 * stay out of the outbox; a failed send must be queued in the outbox
 * with the failure recorded (never silently dropped); scheduling must
 * persist the future timestamp without sending; deleting must remove
 * the queued row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmailSendActionsTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: EmailDatabase
    private lateinit var smtp: FakeSmtpServer
    private lateinit var actions: EmailSendActions
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun account(smtpPort: Int): EmailAccount {
        val (cipher, iv) = CredentialCrypto.encrypt("secret")
        return EmailAccount(
            email = "me@x.com", provider = "custom", authType = "password",
            passwordEncrypted = cipher, passwordIv = iv,
            smtpHost = "127.0.0.1", smtpPort = smtpPort, smtpUseSsl = false,
            imapHost = "127.0.0.1", imapPort = 993, imapUseSsl = false,
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
        smtp = FakeSmtpServer(); smtp.start()
        db = Room.inMemoryDatabaseBuilder(context, EmailDatabase::class.java)
            .allowMainThreadQueries().build()
        resetRepositorySingleton()
        com.vayunmathur.library.util.databases[EmailDatabase::class] = db
        actions = EmailSendActions(scope, context, EmailManager())
    }

    @AfterTest
    fun close() {
        com.vayunmathur.library.util.databases.remove(EmailDatabase::class)
        resetRepositorySingleton()
        db.close()
        smtp.close()
    }

    private fun resetRepositorySingleton() {
        val field = EmailRepository::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    private suspend fun awaitOutbox(check: (List<OutboxEntry>) -> Boolean): List<OutboxEntry> {
        repeat(3000) {
            val rows = db.outboxDao().getOutbox()
            if (check(rows)) return rows
            delay(10)
        }
        return db.outboxDao().getOutbox()
    }

    /** Wait until an async callback flag flips (callbacks fire after the work, so flag-first avoids racing the row state). */
    private suspend fun awaitFlag(flag: () -> Boolean) {
        repeat(3000) {
            if (flag()) return
            delay(10)
        }
    }

    @Test
    fun `successful send reaches the wire and skips the outbox`() = runBlocking {
        var succeeded = false
        var error: String? = null
        actions.sendFrom(
            account = account(smtp.port), to = "you@y.com", subject = "Direct send",
            body = "directbody",
            onSuccess = { succeeded = true }, onError = { error = it },
        )
        awaitFlag { succeeded || error != null }
        assertTrue(succeeded, "error was: $error")
        assertEquals(null, error)
        // The wire body is quoted-printable, so assert on a space-free token.
        assertTrue(smtp.dataBodies.single().contains("Direct send"))
        assertTrue(smtp.dataBodies.single().contains("directbody"))
        assertEquals(emptyList(), db.outboxDao().getOutbox())
    }

    @Test
    fun `failed send is queued in the outbox with the error recorded`() = runBlocking {
        var succeeded = false
        var error: String? = null
        actions.sendFrom(
            account = account(smtpPort = 1), to = "you@y.com", subject = "Will fail",
            body = "queued body", cc = "cc@y.com",
            onSuccess = { succeeded = true }, onError = { error = it },
        )
        awaitFlag { succeeded || error != null }
        val rows = awaitOutbox { it.size == 1 }
        assertEquals(false, succeeded)
        assertTrue(!error.isNullOrBlank(), "expected an error message")
        val row = rows.single()
        assertEquals("Will fail", row.subject)
        assertEquals("queued body", row.body)
        assertEquals("cc@y.com", row.cc)
        assertTrue(!row.lastError.isNullOrBlank(), "outbox row must record the failure")
    }

    @Test
    fun `schedule persists the future send without touching the wire`() = runBlocking {
        val at = System.currentTimeMillis() + 3_600_000
        var done = false
        actions.schedule(
            account = account(smtp.port), to = "later@y.com", subject = "Later",
            body = "later body", scheduledAt = at,
            onDone = { done = true },
        )
        awaitFlag { done }
        val rows = awaitOutbox { it.size == 1 }
        assertTrue(done)
        assertEquals(at, rows.single().scheduledAt)
        assertEquals("Later", rows.single().subject)
        assertEquals(emptyList<String>(), smtp.dataBodies)
    }

    @Test
    fun `delete outbox entry removes the queued row`() = runBlocking {
        var done = false
        actions.schedule(
            account = account(smtp.port), to = "x@y.com", subject = "Doomed",
            body = "b", scheduledAt = System.currentTimeMillis() + 3_600_000,
            onDone = { done = true },
        )
        awaitFlag { done }
        val rows = awaitOutbox { it.size == 1 }
        assertTrue(done)
        actions.deleteOutboxEntry(rows.single())
        val after = awaitOutbox { it.isEmpty() }
        assertEquals(emptyList(), after)
    }
}
