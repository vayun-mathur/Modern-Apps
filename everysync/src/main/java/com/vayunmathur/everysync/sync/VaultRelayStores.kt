package com.vayunmathur.everysync.sync

import android.content.Context
import com.vayunmathur.everysync.data.RemoteContact
import com.vayunmathur.everysync.data.RemoteEvent
import com.vayunmathur.everysync.data.RemoteNote
import com.vayunmathur.everysync.data.RemotePassword
import com.vayunmathur.everysync.data.RemoteTodo
import com.vayunmathur.everysync.data.RemoteTodoList
import com.vayunmathur.everysync.data.sink.CalendarSink
import com.vayunmathur.everysync.data.sink.ContactsSink
import com.vayunmathur.everysync.data.sink.NoteSink
import com.vayunmathur.everysync.data.sink.PasswordSink
import com.vayunmathur.everysync.data.sink.TodoIpcSink
import com.vayunmathur.everysync.data.sink.TodoListIpcSink
import com.vayunmathur.everysync.sync.adapters.CalendarKind
import com.vayunmathur.everysync.sync.adapters.ContactKind
import com.vayunmathur.everysync.sync.adapters.NoteKind
import com.vayunmathur.everysync.sync.adapters.PasswordKind
import com.vayunmathur.everysync.sync.adapters.TodoKind
import com.vayunmathur.everysync.sync.adapters.TodoListKind

/**
 * [RelayStore] implementations over the on-device sinks, one per vault type.
 *
 * Each store bridges three worlds:
 *  - local rows: read via the sink's full-state reader, written via its
 *    upsert/delete (which preserve relay identities + timestamps through the
 *    `upsertRaw` path on the app side, or the SOURCE_ID/SYNC columns on the
 *    system-provider side).
 *  - relay envelopes: parsed/serialized with the per-type adapter's
 *    `fromJson`/`toJson` helpers so bytes stay identical to what the merge
 *    itself would produce.
 *  - baseline: [VaultBaseline] in everysync's DataStore, keyed by
 *    (vault, kind) — never the app-side snapshot tables, which belong to the
 *    app processes.
 *
 * Contacts/calendar identity notes:
 *  - contacts key on [RemoteContact.uid] ↔ RawContacts.SOURCE_ID (see
 *    `ContactKind`); the calendarId field is unused.
 *  - calendar keys on UID + RECURRENCE-ID (see `CalendarKind`): overridden
 *    instances merge independently of their master. The sink stores one row
 *    per (_SYNC_ID = uid) today; recurrence instances that share a uid fold
 *    onto the same row and the last one wins the local write — the relay log
 *    still carries every instance, so no data is lost relay-side.
 */
class ContactRelayStore(
    private val context: Context,
    private val accountName: String,
) : RelayStore<RemoteContact> {
    /**
     * Contacts soft-delete at the platform layer (DELETED flags surfaced via
     * `getLocalChanges`), so a missing id without a mark is never a local
     * delete (see `EntityKind.localDeletesAreAuthoritative`). Read health is
     * still enforced via `listLocalOk`.
     */
    override val listLocalOk: Boolean
        get() = ContactsSink.lastListLocalOk

    override suspend fun listLocal(): List<RemoteContact> =
        ContactsSink.listLocal(context, accountName)

    override suspend fun upsertLocal(items: List<RemoteContact>) {
        for (item in items) ContactsSink.upsert(context, accountName, item)
    }

    override suspend fun deleteLocal(items: List<RemoteContact>) {
        for (item in items) ContactsSink.delete(context, accountName, item.uid)
    }

    override suspend fun loadBaseline(): Map<String, RelaySnapshot> =
        VaultBaseline.load(context, accountName, ContactKind.kind)

    override suspend fun saveSnapshots(snapshots: List<RelaySnapshot>) {
        VaultBaseline.save(context, accountName, ContactKind.kind, snapshots)
    }

    override suspend fun removeSnapshots(syncIds: List<String>) {
        VaultBaseline.remove(context, accountName, ContactKind.kind, syncIds)
    }

    override fun parseRemote(json: String): RemoteContact? =
        ContactKind.fromJson(json)?.takeIf { it.uid.isNotBlank() }

    override fun serializeForRemote(entity: RemoteContact): String =
        ContactKind.toJson(entity)
}

class CalendarRelayStore(
    private val context: Context,
    private val accountName: String,
    private val calendarId: Long,
) : RelayStore<RemoteEvent> {
    /**
     * Calendar soft-deletes at the platform layer like contacts (see
     * `ContactRelayStore`): a missing id without a mark is never a local
     * delete.
     */
    override val listLocalOk: Boolean
        get() = CalendarSink.lastListLocalOk

    override suspend fun listLocal(): List<RemoteEvent> =
        CalendarSink.listLocal(context, calendarId)

    override suspend fun upsertLocal(items: List<RemoteEvent>) {
        for (item in items) CalendarSink.upsertEvent(context, accountName, calendarId, item)
    }

    override suspend fun deleteLocal(items: List<RemoteEvent>) {
        for (item in items) CalendarSink.deleteEvent(context, accountName, calendarId, item.uid)
    }

    override suspend fun loadBaseline(): Map<String, RelaySnapshot> =
        VaultBaseline.load(context, "$accountName:$calendarId", CalendarKind.kind)

    override suspend fun saveSnapshots(snapshots: List<RelaySnapshot>) {
        VaultBaseline.save(context, "$accountName:$calendarId", CalendarKind.kind, snapshots)
    }

    override suspend fun removeSnapshots(syncIds: List<String>) {
        VaultBaseline.remove(context, "$accountName:$calendarId", CalendarKind.kind, syncIds)
    }

    override fun parseRemote(json: String): RemoteEvent? =
        CalendarKind.fromJson(json)?.takeIf { it.uid.isNotBlank() }

    override fun serializeForRemote(entity: RemoteEvent): String =
        CalendarKind.toJson(entity)
}

class PasswordRelayStore(
    private val context: Context,
    private val vaultId: String,
) : RelayStore<RemotePassword> {
    /**
     * Passwords hard-delete locally: rows vanish with only the app-side
     * `SyncSnapshot` remembering them. A healthy-read absence is therefore a
     * genuine local delete, and the merge propagates a relay tombstone for it.
     * Read health is enforced by `listLocalOk` (plus `listLocal` throwing):
     * a provider failure aborts the cycle instead of merging a partial view.
     */
    override val listLocalOk: Boolean
        get() = PasswordSink.lastListLocalOk

    override suspend fun listLocal(): List<RemotePassword> =
        PasswordSink.listLocalOrThrow(context, vaultId)

    override suspend fun upsertLocal(items: List<RemotePassword>) {
        PasswordSink.upsertAll(context, vaultId, items)
    }

    override suspend fun deleteLocal(items: List<RemotePassword>) {
        // Passwords hard-delete locally: a merge "delete" drops the row, and
        // the tombstone survives relay-side until both sides acknowledge it.
        PasswordSink.deleteAll(context, vaultId, items.map { it.uid to it.isPasskey })
    }

    override suspend fun loadBaseline(): Map<String, RelaySnapshot> =
        VaultBaseline.load(context, vaultId, PasswordKind.kind)

    override suspend fun saveSnapshots(snapshots: List<RelaySnapshot>) {
        VaultBaseline.save(context, vaultId, PasswordKind.kind, snapshots)
    }

    override suspend fun removeSnapshots(syncIds: List<String>) {
        VaultBaseline.remove(context, vaultId, PasswordKind.kind, syncIds)
    }

    override fun parseRemote(json: String): RemotePassword? =
        PasswordKind.fromJson(json)?.takeIf { it.uid.isNotBlank() }

    override fun serializeForRemote(entity: RemotePassword): String =
        PasswordKind.toJson(entity)
}

class NoteRelayStore(
    private val context: Context,
    private val vaultId: String,
) : RelayStore<RemoteNote> {
    /**
     * Notes soft-delete (tombstone rows survive in `listLocal`), so a missing
     * id without a mark is never a local delete (see
     * `EntityKind.localDeletesAreAuthoritative`). Read health is still
     * enforced via `listLocalOk`.
     */
    override val listLocalOk: Boolean
        get() = NoteSink.lastListLocalOk

    override suspend fun listLocal(): List<RemoteNote> =
        NoteSink.listLocal(context, vaultId)

    override suspend fun upsertLocal(items: List<RemoteNote>) {
        // Notes soft-delete: tombstones are real rows, so upsert persists them
        // and only fully-acknowledged ids are ever hard-removed.
        NoteSink.upsertAll(context, vaultId, items)
    }

    override suspend fun deleteLocal(items: List<RemoteNote>) {
        NoteSink.deleteAll(context, vaultId, items.map { it.uid })
    }

    override suspend fun loadBaseline(): Map<String, RelaySnapshot> =
        VaultBaseline.load(context, vaultId, NoteKind.kind)

    override suspend fun saveSnapshots(snapshots: List<RelaySnapshot>) {
        VaultBaseline.save(context, vaultId, NoteKind.kind, snapshots)
    }

    override suspend fun removeSnapshots(syncIds: List<String>) {
        VaultBaseline.remove(context, vaultId, NoteKind.kind, syncIds)
    }

    override fun parseRemote(json: String): RemoteNote? =
        NoteKind.fromJson(json)?.takeIf { it.uid.isNotBlank() }

    override fun serializeForRemote(entity: RemoteNote): String =
        NoteKind.toJson(entity)
}

class TodoRelayStore(
    private val context: Context,
    private val vaultId: String,
) : RelayStore<RemoteTodo> {
    /**
     * Todos soft-delete like notes (see `NoteRelayStore`): a missing id
     * without a mark is never a local delete.
     */
    override val listLocalOk: Boolean
        get() = TodoIpcSink.lastListLocalOk

    override suspend fun listLocal(): List<RemoteTodo> =
        TodoIpcSink.listLocal(context, vaultId)

    override suspend fun upsertLocal(items: List<RemoteTodo>) {
        // Todos soft-delete like notes: tombstones persist as rows.
        TodoIpcSink.upsertAll(context, vaultId, items)
    }

    override suspend fun deleteLocal(items: List<RemoteTodo>) {
        TodoIpcSink.deleteAll(context, vaultId, items.map { it.uid })
    }

    override suspend fun loadBaseline(): Map<String, RelaySnapshot> =
        VaultBaseline.load(context, vaultId, TodoKind.kind)

    override suspend fun saveSnapshots(snapshots: List<RelaySnapshot>) {
        VaultBaseline.save(context, vaultId, TodoKind.kind, snapshots)
    }

    override suspend fun removeSnapshots(syncIds: List<String>) {
        VaultBaseline.remove(context, vaultId, TodoKind.kind, syncIds)
    }

    override fun parseRemote(json: String): RemoteTodo? =
        TodoKind.fromJson(json)?.takeIf { it.uid.isNotBlank() }

    override fun serializeForRemote(entity: RemoteTodo): String =
        TodoKind.toJson(entity)
}

class TodoListRelayStore(
    private val context: Context,
    private val vaultId: String,
) : RelayStore<RemoteTodoList> {
    override val listLocalOk: Boolean
        get() = TodoListIpcSink.lastListLocalOk

    override suspend fun listLocal(): List<RemoteTodoList> =
        TodoListIpcSink.listLocal(context, vaultId)

    override suspend fun upsertLocal(items: List<RemoteTodoList>) {
        TodoListIpcSink.upsertAll(context, vaultId, items)
    }

    override suspend fun deleteLocal(items: List<RemoteTodoList>) {
        TodoListIpcSink.deleteAll(context, vaultId, items.map { it.uid })
    }

    override suspend fun loadBaseline(): Map<String, RelaySnapshot> =
        VaultBaseline.load(context, vaultId, TodoListKind.kind)

    override suspend fun saveSnapshots(snapshots: List<RelaySnapshot>) {
        VaultBaseline.save(context, vaultId, TodoListKind.kind, snapshots)
    }

    override suspend fun removeSnapshots(syncIds: List<String>) {
        VaultBaseline.remove(context, vaultId, TodoListKind.kind, syncIds)
    }

    override fun parseRemote(json: String): RemoteTodoList? =
        TodoListKind.fromJson(json)?.takeIf { it.uid.isNotBlank() }

    override fun serializeForRemote(entity: RemoteTodoList): String =
        TodoListKind.toJson(entity)
}
