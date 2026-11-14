package com.vayunmathur.everysync.sync.adapters

import com.vayunmathur.everysync.data.RemoteNote
import com.vayunmathur.everysync.sync.EntityKind
import com.vayunmathur.everysync.sync.relayContentHash
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Relay merge behaviour for notes. The merged form is the [RemoteNote] JSON
 * (title + content + NoteBody blocks JSON); image bytes ride the blob channel
 * (see `RelayBlobStore`) and are referenced by file name inside the blocks.
 */
object NoteKind : EntityKind<RemoteNote>() {
    override val kind = "note"

    /** Notes soft-delete (tombstone rows survive locally); the default holds. */
    override val localDeletesAreAuthoritative = true

    private const val CONTENT_PREVIEW_LENGTH = 64

    private val json = Json { ignoreUnknownKeys = true }

    override fun syncId(e: RemoteNote) = e.uid
    override fun updatedAt(e: RemoteNote) = e.updatedAt
    override fun isDeleted(e: RemoteNote) = e.deleted

    override fun contentHash(e: RemoteNote): String =
        relayContentHash(canonical(e) + "\u0000deleted=${e.deleted}")

    override fun contentKey(e: RemoteNote): String =
        "note\u0000${e.title}\u0000${e.content.take(CONTENT_PREVIEW_LENGTH)}"

    override fun rebind(e: RemoteNote, syncId: String) = e.copy(uid = syncId)
    override fun withDeleted(e: RemoteNote, deleted: Boolean) = e.copy(deleted = deleted)
    override fun withUpdatedAt(e: RemoteNote, updatedAt: Long) = e.copy(updatedAt = updatedAt)

    /**
     * Canonical JSON with identity, position and timestamps stripped, so the
     * hash is comparable across sides and a reorder never looks like an edit.
     */
    fun canonical(e: RemoteNote): String = json.encodeToString(
        e.copy(uid = "", position = 0.0, updatedAt = 0L, deleted = false),
    )

    fun toJson(e: RemoteNote): String = json.encodeToString(e)

    fun fromJson(raw: String): RemoteNote? = runCatching {
        json.decodeFromString<RemoteNote>(raw)
    }.getOrNull()

    /** File names referenced by image blocks inside [blocksJson]. */
    fun imageNames(blocksJson: String?): List<String> {
        if (blocksJson.isNullOrBlank()) return emptyList()
        // NoteBody blocks are `{"blocks":[...]}`; image blocks carry "fileName".
        // A tolerant substring scan avoids depending on the notes module's types.
        return Regex("\"fileName\"\\s*:\\s*\"([^\"]+)\"").findAll(blocksJson)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }
}
