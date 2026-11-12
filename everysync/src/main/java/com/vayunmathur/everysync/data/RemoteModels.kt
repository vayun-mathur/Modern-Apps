package com.vayunmathur.everysync.data

import kotlinx.serialization.Serializable

/** A contact resolved from a relay vault record. */
@Serializable
data class RemoteContact(
    /** Stable relay sync identity (opaque 32-char id). */
    val uid: String,
    /** Relay updatedAt stamp as a string, for change detection; null when unknown. */
    val etag: String? = null,
    val displayName: String = "",
    val prefix: String = "",
    val firstName: String = "",
    val middleName: String = "",
    val lastName: String = "",
    val suffix: String = "",
    val organization: String = "",
    val note: String = "",
    /** number -> type (ContactsContract.CommonDataKinds.Phone type int). */
    val phones: List<TypedValue> = emptyList(),
    val emails: List<TypedValue> = emptyList(),
    val addresses: List<TypedValue> = emptyList(),
    /** ISO-8601 birthday (yyyy-MM-dd) or null. */
    val birthday: String? = null,
    /** Relay sync identity of the vault this record belongs to. */
    val href: String? = null,
    val deleted: Boolean = false,
    /** Last local-or-remote modification (epoch ms); drives relay LWW. */
    val updatedAt: Long = 0L,
)

@Serializable
data class TypedValue(val value: String, val type: Int)

/** A calendar event resolved from a relay vault record. */
@Serializable
data class RemoteEvent(
    val uid: String,
    /** Relay updatedAt stamp as a string, for change detection; null when unknown. */
    val etag: String? = null,
    val calendarId: String,
    val summary: String = "",
    val description: String = "",
    val location: String = "",
    val startMillis: Long = 0L,
    val endMillis: Long = 0L,
    val allDay: Boolean = false,
    val timezone: String = "UTC",
    /** Raw RRULE line (without the "RRULE:" prefix), or null. */
    val rrule: String? = null,
    /** RECURRENCE-ID of an overridden instance; null for the master. */
    val recurrenceId: String? = null,
    /** Relay sync identity of the vault this record belongs to. */
    val href: String? = null,
    val deleted: Boolean = false,
    /** Last local-or-remote modification (epoch ms); drives relay LWW. */
    val updatedAt: Long = 0L,
)

/**
 * A password or passkey record for the encrypted relay. Mirrors the native
 * vault fields so no KDBX mapping is needed: passwords use the name/username/
 * email/password/note/totp/websites half, passkeys ([isPasskey]) use the
 * rp/credential half. The relay envelope is the JSON of this class.
 */
@Serializable
data class RemotePassword(
    /** Relay sync identity (opaque 32-char id). */
    val uid: String,
    /** True for a passkey record, false for a password record. */
    val isPasskey: Boolean = false,
    val name: String = "",
    val username: String = "",
    val email: String = "",
    val password: String = "",
    val note: String = "",
    val totpSecret: String? = null,
    val websites: List<String> = emptyList(),
    // Passkey half (empty for passwords).
    val rpId: String = "",
    val rpName: String = "",
    val credentialId: String = "",
    val userId: String = "",
    val userName: String = "",
    val userDisplayName: String = "",
    /** Base64 of the private-key bytes. */
    val privateKeyB64: String = "",
    val creationTime: Long = 0L,
    val lastUsedTime: Long = 0L,
    val signCount: Int = 0,
    val linkedPasswordSyncId: String? = null,
    val deleted: Boolean = false,
    /** Last local-or-remote modification (epoch ms); drives relay LWW. */
    val updatedAt: Long = 0L,
)

/**
 * A note record for the encrypted relay. [blocksJson] is the stored
 * NoteBody JSON (text / image / ink blocks); image bytes themselves live in
 * the blob channel (see RelayBlobStore) and are referenced by file name.
 * The relay envelope is the JSON of this class.
 */
@Serializable
data class RemoteNote(
    /** Relay sync identity (opaque 32-char id). */
    val uid: String,
    val title: String = "",
    val content: String = "",
    /** Serialized NoteBody, or null for legacy text-only notes. */
    val blocksJson: String? = null,
    val position: Double = 0.0,
    val deleted: Boolean = false,
    /** Last local-or-remote modification (epoch ms); drives relay LWW. */
    val updatedAt: Long = 0L,
    /**
     * Note tags (notes #913). Mirror of notes' `RelayNoteJson.tags`: same name,
     * same last position. Defaults are omitted on encode, so an untagged note
     * hashes exactly as it did before tags existed; a tag edit is a content
     * change and syncs like any other.
     */
    val tags: List<String> = emptyList(),
)

/**
 * A todo task record for the encrypted relay. The relay envelope is the JSON
 * of this class on the `todos:<vaultId>` channel. The todo/ app owns on-device
 * storage; this shape is the contract between RelayMerge and its TodoSink.
 */
@Serializable
data class RemoteTodo(
    /** Relay sync identity (opaque 32-char id). */
    val uid: String,
    val title: String = "",
    val notes: String = "",
    val done: Boolean = false,
    /** Epoch ms the task is due, or null. */
    val dueAt: Long? = null,
    /** Sort position; excluded from the merge hash so reorder is not an edit. */
    val position: Double = 0.0,
    /** Epoch ms the task was created; part of the adoption content key. */
    val createdAt: Long = 0L,
    val deleted: Boolean = false,
    /** Last local-or-remote modification (epoch ms); drives relay LWW. */
    val updatedAt: Long = 0L,
    // v3 fields. Defaults are omitted on encode, so a task that never sets one hashes
    // exactly as it did before they existed. Mirror of todo's `RelayTodoJson`.
    /** Parent task uid for a subtask. */
    val parentUid: String? = null,
    /** Owning list uid ([RemoteTodoList.uid]); null is the Inbox. */
    val listUid: String? = null,
    val section: String? = null,
    val tags: List<String> = emptyList(),
    /** 0 none, 1 highest … 4 lowest. */
    val priority: Int = 0,
    /** When false, [dueAt] is an exact instant rather than local midnight. */
    val dueAllDay: Boolean = true,
    val startAt: Long? = null,
    /** RRULE-style repeat; `X-FROM=COMPLETION` marks after-completion repeats. */
    val recurrence: String? = null,
    val reminderAt: Long? = null,
    val completedAt: Long? = null,
    val estimateMinutes: Int? = null,
    /** Uids of tasks that must be done first. */
    val blockedBy: List<String> = emptyList(),
)

/**
 * A todo list (project), synced on its own `todolists:<vaultId>` channel so builds that
 * predate lists never mistake one for a task. Mirror of todo's `RelayTodoListJson`.
 */
@Serializable
data class RemoteTodoList(
    val uid: String,
    val name: String = "",
    /** ARGB, or null for the theme accent. */
    val color: Int? = null,
    val icon: String? = null,
    /** Sort position; excluded from the merge hash. */
    val position: Double = 0.0,
    val sections: List<String> = emptyList(),
    val createdAt: Long = 0L,
    val deleted: Boolean = false,
    val updatedAt: Long = 0L,
)
