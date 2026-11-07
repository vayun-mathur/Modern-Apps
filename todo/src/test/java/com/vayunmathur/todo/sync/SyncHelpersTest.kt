package com.vayunmathur.todo.sync

import android.content.Context
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.data.TodoList
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SyncHelpersTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun relayTodoRoundTrip() {
        val todo = Todo(id = 7, syncId = "abc", title = "T", notes = "N", done = true, position = 2.5, dueAt = 1000L, createdAt = 900L, updatedAt = 950L, deleted = false, parentSyncId = "p", listSyncId = "l", section = "S", tags = listOf("x"), priority = 2, dueAllDay = false, startAt = 800L, recurrence = "FREQ=DAILY", reminderAt = 700L, completedAt = 600L, estimateMinutes = 30, blockedBy = listOf("b1"))
        val json = todo.toRelayJson()
        val back = json.toTodo(7L)
        assertEquals(todo, back)
        // encode/decode via relayJson preserves
        val encoded = relayJson.encodeToString(json)
        assertEquals(json, relayJson.decodeFromString<RelayTodoJson>(encoded))
        assertTrue(encoded.contains("\"uid\":\"abc\""))
    }
    @Test fun relayListRoundTrip() {
        val list = TodoList(id = 3, syncId = "l1", name = "Work", color = 123, icon = "work", position = 1.5, sections = listOf("A", "B"), createdAt = 100L, updatedAt = 200L, deleted = true)
        assertEquals(list, list.toRelayJson().toTodoList(3L))
    }
    @Test fun contentHashIgnoresUidPositionUpdatedAtButNotContentOrDeleted() {
        val a = RelayTodoJson(uid = "a", title = "T", position = 1.0, updatedAt = 100L)
        val b = RelayTodoJson(uid = "b", title = "T", position = 9.0, updatedAt = 999L)
        assertEquals(relayContentHash(a), relayContentHash(b))
        assertNotEquals(relayContentHash(a), relayContentHash(a.copy(title = "Other")))
        assertNotEquals(relayContentHash(a), relayContentHash(a.copy(deleted = true)))
        val la = RelayTodoListJson(uid = "a", name = "L", position = 1.0, updatedAt = 1L)
        val lb = RelayTodoListJson(uid = "b", name = "L", position = 5.0, updatedAt = 5L)
        assertEquals(relayContentHash(la), relayContentHash(lb))
        assertEquals(64, relayContentHash(a).length)
        assertTrue(relayContentHash(a).all { it in '0'..'9' || it in 'a'..'f' })
    }
    @Test fun relayDefaultsOmittedInEncoding() {
        val encoded = relayJson.encodeToString(RelayTodoJson(uid = "x"))
        // encodeDefaults off: default fields omitted, uid present
        assertTrue(encoded.contains("uid"))
        assertFalse(encoded.contains("priority"))
    }
    @Test fun providerTypesAndUris() {
        val p = RelayTodoProvider()
        assertTrue(p.onCreate())
        assertEquals("vnd.android.cursor.dir/vnd.com.vayunmathur.todo.todo", p.getType(RelayTodoProvider.todosUri("v1")))
        assertEquals("vnd.android.cursor.dir/vnd.com.vayunmathur.todo.list", p.getType(RelayTodoProvider.listsUri("v1")))
        assertEquals("vnd.android.cursor.dir/vnd.com.vayunmathur.todo.snapshot", p.getType(RelayTodoProvider.snapshotsUri("v1")))
        assertEquals(null, p.getType("content://com.vayunmathur.todo.relay/unknown".toUri()))
        assertEquals("content://com.vayunmathur.todo.relay/vault/v1/todos", RelayTodoProvider.todosUri("v1").toString())
        assertEquals(listOf("syncId", "updatedAt", "deleted", "payloadJson", "contentHash"), RelayTodoProvider.COLUMNS.toList())
        assertEquals("com.vayunmathur.todo.relay", RelayTodoProvider.AUTHORITY)
    }
    @Test fun providerInsertNullValuesReturnsNull() {
        val p = RelayTodoProvider()
        assertEquals(null, p.insert(RelayTodoProvider.todosUri("v"), null))
        assertEquals(0, p.update(RelayTodoProvider.todosUri("v"), null, null, null))
    }
    @Test fun calendarMirrorNoPermissionIsNoOp() {
        assertFalse(CalendarMirror.hasPermission(context))
        // Should return early without throwing, enabled or not
        CalendarMirror.reconcile(context, enabled = true, todos = listOf(Todo(title = "t", dueAt = 1000L)))
        CalendarMirror.reconcile(context, enabled = false, todos = emptyList())
    }
    @Test fun widgetIntentsAndPreviewData() {
        val intent = com.vayunmathur.todo.widget.quickAddIntent(context)
        assertEquals(com.vayunmathur.todo.platform.IntentRouter.ACTION_QUICK_ADD, intent.action)
        assertEquals(com.vayunmathur.todo.widget.TodayWidgetData(emptyList()), com.vayunmathur.todo.widget.TodayWidgetData(emptyList()))
        assertEquals(com.vayunmathur.todo.widget.TodayWidgetRow("1", "t", true), com.vayunmathur.todo.widget.TodayWidgetRow("1", "t", true))
    }
}
