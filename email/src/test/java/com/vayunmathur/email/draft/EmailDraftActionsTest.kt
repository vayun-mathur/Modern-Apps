package com.vayunmathur.email.draft

import android.app.Application
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.email.data.EmailDatabase
import com.vayunmathur.email.platform.EmailDraftActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
        var savedId = -1L
        actions.save(
            id = null, accountEmail = "me@x.com", to = "a@b.com",
            cc = "", bcc = "", subject = "Hi", body = "hello",
            onSaved = { savedId = it },
        )
        // The save runs on the actions' scope; wait for the callback.
        repeat(200) {
            if (savedId != -1L) return@repeat
            Thread.sleep(10)
        }
        val loaded = actions.load(savedId)
        assertEquals("a@b.com", loaded?.to)
        assertEquals("Hi", loaded?.subject)
        assertEquals("hello", loaded?.body)
    }

    @Test
    fun `save with an id updates the existing draft`() = runBlocking {
        var firstId = -1L
        actions.save(
            id = null, accountEmail = "me@x.com", to = "a@b.com",
            cc = "", bcc = "", subject = "v1", body = "b1",
            onSaved = { firstId = it },
        )
        repeat(200) {
            if (firstId != -1L) return@repeat
            Thread.sleep(10)
        }
        var secondId = -2L
        actions.save(
            id = firstId, accountEmail = "me@x.com", to = "c@d.com",
            cc = "", bcc = "", subject = "v2", body = "b2",
            onSaved = { secondId = it },
        )
        repeat(200) {
            if (secondId != -2L) return@repeat
            Thread.sleep(10)
        }
        assertEquals(firstId, secondId)
        val loaded = actions.load(firstId)
        assertEquals("c@d.com", loaded?.to)
        assertEquals("v2", loaded?.subject)
    }

    @Test
    fun `delete removes the draft`() = runBlocking {
        var savedId = -1L
        actions.save(
            id = null, accountEmail = "me@x.com", to = "a@b.com",
            cc = "", bcc = "", subject = "bye", body = "b",
            onSaved = { savedId = it },
        )
        repeat(200) {
            if (savedId != -1L) return@repeat
            Thread.sleep(10)
        }
        actions.delete(savedId)
        repeat(200) {
            if (actions.load(savedId) == null && savedId != -1L) return@repeat
            Thread.sleep(10)
        }
        assertNull(actions.load(savedId))
    }

    @Test
    fun `loading a missing draft returns null`() = runBlocking {
        assertNull(actions.load(999L))
    }
}
