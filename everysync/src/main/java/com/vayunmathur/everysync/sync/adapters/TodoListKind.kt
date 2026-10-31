package com.vayunmathur.everysync.sync.adapters

import com.vayunmathur.everysync.data.RemoteTodoList
import com.vayunmathur.everysync.sync.EntityKind
import com.vayunmathur.everysync.sync.relayContentHash
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Relay merge behaviour for todo lists on the `todolists:<vaultId>` channel, the companion
 * of [TodoKind]. Lists soft-delete like tasks; the canonical form strips identity,
 * ordering and timestamps so a reorder never looks like an edit.
 */
object TodoListKind : EntityKind<RemoteTodoList>() {
    override val kind = "todolist"

    override val localDeletesAreAuthoritative = true

    private val json = Json { ignoreUnknownKeys = true }

    override fun syncId(e: RemoteTodoList) = e.uid
    override fun updatedAt(e: RemoteTodoList) = e.updatedAt
    override fun isDeleted(e: RemoteTodoList) = e.deleted

    override fun contentHash(e: RemoteTodoList): String =
        relayContentHash(canonical(e) + "\u0000deleted=${e.deleted}")

    override fun contentKey(e: RemoteTodoList): String = "todolist\u0000${e.name}\u0000${e.createdAt}"

    override fun rebind(e: RemoteTodoList, syncId: String) = e.copy(uid = syncId)
    override fun withDeleted(e: RemoteTodoList, deleted: Boolean) = e.copy(deleted = deleted)
    override fun withUpdatedAt(e: RemoteTodoList, updatedAt: Long) = e.copy(updatedAt = updatedAt)

    fun canonical(e: RemoteTodoList): String = json.encodeToString(
        e.copy(uid = "", position = 0.0, updatedAt = 0L, deleted = false),
    )

    fun toJson(e: RemoteTodoList): String = json.encodeToString(e)

    fun fromJson(raw: String): RemoteTodoList? = runCatching {
        json.decodeFromString<RemoteTodoList>(raw)
    }.getOrNull()
}
