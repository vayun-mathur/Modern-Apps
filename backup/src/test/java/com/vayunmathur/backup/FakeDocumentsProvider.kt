package com.vayunmathur.backup

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * Filesystem-backed [DocumentsProvider] for SAF tests. Doc ids are paths
 * relative to [rootDir] (root = "root"). Registered in tests under [AUTHORITY].
 */
class FakeDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean { instance = this; return true }

    override fun isChildDocument(parentDocId: String, docId: String): Boolean =
        docId == parentDocId || docId.startsWith("$parentDocId/")

    private fun file(docId: String): File =
        if (docId == ROOT_ID) rootDir else File(rootDir, docId.removePrefix("$ROOT_ID/"))

    private fun docId(file: File): String =
        if (file == rootDir) ROOT_ID else "$ROOT_ID/" + file.relativeTo(rootDir).path.replace(File.separatorChar, '/')

    private fun row(cursor: MatrixCursor, file: File) {
        val row = cursor.newRow()
        for (col in cursor.columnNames) row.add(columnValue(col, file))
    }

    private fun columnValue(col: String, file: File): Any = when (col) {
        Document.COLUMN_DOCUMENT_ID -> docId(file)
        Document.COLUMN_DISPLAY_NAME -> if (file == rootDir) "root" else file.name
        Document.COLUMN_MIME_TYPE -> if (file.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream"
        Document.COLUMN_SIZE -> if (file.isFile) file.length() else 0L
        DocumentsContract.Root.COLUMN_ROOT_ID -> ROOT_ID
        DocumentsContract.Root.COLUMN_DOCUMENT_ID -> ROOT_ID
        DocumentsContract.Root.COLUMN_TITLE -> "Fake"
        else -> if (col == Document.COLUMN_LAST_MODIFIED) file.lastModified() else 0
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cols = projection?.toList() ?: DEFAULT_ROOT_PROJECTION
        val c = MatrixCursor(cols.toTypedArray())
        val row = c.newRow()
        for (col in c.columnNames) {
            row.add(when (col) {
                DocumentsContract.Root.COLUMN_ROOT_ID -> ROOT_ID
                DocumentsContract.Root.COLUMN_DOCUMENT_ID -> ROOT_ID
                DocumentsContract.Root.COLUMN_TITLE -> "Fake"
                else -> 0
            })
        }
        return c
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cols = projection?.toList() ?: DEFAULT_DOC_PROJECTION
        val c = MatrixCursor(cols.toTypedArray())
        val f = file(documentId)
        if (f.exists()) row(c, f)
        return c
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val cols = projection?.toList() ?: DEFAULT_DOC_PROJECTION
        val c = MatrixCursor(cols.toTypedArray())
        file(parentDocumentId).listFiles()?.sortedBy { it.name }?.forEach { row(c, it) }
        return c
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = file(parentDocumentId); parent.mkdirs()
        val target = File(parent, displayName)
        if (mimeType == Document.MIME_TYPE_DIR) target.mkdirs() else target.createNewFile()
        return docId(target)
    }

    override fun deleteDocument(documentId: String) {
        val f = file(documentId)
        if (f.isDirectory) f.deleteRecursively() else f.delete()
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val f = file(documentId)
        if (!f.exists() && mode.contains("w")) { f.parentFile?.mkdirs(); f.createNewFile() }
        if (!f.exists()) throw FileNotFoundException(documentId)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }

    companion object {
        var instance: FakeDocumentsProvider? = null
        const val AUTHORITY = "com.vayunmathur.backup.fakedocs"
        const val ROOT_ID = "root"
        lateinit var rootDir: File
        private val DEFAULT_DOC_PROJECTION = listOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )
        private val DEFAULT_ROOT_PROJECTION = listOf(
            DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS,
        )
    }
}
