package com.vayunmathur.email.intents

import android.app.Application
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.email.data.EmailDatabase
import com.vayunmathur.email.data.EmailMessage
import com.vayunmathur.email.data.EmailRepository
import com.vayunmathur.library.intents.email.EmailSearchQuery
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Assistant intents ([GetRecentIntent] / [SearchIntent]) and the
 * [EmailMessage.toEmailData] mapper: the assistant surface must return the
 * newest inbox mail first, must not leak other folders into "recent", and
 * search must match subject/sender/body without returning non-matches.
 * The body handed to the assistant is the plain-text rendering, capped at
 * the snippet length, never raw HTML.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmailIntentsTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: EmailDatabase

    private fun message(
        uid: Long,
        folder: String = "INBOX",
        subject: String = "s$uid",
        from: String = "f@x.com",
        body: String? = "body $uid",
        dateMillis: Long = uid,
        isRead: Boolean = false,
    ) = EmailMessage(
        accountEmail = "me@x.com", folderName = folder, id = uid,
        subject = subject, from = from, to = "me@x.com", date = "d$uid",
        dateMillis = dateMillis, body = body, isRead = isRead,
    )

    @BeforeTest
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, EmailDatabase::class.java)
            .allowMainThreadQueries().build()
        resetRepositorySingleton()
        com.vayunmathur.library.util.databases[EmailDatabase::class] = db
        runBlocking {
            db.messageDao().insertMessages(
                listOf(
                    message(1L, subject = "Old report", body = "quarterly numbers", dateMillis = 100),
                    message(2L, subject = "New lunch", from = "pal@x.com", body = "pizza today", dateMillis = 300, isRead = true),
                    message(3L, folder = "Sent", subject = "Sent report", body = "quarterly sent", dateMillis = 400),
                    message(4L, subject = "Middle", body = "plain middle", dateMillis = 200),
                )
            )
        }
    }

    @AfterTest
    fun close() {
        com.vayunmathur.library.util.databases.remove(EmailDatabase::class)
        resetRepositorySingleton()
        db.close()
    }

    private fun resetRepositorySingleton() {
        val field = EmailRepository::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    @Test
    fun `toEmailData maps fields and renders plain body`() {
        val data = message(9L, subject = "Hi", from = "a@b.com", body = "hello there", isRead = true).toEmailData()
        assertEquals("Hi", data.subject)
        assertEquals("a@b.com", data.from)
        assertEquals("me@x.com", data.to)
        assertEquals("d9", data.date)
        assertEquals("hello there", data.body)
        assertTrue(data.isRead)
    }

    @Test
    fun `toEmailData caps long bodies at the snippet length`() {
        val data = message(9L, body = "x".repeat(2500)).toEmailData()
        assertEquals(2000, data.body!!.length)
    }

    @Test
    fun `toEmailData keeps a missing body missing`() {
        assertEquals(null, message(9L, body = null).toEmailData().body)
    }

    @Test
    fun `get recent returns inbox newest first and skips other folders`() {
        val activity = Robolectric.buildActivity(GetRecentIntent::class.java).create().get()
        val result = runBlocking { activity.performCalculation(Unit) }
        assertEquals(listOf("New lunch", "Middle", "Old report"), result.map { it.subject })
        assertEquals("pizza today", result.first().body)
        assertTrue(result.first().isRead)
    }

    @Test
    fun `search matches subject sender and body across folders`() {
        val activity = Robolectric.buildActivity(SearchIntent::class.java).create().get()
        val byBody = runBlocking { activity.performCalculation(EmailSearchQuery("quarterly")) }
        assertEquals(setOf("Old report", "Sent report"), byBody.map { it.subject }.toSet())
        val bySender = runBlocking { activity.performCalculation(EmailSearchQuery("pal@x.com")) }
        assertEquals(listOf("New lunch"), bySender.map { it.subject })
        val none = runBlocking { activity.performCalculation(EmailSearchQuery("zzz-no-match")) }
        assertEquals(emptyList(), none)
    }
}
