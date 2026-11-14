package com.vayunmathur.backup.data.backend

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * [BackupBackend] over a Storage Access Framework document tree — a user-chosen
 * folder on internal storage, a USB drive, or an SD card, picked via
 * `ACTION_OPEN_DOCUMENT_TREE` and persisted with a takePersistableUriPermission
 * grant. Paths map to a directory/file hierarchy under the tree, addressed through
 * [DocumentsContract] (no androidx.documentfile dependency).
 */
class SafBackend(
    private val context: Context,
    private val treeUri: Uri,
) : BackupBackend {

    private val resolver get() = context.contentResolver
    private val rootDocId get() = DocumentsContract.getTreeDocumentId(treeUri)

    override val displayName: String
        get() = treeUri.lastPathSegment?.substringAfterLast(':')?.ifEmpty { "SAF folder" } ?: "SAF folder"

    override suspend fun ensureDir(path: String) = withContext(Dispatchers.IO) {
        resolveDir(segments(path), create = true) ?: throw IOException("cannot create dir: $path")
        Unit
    }

    override suspend fun write(path: String, writer: suspend (OutputStream) -> Unit) =
        withContext(Dispatchers.IO) {
            val segments = segments(path)
            val name = segments.last()
            val parent = resolveDir(segments.dropLast(1), create = true)
                ?: throw IOException("cannot create parent of: $path")
            val existing = findChild(parent, name)
            val fileUri = if (existing != null) {
                docUri(existing.docId)
            } else {
                DocumentsContract.createDocument(resolver, docUri(parent), MIME_OCTET, name)
                    ?: throw IOException("cannot create file: $path")
            }
            val out = openOutput(fileUri) ?: throw IOException("cannot open for write: $path")
            out.use { writer(it) }
        }

    override suspend fun <T> read(path: String, reader: suspend (InputStream) -> T): T =
        withContext(Dispatchers.IO) {
            val docId = resolveFile(path) ?: throw FileNotFoundException(path)
            val input = resolver.openInputStream(docUri(docId))
                ?: throw IOException("cannot open for read: $path")
            input.use { reader(it) }
        }

    override suspend fun list(dir: String): List<String> = withContext(Dispatchers.IO) {
        val parent = resolveDir(segments(dir), create = false) ?: return@withContext emptyList()
        buildList {
            resolver.query(
                childrenUri(parent),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { c -> while (c.moveToNext()) add(c.getString(0)) }
        }
    }

    override suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        val segments = segments(path)
        val parent = resolveDir(segments.dropLast(1), create = false) ?: return@withContext
        val child = findChild(parent, segments.last()) ?: return@withContext
        DocumentsContract.deleteDocument(resolver, docUri(child.docId))
        Unit
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        val segments = segments(path)
        if (segments.isEmpty()) return@withContext true
        val parent = resolveDir(segments.dropLast(1), create = false) ?: return@withContext false
        findChild(parent, segments.last()) != null
    }

    // --- DocumentsContract helpers ---

    private data class Child(val docId: String, val isDir: Boolean)

    private fun docUri(docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    private fun childrenUri(parentDocId: String): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)

    private fun openOutput(uri: Uri): OutputStream? =
        runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: runCatching { resolver.openOutputStream(uri, "w") }.getOrNull()

    private fun findChild(parentDocId: String, name: String): Child? {
        resolver.query(
            childrenUri(parentDocId),
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name) {
                    return Child(c.getString(0), c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
                }
            }
        }
        return null
    }

    /** Walks (optionally creating) directory [segments]; returns the deepest doc id. */
    private fun resolveDir(segments: List<String>, create: Boolean): String? {
        var parent = rootDocId
        for (seg in segments) {
            val existing = findChild(parent, seg)
            parent = when {
                existing != null && existing.isDir -> existing.docId
                existing != null -> return null // a file blocks this path
                create -> {
                    val uri = DocumentsContract.createDocument(
                        resolver, docUri(parent), DocumentsContract.Document.MIME_TYPE_DIR, seg,
                    ) ?: return null
                    DocumentsContract.getDocumentId(uri)
                }
                else -> return null
            }
        }
        return parent
    }

    /** Resolves a file path to its doc id, or null if any segment is missing. */
    private fun resolveFile(path: String): String? {
        val segments = segments(path)
        val parent = resolveDir(segments.dropLast(1), create = false) ?: return null
        return findChild(parent, segments.last())?.docId
    }

    private fun segments(path: String): List<String> =
        path.split('/').filter { it.isNotEmpty() }

    /** MIME type used when creating backup blobs in the SAF tree. */
    companion object {
        private const val MIME_OCTET = "application/octet-stream"
    }
}
