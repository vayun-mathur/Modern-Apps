package com.vayunmathur.passwords.sync

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.vayunmathur.passwords.data.PasswordDatabase
import com.vayunmathur.passwords.data.PasswordRepository
import com.vayunmathur.passwords.util.KdbxNative
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** Singleton holding native kdbx codec state and operations. */
object NativeKdbxCodec : KdbxCodec {
    override fun read(password: String, bytes: ByteArray): List<Map<String, String>>? {
        val json = KdbxNative.nativeImport(password, bytes) ?: return null
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val entry = array.getJSONObject(i)
            buildMap { for (key in entry.keys()) put(key, entry.getString(key)) }
        }
    }

    override fun write(password: String, entries: List<Map<String, String>>): ByteArray? {
        val array = JSONArray()
        for (entry in entries) array.put(JSONObject(entry as Map<*, *>))
        return KdbxNative.nativeExport(password, array.toString())
    }
}

/** Saf Kdbx Document: a context implementation for saf kdbx document. */
class SafKdbxDocument(private val context: Context, private val uri: Uri) : KdbxDocument {
    override fun read(): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw FileNotFoundException("Cannot open $uri")

    override fun write(bytes: ByteArray) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: throw FileNotFoundException("Cannot write $uri")
    }
}

/** Runs a sync from the persisted settings, recording the outcome for the settings screen. */
suspend fun runKdbxSync(context: Context, db: PasswordDatabase): KdbxSyncResult {
    if (!KdbxSyncSettings.enabled(context)) return KdbxSyncResult.NotConfigured
    val uri = KdbxSyncSettings.documentUri(context) ?: return KdbxSyncResult.NotConfigured
    val passwordHelper = KdbxPasswordHelper(context)
    if (!passwordHelper.isKeyGenerated()) return KdbxSyncResult.NotConfigured

    val result = runSyncCatching {
        KdbxSyncEngine(db, NativeKdbxCodec).sync(
            document = SafKdbxDocument(context, uri.toUri()),
            vaultPassword = passwordHelper.getPassphrase(),
            onBackup = { previous -> writeBytesAtomically(File(context.filesDir, BACKUP_FILE_NAME), previous) },
        )
    }

    when (result) {
        is KdbxSyncResult.Success -> {
            // A successful sync clears any prior quarantine: the file now
            // provably holds every baselined id (live or tombstoned).
            KdbxSyncSettings.clearQuarantine(context)
            KdbxSyncSettings.recordSuccess(context)
        }
        KdbxSyncResult.NotConfigured -> Unit
        is KdbxSyncResult.Quarantined ->
            KdbxSyncSettings.recordQuarantine(context, KdbxSyncSettings.QUARANTINE_FOREIGN_DELETE)
        else -> KdbxSyncSettings.recordFailure(context, result.errorCode())
    }
    return result
}

private suspend fun runSyncCatching(block: suspend () -> KdbxSyncResult): KdbxSyncResult {
    return try {
        block()
    } catch (expected: CancellationException) {
        throw expected
    } catch (expected: Exception) {
        // Any transport/codec failure (SAF IOException on a cloud-backed
        // document, Room failure, codec crash) aborts the sync: the engine
        // mutates nothing until re-encode + verify, so abort is always safe.
        // Never let an exception escape as a partial merge.
        KdbxSyncResult.Error(expected.message ?: expected.javaClass.simpleName)
    }
}

/** Repository-based overload — preferred; avoids direct PasswordDatabase access. */
suspend fun runKdbxSync(context: Context, repository: PasswordRepository): KdbxSyncResult {
    if (!KdbxSyncSettings.enabled(context)) return KdbxSyncResult.NotConfigured
    val uri = KdbxSyncSettings.documentUri(context) ?: return KdbxSyncResult.NotConfigured
    val passwordHelper = KdbxPasswordHelper(context)
    if (!passwordHelper.isKeyGenerated()) return KdbxSyncResult.NotConfigured

    val result = runSyncCatching {
        KdbxSyncEngine(repository, NativeKdbxCodec).sync(
            document = SafKdbxDocument(context, uri.toUri()),
            vaultPassword = passwordHelper.getPassphrase(),
            onBackup = { previous -> writeBytesAtomically(File(context.filesDir, BACKUP_FILE_NAME), previous) },
        )
    }

    when (result) {
        is KdbxSyncResult.Success -> {
            // A successful sync clears any prior quarantine: the file now
            // provably holds every baselined id (live or tombstoned).
            KdbxSyncSettings.clearQuarantine(context)
            KdbxSyncSettings.recordSuccess(context)
        }
        KdbxSyncResult.NotConfigured -> Unit
        is KdbxSyncResult.Quarantined ->
            KdbxSyncSettings.recordQuarantine(context, KdbxSyncSettings.QUARANTINE_FOREIGN_DELETE)
        else -> KdbxSyncSettings.recordFailure(context, result.errorCode())
    }
    return result
}

private const val BACKUP_FILE_NAME = "kdbx-sync-backup.kdbx"

/**
 * Write [bytes] to [target] via tmp file + rename, so a crash mid-backup never leaves a
 * torn KDBX backup in place of the last good one. Same shape as `AndroidFileBlobStore.put`
 * (library/everysync-core): temp in the same directory, rename, delete+rename fallback
 * where rename cannot replace, temp always cleaned up. (No `FileFiles.writeBytes` exists
 * in library/ — verified, it does not do this.)
 */
private fun writeBytesAtomically(target: File, bytes: ByteArray) {
    val dir = target.parentFile
    if (dir != null) dir.mkdirs()
    val tmp = File.createTempFile("kdbx-backup-", ".tmp", dir)
    try {
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    } finally {
        if (tmp.exists()) tmp.delete()
    }
}
