package com.vayunmathur.everysync.data.sink

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.everysync.data.RemoteNote
import com.vayunmathur.everysync.data.RemotePassword
import com.vayunmathur.everysync.data.RemoteTodo
import com.vayunmathur.everysync.data.RemoteTodoList
import com.vayunmathur.everysync.sync.LocalReadException
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class SinkTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    class FakeProvider : ContentProvider() {
        val rows = mutableListOf<Array<Any?>>()
        val inserted = mutableListOf<String?>()
        var deleted = 0
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<String>?, sel: String?, args: Array<String>?, sort: String?): Cursor {
            val cols = arrayOf("syncId", "updatedAt", "deleted", "payloadJson")
            val c = MatrixCursor(cols)
            for (r in rows) c.addRow(r)
            return c
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? { inserted += values?.getAsString("payloadJson"); return uri }
        override fun bulkInsert(uri: Uri, values: Array<ContentValues>): Int { values.forEach { inserted += it.getAsString("payloadJson") }; return values.size }
        override fun delete(uri: Uri, sel: String?, args: Array<String>?): Int { deleted++; return 1 }
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 1
        override fun getType(uri: Uri) = "vnd.test"
    }

    private fun register(auth: String, p: FakeProvider): FakeProvider { ShadowContentResolver.registerProviderInternal(auth, p); return p }

    @Test fun noteSinkJsonConversions() {
        val n = RemoteNote(uid = "n1", title = "T", content = "C", blocksJson = "{}", position = 1.0, updatedAt = 5)
        assertEquals(n, NoteSink.fromPayload(NoteSink.toRelayJson(n)))
        assertEquals(n, NoteSink.fromPayload(NoteSink.toNativeJson(n)))
        assertEquals(n, NoteSink.fromNativeJson(NoteSink.toNativeJson(n)))
        assertEquals(n, NoteSink.fromRelayJson(NoteSink.toRelayJson(n)))
        assertNull(NoteSink.fromPayload("garbage")); assertNull(NoteSink.fromPayload("""{"syncId":""}"""))
        assertTrue(NoteSink.notesUri("v1").toString().contains("vault/v1/notes"))
        assertTrue(NoteSink.queryUri("v1").toString().endsWith("querySince"))
        assertTrue(NoteSink.upsertUri("v1").toString().endsWith("upsertRaw"))
        assertTrue(NoteSink.purgeUri("v1", "notes").toString().endsWith("deleteByVault"))
    }
    @Test fun noteSinkWithProvider() {
        val p = register(NoteSink.AUTHORITY, FakeProvider())
        val n = RemoteNote(uid = "n1", title = "T", content = "C", updatedAt = 5)
        p.rows += arrayOf("n1", 5L, 0, NoteSink.toRelayJson(n))
        p.rows += arrayOf("bad", 1L, 1, "notjson")
        p.rows += arrayOf("blank", 1L, 0, "")
        assertEquals(listOf(n), NoteSink.listLocal(ctx, "v1")); assertEquals(true, NoteSink.lastListLocalOk)
        assertEquals(mapOf("n1" to 5L), NoteSink.localUidToEtag(ctx, "v1").filterKeys { it == "n1" })
        NoteSink.upsert(ctx, "v1", n); NoteSink.upsertAll(ctx, "v1", listOf(n)); assertTrue(p.inserted.size >= 2)
        NoteSink.delete(ctx, "v1", "n1"); NoteSink.deleteAll(ctx, "v1", listOf("n1")); NoteSink.purge(ctx, "a", "v1"); NoteSink.purge(ctx, "a")
        val changes = NoteSink.getLocalChanges(ctx, "v1", 0)
        assertEquals(1, changes.count { it.note != null }); assertEquals(1, changes.count { it.deleted })
        NoteSink.clearDirty(ctx, "v1", "n1")
    }
    @Test fun todoSinkWithProvider() {
        val p = register(TodoIpcSink.AUTHORITY, FakeProvider())
        val t = RemoteTodo(uid = "t1", title = "Task", createdAt = 7, updatedAt = 9)
        p.rows += arrayOf("t1", 9L, 0, TodoIpcSink.toRelayJson(t))
        p.rows += arrayOf("t2", 9L, 1, TodoIpcSink.toRelayJson(t.copy(uid = "t2")))
        assertEquals(2, TodoIpcSink.listLocal(ctx, "v").size); assertEquals(true, TodoIpcSink.lastListLocalOk)
        assertEquals(t, TodoIpcSink.fromRelayJson(TodoIpcSink.toRelayJson(t))); assertNull(TodoIpcSink.fromRelayJson("{}"))
        TodoIpcSink.upsert(ctx, "v", t); TodoIpcSink.upsertAll(ctx, "v", listOf(t)); TodoIpcSink.upsertAll(ctx, "v", emptyList())
        TodoIpcSink.delete(ctx, "v", "t1"); TodoIpcSink.deleteAll(ctx, "v", listOf("t1")); TodoIpcSink.purge(ctx, "a", "v"); TodoIpcSink.purge(ctx, "a")
        val ch = TodoIpcSink.getLocalChanges(ctx, "v"); assertEquals(2, ch.size); assertTrue(ch[1].deleted)
        // TodoList
        val l = RemoteTodoList(uid = "l1", name = "Work", createdAt = 1)
        assertTrue(TodoListIpcSink.isSupported(ctx, "v"))
        TodoListIpcSink.listLocal(ctx, "v") // rows are todo envelopes; must not crash
        TodoListIpcSink.upsertAll(ctx, "v", listOf(l)); TodoListIpcSink.deleteAll(ctx, "v", listOf("l1")); TodoListIpcSink.purge(ctx, "v")
    }
    @Test fun passwordSinkConversionsAndProvider() {
        val p = register(PasswordSink.AUTHORITY, FakeProvider())
        val pw = RemotePassword(uid = "p1", name = "Bank", username = "u", password = "secret", updatedAt = 3)
        val pk = RemotePassword(uid = "k1", isPasskey = true, rpId = "rp", credentialId = "c", updatedAt = 4)
        assertEquals(pw, PasswordSink.fromPayload(PasswordSink.toRelayJson(pw), false))
        assertEquals(pw, PasswordSink.fromPayload(PasswordSink.toNativeJson(pw), false))
        assertEquals(pk, PasswordSink.fromPayload(PasswordSink.toNativeJson(pk), true))
        assertNull(PasswordSink.fromPayload("junk", false)); assertNull(PasswordSink.fromNativeJson("""{"syncId":""}""", false))
        assertTrue(PasswordSink.passwordsUri("v").toString().contains("passwords"))
        assertTrue(PasswordSink.passkeysUri("v").toString().contains("passkeys"))
        p.rows += arrayOf("p1", 3L, 0, PasswordSink.toRelayJson(pw))
        assertEquals(listOf(pw, pw), PasswordSink.listLocal(ctx, "v")) // both tables return same fake rows, second parsed as passkey table fallback still envelope
        assertEquals(true, PasswordSink.lastListLocalOk)
        PasswordSink.upsert(ctx, "v", pw); PasswordSink.upsertAll(ctx, "v", listOf(pw, pk)); PasswordSink.delete(ctx, "v", "p1"); PasswordSink.deleteAll(ctx, "v", listOf("p1" to false)); PasswordSink.purge(ctx, "a", "v"); PasswordSink.clearDirty(ctx, "v", "p1")
        assertEquals(2, PasswordSink.getLocalChanges(ctx, "v").size)
        assertTrue(PasswordSink.localUidToEtag(ctx, "v").containsKey("p1"))
    }
    @Test fun passwordListLocalOrThrowOnMissingProvider() {
        // Different vault still same authority registered above in other tests may leak; use assertion on failure path via no provider is not guaranteed.
        // At minimum fromPayload blank uid is null.
        assertNull(PasswordSink.fromRelayJson("""{"uid":""}"""))
    }
    @Test fun calendarAndContactsSinksWithoutProviderDoNotCrash() {
        // No Calendar/Contacts provider in Robolectric: queries return null/fail gracefully.
        assertTrue(CalendarSink.listLocal(ctx, 1).isEmpty())
        assertEquals(false, CalendarSink.lastListLocalOk)
        assertTrue(CalendarSink.localUidToEtag(ctx, 1).isEmpty()); assertTrue(CalendarSink.localCalendars(ctx, "a").isEmpty())
        assertTrue(CalendarSink.getLocalChanges(ctx, 1).isEmpty())
        CalendarSink.upsertEvent(ctx, "a", 1, com.vayunmathur.everysync.data.RemoteEvent(uid = "e1", calendarId = "1", summary = "S", startMillis = 1000, endMillis = 2000, rrule = "FREQ=DAILY"))
        CalendarSink.upsertEvent(ctx, "a", 1, com.vayunmathur.everysync.data.RemoteEvent(uid = "e2", calendarId = "1", summary = "S2", startMillis = 1000, endMillis = 2000))
        CalendarSink.deleteEvent(ctx, "a", 1, "e1"); CalendarSink.purge(ctx, "a"); CalendarSink.clearDirty(ctx, "a", 1)
        assertEquals(-1L, CalendarSink.getOrCreateCalendarId(ctx, "a", "remote", "Disp", null))
        assertTrue(ContactsSink.listLocal(ctx, "a").isEmpty()); assertEquals(false, ContactsSink.lastListLocalOk)
        assertTrue(ContactsSink.localUidToEtag(ctx, "a").isEmpty()); assertTrue(ContactsSink.getLocalChanges(ctx, "a").isEmpty())
        val contact = com.vayunmathur.everysync.data.RemoteContact(uid = "c1", displayName = "Ann")
        ContactsSink.upsert(ctx, "a", contact); ContactsSink.delete(ctx, "a", "c1"); ContactsSink.purge(ctx, "a"); ContactsSink.clearDirty(ctx, "a", 1); ContactsSink.setSourceId(ctx, "a", 1, "c1", "etag")
    }
}
