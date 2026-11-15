package com.vayunmathur.email.draft

import android.app.Application
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.email.data.EmailDatabase
import com.vayunmathur.email.platform.EmailDraftActions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives the real [EmailDraftActions] against an in-memory database: drafts
 * are the crash-safety net for the composer, so a lost insert or a stale
 * update silently eats the user's mail. Each test round-trips through the DAO
 * and asserts the exact stored row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmailDraftActionsTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: EmailDatabase
    private lateinit var actions: EmailDraftActions

    @BeforeTest
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, EmailDatabase::class.java)
            .allowMainThreadQueries().build()
        // Unconfined runs the launched saves eagerly, keeping tests deterministic.
        actions = EmailDraftActions(CoroutineScope(Dispatchers.Unconfined), db.outboxDao())
    }

    @AfterTest
    fun close() {
        db.close()
    }

    @Test
    fun `save inserts a new draft and reports its id`() = runBlocking {
        // The save runs on the actions' scope; await its callback directly
        // rather than polling a flag on a wall-clock budget.
        val saved = CompletableDeferred<Long>()
        actions.save(
            id = null, accountEmail = "me@x.com", to = "a@b.com",
            cc = "", bcc = "", subject = "Hi", body = "hello",
            onSaved = { saved.complete(it) },
        )
        val savedId = withTimeout(30_000) { saved.await() }
        val loaded = actions.load(savedId)
        assertEquals("a@b.com", loaded?.to)
        assertEquals("Hi", loaded?.subject)
        assertEquals("hello", loaded?.body)
    }

    @Test
    fun `save with an id updates the existing draft`() = runBlocking {
        val first = CompletableDeferred<Long>()
        actions.save(
            id = null, accountEmail = "me@x.com", to = "a@b.com",
            cc = "", bcc = "", subject = "v1", body = "b1",
            onSaved = { first.complete(it) },
        )
        val firstId = withTimeout(30_000) { first.await() }
        val second = CompletableDeferred<Long>()
        actions.save(
            id = firstId, accountEmail = "me@x.com", to = "c@d.com",
            cc = "", bcc = "", subject = "v2", body = "b2",
            onSaved = { second.complete(it) },
        )
        val secondId = withTimeout(30_000) { second.await() }
        assertEquals(firstId, secondId)
        val loaded = actions.load(firstId)
        assertEquals("c@d.com", loaded?.to)
        assertEquals("v2", loaded?.subject)
    }

    @Test
    fun `delete removes the draft`() = runBlocking {
        val saved = CompletableDeferred<Long>()
        actions.save(
            id = null, accountEmail = "me@x.com", to = "a@b.com",
            cc = "", bcc = "", subject = "bye", body = "b",
            onSaved = { saved.complete(it) },
        )
        val savedId = withTimeout(30_000) { saved.await() }
        actions.delete(savedId)
        // Delete is fire-and-forget on the actions' scope: wait until the row
        // is actually gone instead of sleeping a fixed budget.
        withTimeout(30_000) {
            while (actions.load(savedId) != null) {
                kotlinx.coroutines.delay(10)
            }
        }
        assertNull(actions.load(savedId))
    }

    @Test
    fun `loading a missing draft returns null`() = runBlocking {
        assertNull(actions.load(999L))
    }
}
