package com.vayunmathur.email.data

import android.app.Application
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives the real [EmailOutboxStore] against an in-memory database: the
 * outbox is the send-retry queue, so a lost entry means a lost mail and a
 * stuck attempt count retries forever. Each test asserts the exact stored
 * rows and counters.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmailOutboxStoreTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: EmailDatabase
    private lateinit var store: EmailOutboxStore

    private fun entry(subject: String = "Hi") = OutboxEntry(
        accountEmail = "me@x.com", to = "a@b.com", subject = subject, body = "b",
    )

    @BeforeTest
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, EmailDatabase::class.java)
            .allowMainThreadQueries().build()
        store = EmailOutboxStore(db)
    }

    @AfterTest
    fun close() {
        db.close()
    }

    @Test
    fun `inserted entries appear in the queue and the count`() = runBlocking {
        assertEquals(0, store.getOutboxCount())
        store.insertOutboxEntry(entry("one"))
        store.insertOutboxEntry(entry("two"))
        assertEquals(2, store.getOutboxCount())
        val subjects = store.getOutbox().map { it.subject }.sorted()
        assertEquals(listOf("one", "two"), subjects)
    }

    @Test
    fun `outbox flow emits the inserted rows`() = runBlocking {
        store.insertOutboxEntry(entry("flow"))
        val rows = store.getOutboxFlow().first()
        assertEquals(listOf("flow"), rows.map { it.subject })
    }

    @Test
    fun `attempt updates persist error and retry count`() = runBlocking {
        val id = store.insertOutboxEntry(entry("stuck"))
        store.updateOutboxAttempt(id, "timeout", 3, 12345L)
        val row = store.getOutbox().single()
        assertEquals("timeout", row.lastError)
        assertEquals(3, row.attemptCount)
    }

    @Test
    fun `deleted entries leave the queue`() = runBlocking {
        val id = store.insertOutboxEntry(entry("gone"))
        val row = store.getOutbox().single { it.subject == "gone" }
        assertEquals(id, row.id)
        store.deleteOutboxEntry(row)
        assertEquals(0, store.getOutboxCount())
    }

    @Test
    fun `drafts round-trip through insert load and delete`() = runBlocking {
        val id = store.insertDraft(
            DraftEntry(accountEmail = "me@x.com", to = "a@b.com", subject = "d", body = "b")
        )
        assertEquals("d", store.getDraft(id)?.subject)
        assertTrue(store.getDraftsFlow().first().any { it.id == id })
        store.deleteDraftById(id)
        assertNull(store.getDraft(id))
    }
}
