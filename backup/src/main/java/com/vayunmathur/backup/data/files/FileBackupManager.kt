package com.vayunmathur.backup.data.files

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.vayunmathur.backup.data.backend.BackupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Standalone encrypted file/media backup: enumerates the user's photos, videos, and
 * audio via [MediaStore], encrypts each through the [BackupRepository] into the
 * backend under `files/`, and records a manifest at `files/index.json`. Unlike the
 * privileged app-data transport, this path is exercisable on a normal device.
 *
 * Restore writes decrypted copies into the app's external files dir under
 * `BackupRestore/` (writing back into the shared MediaStore collections would need
 * additional user consent per item).
 */
class FileBackupManager(
    private val context: Context,
    private val repo: BackupRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Backs up all enumerated media; returns the number of files stored in this run. */
    suspend fun backupAll(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Int {
        val items = enumerate()
        onProgress(0, items.size)
        // Start from the previous manifest: an item that fails this run (or a
        // run that enumerates nothing because media permission was revoked)
        // must not erase its existing entry, or the still-present blob becomes
        // unrestorable. Entries are only replaced by a successful re-backup.
        val merged = LinkedHashMap<String, FileEntry>()
        for (entry in listBackedUp()) merged[entry.fileId] = entry
        var stored = 0
        for ((i, item) in items.withIndex()) {
            val result = runCatching {
                val input = context.contentResolver.openInputStream(item.uri)
                    ?: throw IOException("cannot open ${item.entry.displayName}")
                input.use { repo.writeEncrypted(BackupRepository.filePath(item.entry.fileId), it) }
            }
            if (result.isSuccess) {
                merged[item.entry.fileId] = item.entry
                stored++
            }
            onProgress(i + 1, items.size)
        }
        repo.writeEncrypted(INDEX, json.encodeToString(FileIndex(merged.values.toList())).toByteArray())
        return stored
    }

    /** The manifest of previously backed-up files (empty if none). */
    suspend fun listBackedUp(): List<FileEntry> {
        if (!repo.exists(INDEX)) return emptyList()
        return runCatching {
            json.decodeFromString<FileIndex>(repo.readEncrypted(INDEX).decodeToString()).files
        }.getOrDefault(emptyList())
    }

    /** Restores backed-up files into the app external `BackupRestore/` dir; returns count restored. */
    suspend fun restoreAll(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Int =
        withContext(Dispatchers.IO) {
            val entries = listBackedUp()
            val baseDir = File(context.getExternalFilesDir(null), "BackupRestore")
            onProgress(0, entries.size)
            var restored = 0
            for ((i, e) in entries.withIndex()) {
                runCatching {
                    val outFile = File(baseDir, e.relativePath + e.displayName)
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { repo.readEncryptedTo(BackupRepository.filePath(e.fileId), it) }
                    restored++
                }
                onProgress(i + 1, entries.size)
            }
            restored
        }

    private data class Item(val uri: Uri, val entry: FileEntry)

    private suspend fun enumerate(): List<Item> = withContext(Dispatchers.IO) {
        buildList {
            for (collection in COLLECTIONS) {
                queryCollection(collection, this)
            }
        }
    }

    private fun queryCollection(collection: Collection, out: MutableList<Item>) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        context.contentResolver.query(collection.uri, projection, null, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val name = c.getString(nameCol) ?: continue
                val relPath = c.getString(pathCol) ?: ""
                val uri = ContentUris.withAppendedId(collection.uri, id)
                out.add(
                    Item(
                        uri = uri,
                        entry = FileEntry(
                            fileId = fileId(collection.type, relPath, name),
                            displayName = name,
                            relativePath = relPath,
                            mimeType = c.getString(mimeCol) ?: "application/octet-stream",
                            size = c.getLong(sizeCol),
                            dateModified = c.getLong(dateCol),
                            mediaType = collection.type,
                        ),
                    ),
                )
            }
        }
    }

    private fun fileId(type: String, relativePath: String, name: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$type/$relativePath$name".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private data class Collection(val uri: Uri, val type: String)

    companion object {
        private const val INDEX = "${BackupRepository.FILES_DIR}/index.json"

        private val COLLECTIONS = listOf(
            Collection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image"),
            Collection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video"),
            Collection(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "audio"),
        )
    }
}
