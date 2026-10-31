package com.vayunmathur.todo.sync

import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.data.TodoList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Relay envelope for one todo record. Field-for-field mirror of everysync's `RemoteTodo`
 * (the bytes the relay log carries).
 *
 * todo/ must not depend on everysync, so the shape is duplicated here. Both sides decode with
 * `ignoreUnknownKeys` and encode without defaults: a field left at its default is omitted, so
 * the hash of a task that never touched a newer field matches what older builds compute.
 * Never rename a key — the other side would silently drop it.
 */
@Serializable
internal data class RelayTodoJson(
    /** Relay sync identity (opaque 32-char id); maps to [Todo.syncId]. */
    val uid: String = "",
    val title: String = "",
    val notes: String = "",
    val done: Boolean = false,
    val dueAt: Long? = null,
    val position: Double = 0.0,
    /** Epoch ms the task was created; part of the adoption content key. */
    val createdAt: Long = 0L,
    val deleted: Boolean = false,
    /** Last local-or-remote modification (epoch ms); drives relay LWW. */
    val updatedAt: Long = 0L,
    val parentUid: String? = null,
    val listUid: String? = null,
    val section: String? = null,
    val tags: List<String> = emptyList(),
    val priority: Int = 0,
    val dueAllDay: Boolean = true,
    val startAt: Long? = null,
    val recurrence: String? = null,
    val reminderAt: Long? = null,
    val completedAt: Long? = null,
    val estimateMinutes: Int? = null,
    val blockedBy: List<String> = emptyList(),
)

/** Relay envelope for one list. Mirror of everysync's `RemoteTodoList`. */
@Serializable
internal data class RelayTodoListJson(
    val uid: String = "",
    val name: String = "",
    val color: Int? = null,
    val icon: String? = null,
    val position: Double = 0.0,
    val sections: List<String> = emptyList(),
    val createdAt: Long = 0L,
    val deleted: Boolean = false,
    val updatedAt: Long = 0L,
)

internal val relayJson = Json { ignoreUnknownKeys = true }

internal fun Todo.toRelayJson(): RelayTodoJson = RelayTodoJson(
    uid = syncId,
    title = title,
    notes = notes,
    done = done,
    dueAt = dueAt,
    position = position,
    createdAt = createdAt,
    deleted = deleted,
    updatedAt = updatedAt,
    parentUid = parentSyncId,
    listUid = listSyncId,
    section = section,
    tags = tags,
    priority = priority,
    dueAllDay = dueAllDay,
    startAt = startAt,
    recurrence = recurrence,
    reminderAt = reminderAt,
    completedAt = completedAt,
    estimateMinutes = estimateMinutes,
    blockedBy = blockedBy,
)

internal fun RelayTodoJson.toTodo(id: Long): Todo = Todo(
    id = id,
    title = title,
    notes = notes,
    done = done,
    position = position,
    dueAt = dueAt,
    createdAt = createdAt,
    syncId = uid,
    updatedAt = updatedAt,
    deleted = deleted,
    parentSyncId = parentUid,
    listSyncId = listUid,
    section = section,
    tags = tags,
    priority = priority,
    dueAllDay = dueAllDay,
    startAt = startAt,
    recurrence = recurrence,
    reminderAt = reminderAt,
    completedAt = completedAt,
    estimateMinutes = estimateMinutes,
    blockedBy = blockedBy,
)

internal fun TodoList.toRelayJson(): RelayTodoListJson = RelayTodoListJson(
    uid = syncId,
    name = name,
    color = color,
    icon = icon,
    position = position,
    sections = sections,
    createdAt = createdAt,
    deleted = deleted,
    updatedAt = updatedAt,
)

internal fun RelayTodoListJson.toTodoList(id: Long): TodoList = TodoList(
    id = id,
    syncId = uid,
    name = name,
    color = color,
    icon = icon,
    position = position,
    sections = sections,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deleted = deleted,
)

/**
 * Merge-compatible content hash: canonical envelope (`uid`, `position`, `updatedAt`,
 * `deleted` stripped) plus the deleted flag, SHA-256 hexed — the same bytes the relay merge
 * driver hashes, so LWW tiebreaks agree on both sides.
 */
internal fun relayContentHash(e: RelayTodoJson): String = sha256Hex(
    relayJson.encodeToString(e.copy(uid = "", position = 0.0, updatedAt = 0L, deleted = false)) +
        "\u0000deleted=${e.deleted}",
)

internal fun relayContentHash(e: RelayTodoListJson): String = sha256Hex(
    relayJson.encodeToString(e.copy(uid = "", position = 0.0, updatedAt = 0L, deleted = false)) +
        "\u0000deleted=${e.deleted}",
)

private fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
