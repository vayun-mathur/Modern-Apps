package com.vayunmathur.everysync.sync.adapters

import com.vayunmathur.everysync.data.RemoteTodo
import com.vayunmathur.everysync.data.RemoteTodoList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The v3 todo fields and the list kind, with cross-version hash stability. */
class TodoListKindTest {

    @Test
    fun legacyTodoPayloadHashesTheSameUnderTheV3Model() {
        // Bytes a pre-v3 build put on the relay for this task.
        val legacyJson = """{"uid":"t1","title":"Shop","done":true,"dueAt":5,"createdAt":7,"updatedAt":100}"""
        val parsed = TodoKind.fromJson(legacyJson)!!
        assertEquals(legacyJson, TodoKind.toJson(parsed))
        assertEquals(
            """{"uid":"","title":"Shop","done":true,"dueAt":5,"createdAt":7}""",
            TodoKind.canonical(parsed),
        )
    }

    @Test
    fun v3TodoFieldsRoundTripAndCountAsEdits() {
        val base = RemoteTodo(uid = "t1", title = "Shop", createdAt = 7L)
        val rich = base.copy(
            listUid = "l1",
            parentUid = "p1",
            tags = listOf("home"),
            priority = 1,
            dueAllDay = false,
            recurrence = "FREQ=DAILY",
            blockedBy = listOf("b1"),
        )
        assertEquals(rich, TodoKind.fromJson(TodoKind.toJson(rich)))
        assertTrue(TodoKind.contentHash(base) != TodoKind.contentHash(rich))
        assertTrue(TodoKind.contentHash(base) != TodoKind.contentHash(base.copy(priority = 2)))
    }

    @Test
    fun listCanonicalIgnoresIdentityOrderAndClock() {
        val base = RemoteTodoList(uid = "l1", name = "Work", position = 1.0, createdAt = 3L, updatedAt = 10L)
        val variant = base.copy(uid = "l2", position = 9.0, updatedAt = 99L)
        assertEquals(TodoListKind.contentHash(base), TodoListKind.contentHash(variant))
        assertTrue(TodoListKind.contentHash(base) != TodoListKind.contentHash(base.copy(name = "Home")))
        assertTrue(TodoListKind.contentHash(base) != TodoListKind.contentHash(base.copy(deleted = true)))
    }

    @Test
    fun listContentKeyUsesNameAndCreatedAt() {
        val a = RemoteTodoList(uid = "a", name = "Work", createdAt = 1L)
        assertEquals(TodoListKind.contentKey(a), TodoListKind.contentKey(a.copy(uid = "b")))
        assertTrue(TodoListKind.contentKey(a) != TodoListKind.contentKey(a.copy(name = "Home")))
        assertTrue(TodoListKind.contentKey(a).startsWith("todolist"))
    }

    @Test
    fun listJsonRoundTrips() {
        val list = RemoteTodoList(uid = "l1", name = "Work", color = -16711936, icon = "work", sections = listOf("A", "B"))
        assertEquals(list, TodoListKind.fromJson(TodoListKind.toJson(list)))
    }
}
