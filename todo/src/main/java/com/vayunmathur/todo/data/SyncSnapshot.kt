package com.vayunmathur.todo.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import java.util.UUID

/** Opaque 32-char identity shared between a local row and its relay entry. */
fun newSyncId(): String = UUID.randomUUID().toString().replace("-", "")

/**
 * Last agreed state of one synced entry, written after a successful relay sync.
 *
 * Without this baseline "present locally, absent remotely" cannot be told apart from
 * "created locally, not pushed yet", so deletions could never propagate.
 */
@Entity
data class SyncSnapshot(
    @PrimaryKey val syncId: String,
    val kind: String,
    val contentHash: String,
    val localUpdatedAt: Long,
    val remoteModified: Long,
    val lastSyncedAt: Long,
) {
    companion object {
        const val KIND_TODO = "todo"
        const val KIND_LIST = "todolist"
    }
}
