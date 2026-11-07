package com.vayunmathur.email.data

import android.app.Application
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives the real [DateMillisBackfill] and [PeekContentBackfill] against an
 * in-memory database: both are one-shot migrations that must terminate (an
 * unparseable row re-queued forever would spin on every app start) and must
 * land the right values, since sorting and list snippets read these columns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackfillTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: EmailDatabase
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun msg(uid: Long, date: String, body: String?, dateMillis: Long = 0, peek: String = "") =
        EmailMessage(
            accountEmail = "me@x.com", folderName = "INBOX", id = uid,
            subject = "s", from = "f", date = date,
            dateMillis = dateMillis, body = body, peekContent = peek,
        )

    @BeforeTest
    fun open() {
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
    }

    private fun resetRepositorySingleton() {
        val field = EmailRepository::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    private fun waitFor(message: String, timeoutMs: Long = 10_000, cond: suspend () -> Boolean) {
        val start = System.currentTimeMillis()
        runBlocking {
            while (!cond()) {
                if (System.currentTimeMillis() - start > timeoutMs) {
                    throw AssertionError(message)
                }
                Thread.sleep(25)
            }
        }
    }

    @Test
    fun `date backfill parses java Date strings into millis`() {
        runBlocking {
            db.messageDao().insertMessagesRaw(
                listOf(msg(1L, "Wed Nov 27 14:30:00 PST 2024", "b"))
            )
        }
        DateMillisBackfill.runIfNeeded(scope, context)
        waitFor("dateMillis was not backfilled") {
            db.messageDao().getMessage("me@x.com", "INBOX", 1L)?.dateMillis.let { (it ?: 0L) > 0L }
        }
        val row = runBlocking { db.messageDao().getMessage("me@x.com", "INBOX", 1L) }
        // Nov 27 2024 14:30 PST = 22:30 UTC ≈ epoch 1732746600.
        assertTrue(row!!.dateMillis > 1_700_000_000_000L, "millis: ${row.dateMillis}")
    }

    @Test
    fun `date backfill marks unparseable rows done without looping`() {
        runBlocking {
            db.messageDao().insertMessagesRaw(
                listOf(msg(2L, "not a date at all", "b"))
            )
        }
        DateMillisBackfill.runIfNeeded(scope, context)
        waitFor("unparseable row was not marked done") {
            db.messageDao().getMessage("me@x.com", "INBOX", 2L)?.dateMillis.let { (it ?: 0L) != 0L }
        }
        val row = runBlocking { db.messageDao().getMessage("me@x.com", "INBOX", 2L) }
        assertEquals(1L, row!!.dateMillis)
        assertTrue(runBlocking { db.queryDao().getRowsWithZeroDateMillis() }.isEmpty())
    }

    @Test
    fun `peek backfill derives the snippet from the body`() {
        runBlocking {
            db.messageDao().insertMessagesRaw(
                listOf(msg(3L, "Wed Nov 27 14:30:00 PST 2024", "hello   world"))
            )
        }
        PeekContentBackfill.runIfNeeded(scope, context)
        waitFor("peek was not backfilled") {
            db.messageDao().getMessage("me@x.com", "INBOX", 3L)?.peekContent?.isNotEmpty() == true
        }
        val row = runBlocking { db.messageDao().getMessage("me@x.com", "INBOX", 3L) }
        assertEquals("hello world", row!!.peekContent)
    }

    @Test
    fun `peek backfill blanks become a single space so rows finish`() {
        runBlocking {
            db.messageDao().insertMessagesRaw(
                listOf(msg(4L, "Wed Nov 27 14:30:00 PST 2024", "   "))
            )
        }
        PeekContentBackfill.runIfNeeded(scope, context)
        waitFor("blank peek row was not marked done") {
            val peek = db.messageDao().getMessage("me@x.com", "INBOX", 4L)?.peekContent
            peek != null && peek.isNotEmpty()
        }
        assertTrue(runBlocking { db.queryDao().getRowsWithEmptyPeek() }.isEmpty())
    }
}
