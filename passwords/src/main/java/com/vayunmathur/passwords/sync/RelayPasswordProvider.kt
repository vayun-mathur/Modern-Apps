package com.vayunmathur.passwords.sync

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.core.net.toUri
import android.os.Binder
import android.os.Process
import android.util.Base64
import com.vayunmathur.library.log.Log
import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.Password
import com.vayunmathur.passwords.data.PasswordRepository
import com.vayunmathur.passwords.data.SyncSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Relay IPC surface for the passwords vault, consumed by the everysync app's
 * PasswordSink (same process signature, different app).
 *
 * Exposes `content://com.vayunmathur.passwords.relay/vault/<id>/passwords`,
 * `.../passkeys` and `.../snapshots`. The vault id segment is accepted but
 * ignored: this app holds a single vault. Every row carries the relay columns
 * Every row carries the relay columns `syncId, updatedAt, deleted,
 * payloadJson, contentHash`.
 *
 * `payloadJson` is [RelayPasswordJson] — a field-for-field mirror of the relay
 * envelope (`RemotePassword` in everysync): the exact bytes the relay log
 * carries and the merge driver hashes. Passwords and passkeys ride as one
 * shape, told apart by `isPasskey`. This provider must not depend on
 * everysync, so the shape is duplicated here; keep the field names in sync
 * with `RemotePassword` (both sides use `ignoreUnknownKeys`, but a renamed
 * key would silently drop data). `contentHash` is the merge-compatible
 * canonical hash ([relayContentHash]), so LWW tiebreaks agree on both sides.
 *
 * Ops accept both the base table URIs and the op-suffixed URIs
 * (`.../passwords/querySince?since=<ms>`, `.../passwords/upsertRaw`,
 * `.../passwords/deleteByVault`, same for passkeys/snapshots):
 *  - `querySince`: rows with `updatedAt` past the `since` query parameter
 *    (falling back to `selectionArgs[0]`).
 *  - `upsertRaw`: insert/update preserving the pulled `updatedAt`. Accepts
 *    the envelope shape only ([RelayPasswordJson] — the exact relay-log
 *    bytes, with `uid` identity). Non-envelope payloads are rejected (null).
 *    A `deleted` envelope hard-deletes the row instead (snapshot retained).
 *  - single delete (`syncId = ?` selection): hard-deletes the row. The
 *    [SyncSnapshot] row is intentionally retained so the relay merge driver
 *    can tell "deleted since baseline" apart from "never seen" (baseline
 *    present + row absent = deleted).
 *  - `deleteByVault` (or selection-less delete): purge — drops the whole
 *    table *and* its snapshots.
 *
 * The kdbx sync (KdbxSyncEngine/KdbxSyncSettings/KdbxSyncWorker/SAF) is untouched;
 * this provider only reads/writes the same Room tables it does.
 */
class RelayPasswordProvider : ContentProvider() {

    /** Shared constants and helpers for RelayPasswordProvider. */
    companion object {
        const val AUTHORITY = "com.vayunmathur.passwords.relay"
        const val PERMISSION = "com.vayunmathur.passwords.permission.RELAY"

        const val COL_SYNC_ID = "syncId"
        const val COL_UPDATED_AT = "updatedAt"
        const val COL_DELETED = "deleted"
        const val COL_PAYLOAD_JSON = "payloadJson"
        const val COL_CONTENT_HASH = "contentHash"

        val COLUMNS = arrayOf(COL_SYNC_ID, COL_UPDATED_AT, COL_DELETED, COL_PAYLOAD_JSON, COL_CONTENT_HASH)

        private const val PARAM_SINCE = "since"

        private const val CODE_PASSWORDS = 1
        private const val CODE_PASSKEYS = 2
        private const val CODE_SNAPSHOTS = 3
        private const val CODE_PASSWORDS_QUERY = 11
        private const val CODE_PASSKEYS_QUERY = 12
        private const val CODE_PASSWORDS_UPSERT = 21
        private const val CODE_PASSKEYS_UPSERT = 22
        private const val CODE_SNAPSHOTS_UPSERT = 23
        private const val CODE_PASSWORDS_PURGE = 31
        private const val CODE_PASSKEYS_PURGE = 32
        private const val CODE_SNAPSHOTS_PURGE = 33

        private const val TAG = "RelayPasswordProvider"

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "vault/*/passwords", CODE_PASSWORDS)
            addURI(AUTHORITY, "vault/*/passkeys", CODE_PASSKEYS)
            addURI(AUTHORITY, "vault/*/snapshots", CODE_SNAPSHOTS)
            addURI(AUTHORITY, "vault/*/passwords/querySince", CODE_PASSWORDS_QUERY)
            addURI(AUTHORITY, "vault/*/passkeys/querySince", CODE_PASSKEYS_QUERY)
            addURI(AUTHORITY, "vault/*/passwords/upsertRaw", CODE_PASSWORDS_UPSERT)
            addURI(AUTHORITY, "vault/*/passkeys/upsertRaw", CODE_PASSKEYS_UPSERT)
            addURI(AUTHORITY, "vault/*/snapshots/upsertRaw", CODE_SNAPSHOTS_UPSERT)
            addURI(AUTHORITY, "vault/*/passwords/deleteByVault", CODE_PASSWORDS_PURGE)
            addURI(AUTHORITY, "vault/*/passkeys/deleteByVault", CODE_PASSKEYS_PURGE)
            addURI(AUTHORITY, "vault/*/snapshots/deleteByVault", CODE_SNAPSHOTS_PURGE)
        }

        private val json = Json { ignoreUnknownKeys = true }

        fun passwordsUri(vaultId: String): Uri =
            "content://$AUTHORITY/vault/$vaultId/passwords".toUri()

        fun passkeysUri(vaultId: String): Uri =
            "content://$AUTHORITY/vault/$vaultId/passkeys".toUri()

        fun snapshotsUri(vaultId: String): Uri =
            "content://$AUTHORITY/vault/$vaultId/snapshots".toUri()
    }

    private fun repo(): PasswordRepository {
        val ctx = context ?: error("RelayPasswordProvider context null")
        return PasswordRepository.get(ctx)
    }

    override fun onCreate(): Boolean = true

    // The signature permission only proves "signed by the Modern Apps key", which
    // every app in the repo is. Narrow that to the everysync package as well.
    private fun enforceRelayCaller() {
        if (Binder.getCallingUid() == Process.myUid()) return
        val caller = callingPackage
        if (caller != "com.vayunmathur.everysync") {
            throw SecurityException("RelayPasswordProvider: caller $caller is not everysync")
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        enforceRelayCaller()
        // querySince: `?since=<ms>` query parameter first, selectionArgs[0] as fallback.
        val since = uri.getQueryParameter(PARAM_SINCE)?.toLongOrNull()
            ?: selectionArgs?.firstOrNull()?.toLongOrNull()
            ?: 0L
        return runBlocking(Dispatchers.IO) {
            try {
                when (uriMatcher.match(uri)) {
                    CODE_PASSWORDS, CODE_PASSWORDS_QUERY ->
                        cursorFromPasswords(repo().getPasswordsUpdatedSince(since))
                    CODE_PASSKEYS, CODE_PASSKEYS_QUERY ->
                        cursorFromPasskeys(repo().getPasskeysUpdatedSince(since))
                    CODE_SNAPSHOTS -> cursorFromSnapshots(repo().snapshots.getAll())
                    else -> null
                }
            } catch (e: IllegalArgumentException) {
                Log.error(TAG, "query failed $uri", e)
                null
            } catch (e: android.database.SQLException) {
                Log.error(TAG, "query failed $uri", e)
                null
            }
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        enforceRelayCaller()
        val v = values ?: return null
        return runBlocking(Dispatchers.IO) {
            try {
                when (uriMatcher.match(uri)) {
                    CODE_PASSWORDS, CODE_PASSWORDS_UPSERT,
                    CODE_PASSKEYS, CODE_PASSKEYS_UPSERT -> applyRelayJson(v)
                    CODE_SNAPSHOTS, CODE_SNAPSHOTS_UPSERT -> upsertSnapshot(v)
                    else -> null
                }?.let { Uri.withAppendedPath(uri, it) }
            } catch (e: IllegalArgumentException) {
                Log.error(TAG, "insert failed $uri", e)
                null
            } catch (e: android.database.SQLException) {
                Log.error(TAG, "insert failed $uri", e)
                null
            }
        }
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        enforceRelayCaller()
        val v = values ?: return 0
        return runBlocking(Dispatchers.IO) {
            try {
                when (uriMatcher.match(uri)) {
                    CODE_PASSWORDS, CODE_PASSWORDS_UPSERT,
                    CODE_PASSKEYS, CODE_PASSKEYS_UPSERT ->
                        if (applyRelayJson(v) != null) 1 else 0
                    CODE_SNAPSHOTS, CODE_SNAPSHOTS_UPSERT ->
                        if (upsertSnapshot(v) != null) 1 else 0
                    else -> 0
                }
            } catch (e: IllegalArgumentException) {
                Log.error(TAG, "update failed $uri", e)
                0
            } catch (e: android.database.SQLException) {
                Log.error(TAG, "update failed $uri", e)
                0
            }
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        enforceRelayCaller()
        return runBlocking(Dispatchers.IO) {
            try {
                val repo = repo()
                deleteMatched(repo, uriMatcher.match(uri), selection, selectionArgs)
            } catch (e: IllegalArgumentException) {
                Log.error(TAG, "delete failed $uri", e)
                0
            } catch (e: android.database.SQLException) {
                Log.error(TAG, "delete failed $uri", e)
                0
            }
        }
    }


    /** Delete dispatch for the matched provider code. */
    private suspend fun deleteMatched(
        repo: PasswordRepository,
        code: Int,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = when (code) {
        CODE_PASSWORDS_PURGE -> {
            repo.deleteAllPasswords()
            repo.snapshots.deleteByKind(SyncSnapshot.KIND_PASSWORD)
            0
        }
        CODE_PASSKEYS_PURGE -> {
            repo.deleteAllPasskeys()
            repo.snapshots.deleteByKind(SyncSnapshot.KIND_PASSKEY)
            0
        }
        CODE_SNAPSHOTS_PURGE -> {
            repo.snapshots.deleteAll()
            0
        }
        // Single delete (`syncId = ?`): hard-delete the row but keep
        // its snapshot — the relay merge reads that as a deletion.
        // Selection-less base delete is the legacy purge spelling.
        CODE_PASSWORDS -> deleteBySyncIdOrPurge(selection, selectionArgs) {
            repo.deletePasswordBySyncId(it)
        } ?: run {
            repo.deleteAllPasswords()
            repo.snapshots.deleteByKind(SyncSnapshot.KIND_PASSWORD)
            0
        }
        CODE_PASSKEYS -> deleteBySyncIdOrPurge(selection, selectionArgs) {
            repo.deletePasskeyBySyncId(it)
        } ?: run {
            repo.deleteAllPasskeys()
            repo.snapshots.deleteByKind(SyncSnapshot.KIND_PASSKEY)
            0
        }
        CODE_SNAPSHOTS -> {
            val syncId = selectionArgs?.firstOrNull()
            if (syncId != null) {
                repo.snapshots.deleteBySyncId(syncId)
                1
            } else {
                repo.snapshots.deleteAll()
                0
            }
        }
        else -> 0
    }

    override fun getType(uri: Uri): String? = when (uriMatcher.match(uri)) {
        CODE_PASSWORDS, CODE_PASSWORDS_QUERY -> "vnd.android.cursor.dir/vnd.com.vayunmathur.passwords.password"
        CODE_PASSKEYS, CODE_PASSKEYS_QUERY -> "vnd.android.cursor.dir/vnd.com.vayunmathur.passwords.passkey"
        CODE_SNAPSHOTS -> "vnd.android.cursor.dir/vnd.com.vayunmathur.passwords.snapshot"
        else -> null
    }

    /**
     * Applies one pulled record. The only accepted shape is the envelope
     * ([RelayPasswordJson] — the exact relay-log bytes, with `uid` identity);
     * anything else is rejected as corrupt/foreign. A `deleted` envelope
     * hard-deletes the row (snapshot retained — see [delete]). Rebinds onto
     * the existing row id so a pull updates in place instead of duplicating.
     */
    private suspend fun applyRelayJson(v: ContentValues): String? {
        val payload = v.getAsString(COL_PAYLOAD_JSON) ?: return null
        val repo = repo()
        // Envelope first: it is what the sink sends on upsert.
        // Serializer overloads (not the inline reified ones) dodge the
        // decodeFromString overload ambiguity on this Kotlin version.
        val remote: RelayPasswordJson =
            runCatching { json.decodeFromString(RelayPasswordJson.serializer(), payload) }.getOrNull()
                ?: return null
        if (remote.uid.isBlank()) return null
        if (remote.isPasskey) {
            val existing = repo.getPasskeyBySyncId(remote.uid)
            if (remote.deleted) {
                if (existing != null) repo.deletePasskey(existing)
                return remote.uid
            }
            repo.upsertPasskeyRaw(remote.toPasskey(existing?.id ?: 0L))
        } else {
            val existing = repo.getPasswordBySyncId(remote.uid)
            if (remote.deleted) {
                if (existing != null) repo.deletePassword(existing)
                return remote.uid
            }
            repo.upsertPasswordRaw(remote.toPassword(existing?.id ?: 0L))
        }
        return remote.uid
    }

    private suspend fun upsertSnapshot(v: ContentValues): String? {
        val syncId = v.getAsString(COL_SYNC_ID) ?: return null
        repo().snapshots.upsert(
            SyncSnapshot(
                syncId = syncId,
                kind = v.getAsString("kind").orEmpty(),
                contentHash = v.getAsString(COL_CONTENT_HASH).orEmpty(),
                localUpdatedAt = v.getAsLong(COL_UPDATED_AT) ?: 0L,
                remoteModified = v.getAsLong("remoteModified") ?: 0L,
                lastSyncedAt = System.currentTimeMillis(),
            ),
        )
        return syncId
    }

    private suspend fun deleteBySyncIdOrPurge(
        selection: String?,
        selectionArgs: Array<out String>?,
        deleteOne: suspend (String) -> Int,
    ): Int? {
        val syncId = selectionArgs?.firstOrNull()
        return if (selection != null && syncId != null) {
            deleteOne(syncId)
        } else {
            null
        }
    }

    private fun cursorFromPasswords(passwords: List<Password>): MatrixCursor {
        val c = MatrixCursor(COLUMNS)
        for (p in passwords) {
            val relay = p.toRelayJson()
            val payload = json.encodeToString(relay)
            c.addRow(arrayOf<Any?>(p.syncId, p.updatedAt, 0, payload, relayContentHash(relay)))
        }
        return c
    }

    private fun cursorFromPasskeys(passkeys: List<Passkey>): MatrixCursor {
        val c = MatrixCursor(COLUMNS)
        for (p in passkeys) {
            val relay = p.toRelayJson()
            val payload = json.encodeToString(relay)
            c.addRow(arrayOf<Any?>(p.syncId, p.updatedAt, 0, payload, relayContentHash(relay)))
        }
        return c
    }

    private fun cursorFromSnapshots(snapshots: List<SyncSnapshot>): MatrixCursor {
        val c = MatrixCursor(COLUMNS)
        for (s in snapshots) {
            c.addRow(arrayOf<Any?>(s.syncId, s.localUpdatedAt, 0, "", s.contentHash))
        }
        return c
    }

    private fun sha256Hex(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /**
     * Merge-compatible content hash: canonical envelope (`uid`, `updatedAt`,
     * `deleted` stripped) plus the deleted flag, SHA-256 hexed — the same
     * bytes the relay merge driver hashes, so LWW tiebreaks agree on both
     * sides.
     */
    private fun relayContentHash(e: RelayPasswordJson): String {
        val canonical = json.encodeToString(
            e.copy(uid = "", updatedAt = 0L, deleted = false),
        ) + "\u0000deleted=${e.deleted}"
        return sha256Hex(canonical)
    }
}

/**
 * Native passkey row carrier: [Passkey] is not `@Serializable` (its
 * `privateKeyBytes` uses custom equality), so it crosses IPC as this plain
 * carrier with base64 bytes. Field names mirror the sink-side carrier
 * (`PasskeyPayload` in everysync's PasswordSink) so payloads stay mutually
 * intelligible in both directions — this is the LIVE passkey payload shape,
 * emitted by [RelayPasswordProvider.cursorFromPasskeys] and parsed back in
 * [RelayPasswordProvider.applyRelayJson].
 */
@Serializable
internal data class PasswordPasskeyPayload(
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
) {
    companion object {
        fun fromPasskey(p: Passkey): PasswordPasskeyPayload = PasswordPasskeyPayload(
            id = p.id,
            rpId = p.rpId,
            rpName = p.rpName,
            credentialId = p.credentialId,
            userId = p.userId,
            userName = p.userName,
            userDisplayName = p.userDisplayName,
            privateKeyBase64 = Base64.encodeToString(p.privateKeyBytes, Base64.NO_WRAP),
            creationTime = p.creationTime,
            lastUsedTime = p.lastUsedTime,
            signCount = p.signCount,
            linkedPasswordSyncId = p.linkedPasswordSyncId,
            syncId = p.syncId,
            updatedAt = p.updatedAt,
        )
    }

    fun toPasskey(): Passkey = Passkey(
        id = id,
        rpId = rpId,
        rpName = rpName,
        credentialId = credentialId,
        userId = userId,
        userName = userName,
        userDisplayName = userDisplayName,
        privateKeyBytes = runCatching {
            Base64.decode(privateKeyBase64, Base64.NO_WRAP)
        }.getOrDefault(ByteArray(0)),
        creationTime = creationTime,
        lastUsedTime = lastUsedTime,
        signCount = signCount,
        linkedPasswordSyncId = linkedPasswordSyncId,
        syncId = syncId,
        updatedAt = updatedAt,
    )
}

/**
 * Relay envelope for one password or passkey record. Field-for-field mirror of
 * everysync's `RemotePassword` (the bytes the relay log carries): passwords use
 * the name/username/email/password/note/totp/websites half, passkeys
 * ([isPasskey]) use the rp/credential half.
 *
 * passwords/ must not depend on everysync, so the shape is duplicated here.
 * Both sides decode with `ignoreUnknownKeys`, but keep the names in sync —
 * a renamed key here would silently drop data on the relay side.
 */
@Serializable
internal data class RelayPasswordJson(
    /** Relay sync identity (opaque 32-char id); maps to [Password.syncId]/[Passkey.syncId]. */
    val uid: String = "",
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

// Envelope mappers: native <-> relay-log bytes. The passkey bytes cross as
// base64 ([Passkey] itself is not @Serializable); see PasswordPasskeyPayload
// below, which shares the carrier field names with the sink side.
internal fun Password.toRelayJson(): RelayPasswordJson = RelayPasswordJson(
    uid = syncId,
    name = name,
    username = username,
    email = email,
    password = password,
    note = note,
    totpSecret = totpSecret,
    websites = websites,
    updatedAt = updatedAt,
)

// Envelope mapper (see above).
internal fun Passkey.toRelayJson(): RelayPasswordJson = RelayPasswordJson(
    uid = syncId,
    isPasskey = true,
    rpId = rpId,
    rpName = rpName,
    credentialId = credentialId,
    userId = userId,
    userName = userName,
    userDisplayName = userDisplayName,
    privateKeyB64 = Base64.encodeToString(privateKeyBytes, Base64.NO_WRAP),
    creationTime = creationTime,
    lastUsedTime = lastUsedTime,
    signCount = signCount,
    linkedPasswordSyncId = linkedPasswordSyncId,
    updatedAt = updatedAt,
)

internal fun RelayPasswordJson.toPassword(id: Long): Password = Password(
    id = id,
    name = name,
    username = username,
    email = email,
    password = password,
    note = note,
    totpSecret = totpSecret,
    websites = websites,
    syncId = uid,
    updatedAt = updatedAt,
)

internal fun RelayPasswordJson.toPasskey(id: Long): Passkey = Passkey(
    id = id,
    rpId = rpId,
    rpName = rpName,
    credentialId = credentialId,
    userId = userId,
    userName = userName,
    userDisplayName = userDisplayName,
    privateKeyBytes = runCatching { Base64.decode(privateKeyB64, Base64.NO_WRAP) }
        .getOrDefault(ByteArray(0)),
    creationTime = creationTime,
    lastUsedTime = lastUsedTime,
    signCount = signCount,
    linkedPasswordSyncId = linkedPasswordSyncId,
    syncId = uid,
    updatedAt = updatedAt,
)
