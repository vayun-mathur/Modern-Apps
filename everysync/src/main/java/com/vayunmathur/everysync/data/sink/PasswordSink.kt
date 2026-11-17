package com.vayunmathur.everysync.data.sink

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import com.vayunmathur.everysync.data.RemotePassword
import com.vayunmathur.everysync.sync.LocalReadException
import com.vayunmathur.everysync.sync.adapters.PasswordKind
import com.vayunmathur.library.log.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A locally-changed password/passkey row detected via the app provider's timestamp watermark. */
data class LocalPasswordChange(
    val syncId: String,
    val isPasskey: Boolean,
    val updatedAt: Long,
    val deleted: Boolean,
    val password: RemotePassword?,
)

/**
 * Passkey payload carrier for the native row JSON. The passwords app's
 * `Passkey` is not `@Serializable`, so it crosses IPC as this plain carrier
 * with base64 bytes — field names mirror the provider side (`syncId`, and
 * `privateKeyBase64` for the key bytes).
 */
@Serializable
internal data class PasskeyPayload(
    val id: Long = 0,
    val rpId: String = "",
    val rpName: String = "",
    val credentialId: String = "",
    val userId: String = "",
    val userName: String = "",
    val userDisplayName: String = "",
    val privateKeyBase64: String = "",
    val creationTime: Long = 0,
    val lastUsedTime: Long = 0,
    val signCount: Int = 0,
    val linkedPasswordSyncId: String? = null,
    val syncId: String = "",
    val updatedAt: Long = 0,
)

/**
 * Reads/writes password + passkey records through the passwords app's relay
 * ContentProvider (owned by task 3 — the `passwords/` app). The contract
 * mirrors the provider exactly:
 *
 * - authority: `com.vayunmathur.passwords.relay` (signature-protected)
 * - URIs: base table URIs (`.../vault/<id>/passwords`, `.../passkeys`,
 *   `.../snapshots`) plus the op-suffixed forms (`.../passwords/querySince`,
 *   `.../passwords/upsertRaw`, `.../passwords/deleteByVault`, same for
 *   passkeys/snapshots). The vault id segment is accepted but ignored: the
 *   app holds a single vault.
 * - query: `?since=<ms>` query parameter first, `selectionArgs[0]` as
 *   fallback, for deltas; no bound lists the current full state.
 * - `payloadJson` is the **envelope JSON** ([RemotePassword] bytes via
 *   [PasswordKind.toJson] — the exact relay-log bytes, with `uid` identity).
 *   The provider emits envelopes and accepts envelopes primarily; native row
 *   shapes are accepted as fallback for older sinks. Reads accept both via
 *   [fromPayload] (envelope first, native fallback) so no row is ever
 *   silently dropped.
 * - upsert: `insert`/`update` with `ContentValues(payloadJson)` carrying the
 *   **envelope JSON**, preserving the pulled timestamp and relay identity
 *   (`uid` -> `syncId`). A `deleted` envelope hard-deletes the row instead
 *   (snapshot retained app-side so the merge reads it as a deletion).
 * - delete: a `syncId = ?` selection hard-deletes one row (snapshot kept);
 *   `deleteByVault` (or selection-less base delete) purges the whole table
 *   *and* its snapshots. NOTE: this path is merge-EXTERNAL (user toggling sync
 *   off, explicit purge). Merge-driven deletes ALWAYS arrive as `deleted`
 *   tombstone envelopes via upsert, never as raw deletes — raw deletes carry
 *   no proof of intent and the merge must never infer deletion from absence.
 * - columns: `syncId`, `updatedAt`, `deleted`, `payloadJson`, `contentHash`
 *   (payload SHA-256 hex; the merge driver recomputes canonical hashes
 *   itself via [PasswordKind] and ignores this column on read).
 * - deletes: passwords/passkeys hard-delete locally (`deleted` is always 0 in
 *   query results); tombstones live only on the relay side.
 *
 * `RemotePassword.uid` is the relay sync identity; passkeys (`isPasskey`)
 * ride the same `passwords:<vaultId>` channel and are told apart by the flag
 * (see `PasswordKind`).
 */
object PasswordSink {
    private const val TAG = "PasswordSink"

    /**
     * Health of the last [listLocalOrThrow]/[listLocal] read: false when any
     * table query failed or returned a null cursor. Consulted by
     * `PasswordRelayStore.listLocalOk` so the driver can abort before merging.
     */
    @Volatile var lastListLocalOk: Boolean = true
        private set

    const val AUTHORITY = "com.vayunmathur.passwords.relay"
    const val PATH_PASSWORDS = "passwords"
    const val PATH_PASSKEYS = "passkeys"
    const val PATH_SNAPSHOTS = "snapshots"
    const val OP_QUERY_SINCE = "querySince"
    const val OP_UPSERT_RAW = "upsertRaw"
    const val OP_DELETE_BY_VAULT = "deleteByVault"
    const val COL_SYNC_ID = "syncId"
    const val COL_UPDATED_AT = "updatedAt"
    const val COL_DELETED = "deleted"
    const val COL_PAYLOAD_JSON = "payloadJson"
    const val COL_CONTENT_HASH = "contentHash"
    const val PARAM_SINCE = "since"

    private val json = Json { ignoreUnknownKeys = true }

    fun passwordsUri(vaultId: String): Uri =
        "content://$AUTHORITY/vault/$vaultId/$PATH_PASSWORDS".toUri()

    fun passkeysUri(vaultId: String): Uri =
        "content://$AUTHORITY/vault/$vaultId/$PATH_PASSKEYS".toUri()

    fun snapshotsUri(vaultId: String): Uri =
        "content://$AUTHORITY/vault/$vaultId/$PATH_SNAPSHOTS".toUri()

    fun queryUri(vaultId: String, isPasskey: Boolean): Uri =
        tableUri(vaultId, isPasskey).buildUpon()
            .appendPath(OP_QUERY_SINCE).build()

    fun upsertUri(vaultId: String, isPasskey: Boolean): Uri =
        tableUri(vaultId, isPasskey).buildUpon()
            .appendPath(OP_UPSERT_RAW).build()

    fun purgeUri(vaultId: String, table: String): Uri =
        "content://$AUTHORITY/vault/$vaultId/$table".toUri()
            .buildUpon().appendPath(OP_DELETE_BY_VAULT).build()

    private fun tableUri(vaultId: String, isPasskey: Boolean): Uri =
        if (isPasskey) passkeysUri(vaultId) else passwordsUri(vaultId)

    /** Relay envelope for [password] (adapter bytes — what the relay log carries). */
    fun toRelayJson(password: RemotePassword): String = PasswordKind.toJson(password)

    /** Native row JSON for [password] (what the provider persists verbatim). */
    fun toNativeJson(password: RemotePassword): String =
        if (password.isPasskey) {
            json.encodeToString(
                PasskeyPayload(
                    rpId = password.rpId,
                    rpName = password.rpName,
                    credentialId = password.credentialId,
                    userId = password.userId,
                    userName = password.userName,
                    userDisplayName = password.userDisplayName,
                    privateKeyBase64 = password.privateKeyB64,
                    creationTime = password.creationTime,
                    lastUsedTime = password.lastUsedTime,
                    signCount = password.signCount,
                    linkedPasswordSyncId = password.linkedPasswordSyncId,
                    syncId = password.uid,
                    updatedAt = password.updatedAt,
                ),
            )
        } else {
            json.encodeToString(
                NativePassword(
                    name = password.name,
                    username = password.username,
                    email = password.email,
                    password = password.password,
                    note = password.note,
                    totpSecret = password.totpSecret,
                    websites = password.websites,
                    syncId = password.uid,
                    updatedAt = password.updatedAt,
                ),
            )
        }

    /**
     * Parses a `payloadJson` value back into relay form. Accepts the envelope
     * shape first (what the provider emits) and the native row shapes as
     * fallback; null skips corrupt/foreign rows.
     */
    fun fromPayload(payload: String, isPasskey: Boolean): RemotePassword? {
        fromRelayJson(payload)?.let { return it }
        return fromNativeJson(payload, isPasskey)
    }

    /** Parses a native row payload; null skips non-native rows. */
    fun fromNativeJson(payload: String, isPasskey: Boolean): RemotePassword? = runCatching {
        if (isPasskey) {
            val p = json.decodeFromString<PasskeyPayload>(payload)
            RemotePassword(
                uid = p.syncId,
                isPasskey = true,
                rpId = p.rpId,
                rpName = p.rpName,
                credentialId = p.credentialId,
                userId = p.userId,
                userName = p.userName,
                userDisplayName = p.userDisplayName,
                privateKeyB64 = p.privateKeyBase64,
                creationTime = p.creationTime,
                lastUsedTime = p.lastUsedTime,
                signCount = p.signCount,
                linkedPasswordSyncId = p.linkedPasswordSyncId,
                updatedAt = p.updatedAt,
            )
        } else {
            val p = json.decodeFromString<NativePassword>(payload)
            RemotePassword(
                uid = p.syncId,
                name = p.name,
                username = p.username,
                email = p.email,
                password = p.password,
                note = p.note,
                totpSecret = p.totpSecret,
                websites = p.websites,
                updatedAt = p.updatedAt,
            )
        }
    }.getOrNull()?.takeIf { it.uid.isNotBlank() }

    /** Parses an envelope payload back; null skips corrupt/foreign rows. */
    fun fromRelayJson(payload: String): RemotePassword? =
        PasswordKind.fromJson(payload)?.takeIf { it.uid.isNotBlank() }

    /** Map of relay syncId → updatedAt currently present locally for this vault. */
    fun localUidToEtag(context: Context, vaultId: String): Map<String, Long?> {
        val result = mutableMapOf<String, Long?>()
        // Full state, no bound: live rows only (passwords hard-delete, so no
        // tombstone filtering needed).
        for (uri in listOf(passwordsUri(vaultId), passkeysUri(vaultId))) {
            try {
                context.contentResolver.query(
                    uri,
                    arrayOf(COL_SYNC_ID, COL_UPDATED_AT),
                    null,
                    null,
                    null,
                )?.use { c -> readEtagRows(c, result) }
            } catch (expected: Exception) {
                Log.error(TAG, "localUidToEtag failed for $uri", expected)
            }
        }
        return result
    }


    /** All local rows in relay form (tombstones never occur locally — hard deletes). */
    fun listLocal(context: Context, vaultId: String): List<RemotePassword> =
        runCatching { listLocalOrThrow(context, vaultId) }.getOrDefault(emptyList())

    /**
     * All local rows in relay form, throwing [LocalReadException] when the
     * provider cannot be read. The merge driver MUST use this variant: a
     * failed read merged as empty would delete every baseline-present relay
     * record.
     */
    fun listLocalOrThrow(context: Context, vaultId: String): List<RemotePassword> {
        val out = mutableListOf<RemotePassword>()
        var failed = false
        for ((uri, isPasskey) in listOf(passwordsUri(vaultId) to false, passkeysUri(vaultId) to true)) {
            try {
                val cursor = context.contentResolver.query(
                    uri,
                    arrayOf(COL_PAYLOAD_JSON),
                    null,
                    null,
                    null,
                ) ?: run {
                    // Null cursor = provider cannot serve (locked DB, crash):
                    // failure, not an empty table.
                    failed = true
                    continue
                }
                cursor.use { c -> out += readPasswordRows(c, isPasskey) }
            } catch (expected: Exception) {
                Log.error(TAG, "listLocal failed for $uri", expected)
                failed = true
            }
        }
        if (failed) {
            lastListLocalOk = false
            throw LocalReadException("password provider read failed for vault $vaultId")
        }
        lastListLocalOk = true
        return out
    }

    private fun readPasswordRows(
        cursor: android.database.Cursor,
        isPasskey: Boolean,
    ): List<RemotePassword> {
        val out = mutableListOf<RemotePassword>()
        val payloadIdx = cursor.getColumnIndex(COL_PAYLOAD_JSON)
        while (cursor.moveToNext()) {
            val payload = if (payloadIdx >= 0) cursor.getStringOrNull(payloadIdx) else null
            if (!payload.isNullOrBlank()) {
                fromPayload(payload, isPasskey)?.let { out += it }
            }
        }
        return out
    }

    /** Idempotent upsert keyed on [RemotePassword.uid]; preserves the relay identity + timestamp. */
    fun upsert(context: Context, vaultId: String, password: RemotePassword) {
        try {
            context.contentResolver.insert(
                upsertUri(vaultId, password.isPasskey),
                ContentValues().apply { put(COL_PAYLOAD_JSON, toRelayJson(password)) },
            )
        } catch (expected: Exception) {
            Log.error(TAG, "upsert failed", expected)
        }
    }

    /** Bulk upsert for merge results; one IPC call per row like the single form. */
    fun upsertAll(context: Context, vaultId: String, passwords: List<RemotePassword>) {
        for (password in passwords) upsert(context, vaultId, password)
    }

    /**
     * Applies a pulled relay tombstone (or a local delete ack) by hard-deleting
     * the row: `syncId = ?` selection deletes exactly one row (snapshot kept
     * app-side so the merge reads it as a deletion).
     */
    fun delete(context: Context, vaultId: String, uid: String, isPasskey: Boolean = false) {
        try {
            context.contentResolver.delete(
                tableUri(vaultId, isPasskey),
                "$COL_SYNC_ID = ?",
                arrayOf(uid),
            )
        } catch (expected: Exception) {
            Log.error(TAG, "delete failed", expected)
        }
    }

    /** Bulk hard-delete for merge garbage collection. */
    fun deleteAll(context: Context, vaultId: String, uids: List<Pair<String, Boolean>>) {
        for ((uid, isPasskey) in uids) delete(context, vaultId, uid, isPasskey)
    }

    /**
     * Removes every password + passkey row (and snapshots) for this vault from
     * the app provider. Used when the user turns password sync off; the vault
     * itself stays, so a later re-enable repopulates it from a fresh pull.
     */
    // Intentional no-op / account-parity parameter: kept so all sinks share one call shape.
    @Suppress("UnusedParameter")
    fun purge(context: Context, accountName: String, vaultId: String) {
        for (table in listOf(PATH_PASSWORDS, PATH_PASSKEYS, PATH_SNAPSHOTS)) {
            try {
                context.contentResolver.delete(purgeUri(vaultId, table), null, null)
            } catch (expected: Exception) {
                Log.error(TAG, "purge failed for $table", expected)
            }
        }
    }

    /** Back-compat overload for account-scoped callers that track a single vault per account. */
    fun purge(context: Context, accountName: String) {
        purge(context, accountName, accountName)
    }

    /**
     * Rows changed locally since [since] (`updatedAt > since`, via `?since=`).
     * Passwords never report tombstones (hard deletes); a delete is observed
     * as "present at baseline, absent now" by the merge.
     */
    fun getLocalChanges(context: Context, vaultId: String, since: Long = 0L): List<LocalPasswordChange> {
        val changes = mutableListOf<LocalPasswordChange>()
        for ((table, isPasskey) in listOf(PATH_PASSWORDS to false, PATH_PASSKEYS to true)) {
            try {
                context.contentResolver.query(
                    queryUri(vaultId, isPasskey).buildUpon()
                        .appendQueryParameter(PARAM_SINCE, since.toString()).build(),
                    arrayOf(COL_SYNC_ID, COL_UPDATED_AT, COL_PAYLOAD_JSON),
                    null,
                    null,
                    null,
                )?.use { c -> readPasswordChangeRows(c, isPasskey, changes) }
            } catch (expected: Exception) {
                Log.error(TAG, "getLocalChanges failed for $table", expected)
            }
        }
        return changes
    }

    /**
     * Marks locally-pushed rows clean after a successful relay append. No-op:
     * rows applied via `upsertRaw` keep their remote `updatedAt`, which sits
     * at or below the push watermark, so fresh pulls never look dirty.
     */
    // Intentional no-op / account-parity parameter: kept so all sinks share one call shape.
    @Suppress("UnusedParameter")
    fun clearDirty(context: Context, vaultId: String, syncId: String) {
        // Timestamp-watermark providers need no explicit clean bit; kept for
        // API parity with ContactsSink/CalendarSink.
    }

    /**
     * Native `Password` JSON shape (mirrors the passwords app's model without
     * depending on its module: `id` is per-device so it is never synced).
     */
    @Serializable
    internal data class NativePassword(
        val name: String = "",
        val username: String = "",
        val email: String = "",
        val password: String = "",
        val note: String = "",
        val totpSecret: String? = null,
        val websites: List<String> = emptyList(),
        val syncId: String = "",
        val updatedAt: Long = 0L,
    )
}

private fun readEtagRows(cursor: android.database.Cursor, result: MutableMap<String, Long?>) {
    val idIdx = cursor.getColumnIndex(PasswordSink.COL_SYNC_ID)
    val tsIdx = cursor.getColumnIndex(PasswordSink.COL_UPDATED_AT)
    while (cursor.moveToNext()) {
        val uid = (if (idIdx >= 0) cursor.getStringOrNull(idIdx) else null) ?: continue
        result[uid] = if (tsIdx >= 0) cursor.getLongOrNull(tsIdx) else null
    }
}

private fun readPasswordChangeRows(
    cursor: android.database.Cursor,
    isPasskey: Boolean,
    changes: MutableList<LocalPasswordChange>,
) {
    val idIdx = cursor.getColumnIndex(PasswordSink.COL_SYNC_ID)
    val tsIdx = cursor.getColumnIndex(PasswordSink.COL_UPDATED_AT)
    val payloadIdx = cursor.getColumnIndex(PasswordSink.COL_PAYLOAD_JSON)
    while (cursor.moveToNext()) {
        val uid = (if (idIdx >= 0) cursor.getStringOrNull(idIdx) else null) ?: continue
        val payload = if (payloadIdx >= 0) cursor.getStringOrNull(payloadIdx) else null
        // Table determines the half; the parsed flag is
        // authoritative when present, table otherwise.
        val parsed = if (payload.isNullOrBlank()) null else PasswordSink.fromPayload(payload, isPasskey)
        changes += LocalPasswordChange(
            syncId = uid,
            isPasskey = parsed?.isPasskey ?: isPasskey,
            updatedAt = if (tsIdx >= 0) cursor.getLong(tsIdx) else 0L,
            deleted = false,
            password = parsed,
        )
    }
}

