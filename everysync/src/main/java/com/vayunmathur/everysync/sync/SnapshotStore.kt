package com.vayunmathur.everysync.sync

import android.content.Context
import com.vayunmathur.everysync.auth.AccountStore
import com.vayunmathur.everysync.data.VaultStore
import com.vayunmathur.everysync.data.sink.CalendarSink
import com.vayunmathur.everysync.data.sink.ContactsSink
import com.vayunmathur.everysync.data.sink.NoteSink
import com.vayunmathur.everysync.data.sink.PasswordSink
import com.vayunmathur.everysync.data.sink.TodoIpcSink
import com.vayunmathur.everysync.provider.SyncState
import com.vayunmathur.everysync.sync.adapters.CalendarKind
import com.vayunmathur.everysync.sync.adapters.ContactKind
import com.vayunmathur.everysync.sync.adapters.NoteKind
import com.vayunmathur.everysync.sync.adapters.PasswordKind
import com.vayunmathur.everysync.sync.adapters.TodoKind
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.util.DataStoreUtils
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Daily encrypted snapshots of every vault type — the last-resort recovery net.
 *
 * Each snapshot is the vault's full local state in relay-envelope form (the
 * exact bytes the merge driver consumes), AES-256-GCM encrypted with a
 * device-local snapshot key (Keystore-backed via [SnapshotKeyStore], same
 * pattern as `TokenStore`), written to `filesDir/snapshots/<vaultId>/<day>.bin`
 * plus `RelayBlobStore` file copies for note images.
 *
 * - One snapshot per day per (vaultId, type), taken by [SnapshotWorker].
 * - 30-day TTL: entries older than [RETENTION_DAYS] are pruned on every write.
 * - Restore replays envelopes through the existing sink `upsertAll` paths as
 *   normal merge input, so conflicts with newer edits resolve by the existing
 *   LWW rules — restore never overwrites by force.
 * - `purgeType` callers must snapshot first (see `E2eeProvider.purgeType`
 *   contract): a sync-off purge is exactly the "everything vanished locally"
 *   shape the merge refuses to infer, so the snapshot is the way back.
 *
 * Never log envelope plaintext, keys, or snapshot contents — only counts and ids.
 */
object SnapshotStore {
    private const val TAG = "SnapshotStore"

    /** Snapshots older than this are pruned on every write. */
    const val RETENTION_DAYS = 30L
    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

    private const val DIR_SNAPSHOTS = "snapshots"
    private const val DIR_BLOBS = "blobs"
    private const val EXT = ".bin"

    private const val KEY_INDEX = "snapshot_index"
    private const val KEY_LAST_TAKEN_PREFIX = "snapshot_last_taken:"

    private val json = Json { ignoreUnknownKeys = true }

    /** One restorable snapshot: day (epoch millis at midnight UTC) + envelope payloads. */
    @Serializable
    data class SnapshotMeta(
        val vaultId: String,
        val dataType: String,
        val dayEpochMs: Long,
        val recordCount: Int,
        val blobCount: Int = 0,
        val takenAtMs: Long = 0L,
    )

    @Serializable
    private data class StoredIndex(val snapshots: List<SnapshotMeta> = emptyList())

    @Serializable
    private data class SnapshotPayload(
        val records: List<String> = emptyList(),
        /** Blob name -> base64 bytes (note images only; contacts/calendar/passwords/todos carry none). */
        val blobs: Map<String, String> = emptyMap(),
    )

    private fun vaultDir(context: Context, vaultId: String): File =
        File(File(context.filesDir, DIR_SNAPSHOTS), vaultId).apply { mkdirs() }

    private fun snapshotFile(context: Context, vaultId: String, dayEpochMs: Long): File =
        File(vaultDir(context, vaultId), "$dayEpochMs$EXT")

    private fun blobDir(context: Context, vaultId: String): File =
        File(File(vaultDir(context, vaultId), DIR_BLOBS), "").apply { mkdirs() }

    private fun dayOf(timestampMs: Long): Long = (timestampMs / MILLIS_PER_DAY) * MILLIS_PER_DAY

    private fun loadIndex(context: Context): StoredIndex {
        val raw = DataStoreUtils.getInstance(context).getString(KEY_INDEX)
        if (raw.isNullOrBlank()) return StoredIndex()
        return runCatching { json.decodeFromString<StoredIndex>(raw) }.getOrDefault(StoredIndex())
    }

    private suspend fun saveIndex(context: Context, index: StoredIndex) {
        DataStoreUtils.getInstance(context).setString(KEY_INDEX, json.encodeToString(index))
    }

    /** All known snapshots, newest first. */
    fun listAll(context: Context): List<SnapshotMeta> =
        loadIndex(context).snapshots.sortedByDescending { it.dayEpochMs }

    /** Snapshots for one vault, newest first. */
    fun listForVault(context: Context, vaultId: String): List<SnapshotMeta> =
        listAll(context).filter { it.vaultId == vaultId }

    /** True when a snapshot was already taken today for this vault. */
    fun takenToday(context: Context, vaultId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val last = DataStoreUtils.getInstance(context)
            .getLong(KEY_LAST_TAKEN_PREFIX + vaultId) ?: 0L
        return dayOf(last) == dayOf(nowMs)
    }

    /**
     * Takes today's snapshot for every known vault (all five types + note
     * blobs). Skips vaults already snapshotted today. Prunes entries older
     * than [RETENTION_DAYS] afterwards. Returns how many snapshots were taken.
     */
    suspend fun snapshotAll(context: Context, nowMs: Long = System.currentTimeMillis()): Int =
        withContext(Dispatchers.IO) {
            var taken = 0
            for (meta in VaultStore.getAll(context)) {
                if (takenToday(context, meta.vaultId, nowMs)) continue
                if (snapshotVault(context, meta.vaultId, meta.dataType, nowMs)) taken++
            }
            pruneExpired(context, nowMs)
            taken
        }

    /**
     * Takes today's snapshot for one vault. Records are the sink's full local
     * state in relay-envelope bytes (tombstones included — a restore must see
     * the same deletes the merge saw). Returns false when the local read is
     * unhealthy (never snapshot a partial view) or the vault has no key.
     */
    suspend fun snapshotVault(
        context: Context,
        vaultId: String,
        dataType: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = withContext(Dispatchers.IO) {
        val day = dayOf(nowMs)
        val (records, healthy) = readRecords(context, vaultId, dataType)
        if (!healthy) {
            Log.status(TAG, "snapshot skipped for $vaultId/$dataType: unhealthy local read")
            return@withContext false
        }
        val blobs = readBlobs(context, vaultId, dataType)
        val payload = SnapshotPayload(records, blobs)
        val plain = json.encodeToString(payload).encodeToByteArray()
        val sealed = SnapshotKeyStore.seal(context, plain) ?: run {
            Log.error(TAG, "snapshot failed for $vaultId/$dataType: keystore unavailable")
            return@withContext false
        }
        runCatching { snapshotFile(context, vaultId, day).writeBytes(sealed) }.getOrNull()
            ?: run {
                Log.error(TAG, "snapshot failed for $vaultId/$dataType: write failed")
                return@withContext false
            }
        // Blob copies ride inside the sealed payload; the blob dir mirrors the
        // live files for inspection-free restore (never read back except restore).
        val index = loadIndex(context)
        val kept = index.snapshots.filterNot { it.vaultId == vaultId && it.dayEpochMs == day } +
            SnapshotMeta(vaultId, dataType, day, records.size, blobs.size, nowMs)
        saveIndex(context, StoredIndex(kept))
        DataStoreUtils.getInstance(context).setLong(KEY_LAST_TAKEN_PREFIX + vaultId, nowMs)
        Log.status(TAG, "snapshot taken for $vaultId/$dataType: ${records.size} records, ${blobs.size} blobs")
        pruneExpired(context, nowMs)
        true
    }

    /**
     * Restores [snapshot] by replaying its envelopes through the sink
     * `upsertAll` paths with pulled timestamps preserved — i.e. as normal
     * merge input. Newer local edits win by LWW where they conflict; nothing
     * is force-overwritten and baselines are left for the next sync cycle to
     * reconcile. Returns the restored record count, or null on failure.
     */
    suspend fun restore(context: Context, snapshot: SnapshotMeta): Int? =
        withContext(Dispatchers.IO) {
            val sealed = runCatching {
                snapshotFile(context, snapshot.vaultId, snapshot.dayEpochMs).readBytes()
            }.getOrNull() ?: return@withContext null
            val plain = SnapshotKeyStore.unseal(context, sealed) ?: return@withContext null
            val payload = runCatching {
                json.decodeFromString<SnapshotPayload>(plain.decodeToString())
            }.getOrNull() ?: return@withContext null
            val count = writeRecords(context, snapshot.vaultId, snapshot.dataType, payload.records)
                ?: return@withContext null
            writeBlobs(context, snapshot.vaultId, snapshot.dataType, payload.blobs)
            Log.status(TAG, "restored ${snapshot.vaultId}/${snapshot.dataType} @${snapshot.dayEpochMs}: $count records")
            count
        }

    /** Drops snapshots (files + index rows) older than [RETENTION_DAYS]. */
    suspend fun pruneExpired(context: Context, nowMs: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) {
            val cutoff = dayOf(nowMs) - RETENTION_DAYS * MILLIS_PER_DAY
            val index = loadIndex(context)
            val (expired, kept) = index.snapshots.partition { it.dayEpochMs < cutoff }
            if (expired.isEmpty()) return@withContext
            for (meta in expired) {
                runCatching { snapshotFile(context, meta.vaultId, meta.dayEpochMs).delete() }
            }
            // Drop blob mirrors for vaults with no snapshots left.
            val liveVaults = kept.map { it.vaultId }.toSet()
            for (meta in expired.filterNot { it.vaultId in liveVaults }) {
                runCatching { blobDir(context, meta.vaultId).deleteRecursively() }
            }
            saveIndex(context, StoredIndex(kept))
            Log.status(TAG, "pruned ${expired.size} expired snapshots")
        }

    // --- Per-type record readers (envelope bytes + health flag) ---

    private fun readRecords(context: Context, vaultId: String, dataType: String): Pair<List<String>, Boolean> {
        return when (dataType) {
            "passwords" -> {
                // Throws on unhealthy read — fail closed, never snapshot partial.
                val records = runCatching {
                    PasswordSink.listLocalOrThrow(context, vaultId)
                        .map { PasswordKind.toJson(it) }
                }.getOrNull()
                if (records == null || !PasswordSink.lastListLocalOk) emptyList<String>() to false
                else records to true
            }
            "notes" -> {
                val records = NoteSink.listLocal(context, vaultId).map { NoteKind.toJson(it) }
                if (!NoteSink.lastListLocalOk) emptyList<String>() to false else records to true
            }
            "todos" -> {
                val records = TodoIpcSink.listLocal(context, vaultId).map { TodoKind.toJson(it) }
                if (!TodoIpcSink.lastListLocalOk) emptyList<String>() to false else records to true
            }
            "contacts" -> {
                // Contacts/calendar rows are account-scoped, not vault-scoped:
                // snapshot the bound account's rows (see accountForVault).
                val account = accountForVault(context, vaultId) ?: return emptyList<String>() to false
                val records = ContactsSink.listLocal(context, account).map { ContactKind.toJson(it) }
                if (!ContactsSink.lastListLocalOk) emptyList<String>() to false else records to true
            }
            "calendar" -> {
                val account = accountForVault(context, vaultId) ?: return emptyList<String>() to false
                val calendarId = CalendarSink.localCalendars(context, account)
                    .firstOrNull() ?: return emptyList<String>() to true
                val records = CalendarSink.listLocal(context, calendarId).map { CalendarKind.toJson(it) }
                if (!CalendarSink.lastListLocalOk) emptyList<String>() to false else records to true
            }
            else -> emptyList<String>() to false
        }
    }

    private fun accountForVault(context: Context, vaultId: String): String? {
        // Reverse the SyncState account->vault binding: scan known vault scopes.
        // The binding key layout is `e2ee_vault_<type>` per account.
        // Fallback: the single account when exactly one exists.
        val accounts = AccountStore.getInstance(context).getAll()
        if (accounts.size == 1) return accounts.single().accountName
        for (account in accounts) {
            for (type in listOf("contacts", "calendar", "passwords", "notes", "todos")) {
                if (SyncState.get(context, account.accountName, "e2ee_vault_$type") == vaultId) {
                    return account.accountName
                }
            }
        }
        return null
    }

    private fun readBlobs(context: Context, vaultId: String, dataType: String): Map<String, String> {
        if (dataType != "notes") return emptyMap()
        val dir = File(context.filesDir, "relay_blobs/$vaultId")
        if (!dir.isDirectory) return emptyMap()
        return dir.listFiles()?.filter { it.isFile }?.associate { file ->
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@associate file.name to ""
            file.name to Base64.encode(bytes)
        }?.filterValues { it.isNotEmpty() } ?: emptyMap()
    }

    private fun writeRecords(
        context: Context,
        vaultId: String,
        dataType: String,
        records: List<String>,
    ): Int? {
        return runCatching {
            when (dataType) {
                "passwords" -> {
                    val parsed = records.mapNotNull { PasswordKind.fromJson(it) }
                    PasswordSink.upsertAll(context, vaultId, parsed)
                    parsed.size
                }
                "notes" -> {
                    val parsed = records.mapNotNull { NoteKind.fromJson(it) }
                    NoteSink.upsertAll(context, vaultId, parsed)
                    parsed.size
                }
                "todos" -> {
                    val parsed = records.mapNotNull { TodoKind.fromJson(it) }
                    TodoIpcSink.upsertAll(context, vaultId, parsed)
                    parsed.size
                }
                "contacts" -> {
                    val account = accountForVault(context, vaultId) ?: return null
                    val parsed = records.mapNotNull { ContactKind.fromJson(it) }
                    for (item in parsed) ContactsSink.upsert(context, account, item)
                    parsed.size
                }
                "calendar" -> {
                    val account = accountForVault(context, vaultId) ?: return null
                    val calendarId = CalendarSink.getOrCreateCalendarId(
                        context, account, vaultId, vaultId, null,
                    )
                    if (calendarId == -1L) return null
                    val parsed = records.mapNotNull { CalendarKind.fromJson(it) }
                    for (item in parsed) CalendarSink.upsertEvent(context, account, calendarId, item)
                    parsed.size
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun writeBlobs(context: Context, vaultId: String, dataType: String, blobs: Map<String, String>) {
        if (dataType != "notes" || blobs.isEmpty()) return
        for ((name, b64) in blobs) {
            if (name.isBlank()) continue
            val bytes = runCatching { Base64.decode(b64) }.getOrNull() ?: continue
            RelayBlobStore.writeLocal(context, vaultId, name, bytes)
        }
    }
}

/**
 * Device-local AES-256-GCM snapshot key, Keystore-backed (same pattern as
 * `TokenStore`: non-exportable Keystore key encrypts the bytes, ciphertext in
 * DataStore). Snapshots are recoverable only on this device — by design: the
 * relay already covers cross-device recovery, snapshots cover local disaster.
 */
internal object SnapshotKeyStore {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "everysync_snapshot_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    /** Test seam: a software key replacing the AndroidKeyStore key (which Robolectric lacks). */
    internal var secretKeyForTest: javax.crypto.SecretKey? = null

    private fun secretKey(): javax.crypto.SecretKey {
        secretKeyForTest?.let { return it }
        val ks = java.security.KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? java.security.KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = javax.crypto.KeyGenerator.getInstance(
            android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE,
        )
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    fun seal(context: Context, plain: ByteArray): ByteArray? = runCatching {
        // Fresh random content key per snapshot file, wrapped by the Keystore
        // key: keeps the Keystore key's GCM nonce space unbounded.
        val contentKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(contentKey, "AES"))
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        val wrapCipher = Cipher.getInstance(TRANSFORMATION)
        wrapCipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val wrapIv = wrapCipher.iv
        val wrappedKey = wrapCipher.doFinal(contentKey)
        // Wrap parameters live alongside the file header (length-prefixed).
        wrapIv.size.toByteArray() + wrapIv + wrappedKey.size.toByteArray() + wrappedKey +
            iv.size.toByteArray() + iv + ct
    }.getOrNull()

    fun unseal(context: Context, sealed: ByteArray): ByteArray? = runCatching {
        var off = 0
        fun readChunk(): ByteArray {
            val len = sealed.copyOfRange(off, off + 4).toInt()
            off += 4
            val chunk = sealed.copyOfRange(off, off + len)
            off += len
            return chunk
        }
        val wrapIv = readChunk()
        val wrappedKey = readChunk()
        val iv = readChunk()
        val ct = sealed.copyOfRange(off, sealed.size)
        val unwrapCipher = Cipher.getInstance(TRANSFORMATION)
        unwrapCipher.init(
            Cipher.DECRYPT_MODE, secretKey(),
            GCMParameterSpec(GCM_TAG_BITS, wrapIv),
        )
        val contentKey = unwrapCipher.doFinal(wrappedKey)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE, SecretKeySpec(contentKey, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        cipher.doFinal(ct)
    }.getOrNull()

    private fun Int.toByteArray(): ByteArray = byteArrayOf(
        (this shr 24).toByte(), (this shr 16).toByte(), (this shr 8).toByte(), this.toByte(),
    )

    private fun ByteArray.toInt(): Int =
        ((this[0].toInt() and 0xFF) shl 24) or ((this[1].toInt() and 0xFF) shl 16) or
            ((this[2].toInt() and 0xFF) shl 8) or (this[3].toInt() and 0xFF)
}
