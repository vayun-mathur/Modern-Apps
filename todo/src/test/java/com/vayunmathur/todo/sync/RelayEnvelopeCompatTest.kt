package com.vayunmathur.todo.sync

import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.data.TodoList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Cross-version safety for the v3 envelope: tasks that never touch a new field must hash
 * exactly like the pre-v3 envelope did, or every existing task would look edited.
 */
class RelayEnvelopeCompatTest {

    /** The pre-v3 envelope, verbatim. */
    @Serializable
    private data class LegacyEnvelope(
        val uid: String = "",
        val title: String = "",
        val notes: String = "",
        val done: Boolean = false,
        val dueAt: Long? = null,
        val position: Double = 0.0,
        val createdAt: Long = 0L,
        val deleted: Boolean = false,
        val updatedAt: Long = 0L,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun legacyHash(e: LegacyEnvelope): String {
        val canonical = json.encodeToString(e.copy(uid = "", position = 0.0, updatedAt = 0L, deleted = false)) +
            "\u0000deleted=${e.deleted}"
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `a legacy-shaped task hashes exactly as before`() {
        val todo = Todo(title = "Milk", notes = "2%", done = true, dueAt = 5L, createdAt = 9L, syncId = "u1", updatedAt = 3L)
        val legacy = LegacyEnvelope(uid = "u1", title = "Milk", notes = "2%", done = true, dueAt = 5L, createdAt = 9L, updatedAt = 3L)
        assertEquals(legacyHash(legacy), relayContentHash(todo.toRelayJson()))
    }

    @Test
    fun `a legacy payload decodes with v3 defaults`() {
        val raw = json.encodeToString(LegacyEnvelope(uid = "u1", title = "t", dueAt = 7L))
        val todo = relayJson.decodeFromString<RelayTodoJson>(raw).toTodo(0)
        assertEquals(true, todo.dueAllDay)
        assertEquals(emptyList(), todo.tags)
        assertEquals(null, todo.listSyncId)
        assertEquals(0, todo.priority)
    }

    @Test
    fun `every v3 field survives the round trip`() {
        val todo = Todo(
            syncId = "u1",
            title = "t",
            parentSyncId = "p",
            listSyncId = "l",
            section = "s",
            tags = listOf("home", "errand"),
            priority = 2,
            dueAt = 100L,
            dueAllDay = false,
            startAt = 50L,
            recurrence = "FREQ=WEEKLY;BYDAY=MO",
            reminderAt = 90L,
            completedAt = 80L,
            estimateMinutes = 25,
            blockedBy = listOf("b"),
        )
        val back = relayJson.decodeFromString<RelayTodoJson>(relayJson.encodeToString(todo.toRelayJson())).toTodo(0)
        assertEquals(todo.copy(id = 0), back)
    }

    @Test
    fun `editing a new field changes the hash but reordering does not`() {
        val base = Todo(syncId = "u1", title = "t").toRelayJson()
        assertNotEquals(relayContentHash(base), relayContentHash(base.copy(priority = 1)))
        assertEquals(relayContentHash(base), relayContentHash(base.copy(position = 99.0)))
    }

    @Test
    fun `lists round trip and ignore position in the hash`() {
        val list = TodoList(syncId = "l1", name = "Work", color = 0xFF00FF00.toInt(), icon = "work", sections = listOf("A", "B"))
        val relay = list.toRelayJson()
        assertEquals(list.copy(id = 3), relayJson.decodeFromString<RelayTodoListJson>(relayJson.encodeToString(relay)).toTodoList(3))
        assertEquals(relayContentHash(relay), relayContentHash(relay.copy(position = 7.0)))
    }
}
