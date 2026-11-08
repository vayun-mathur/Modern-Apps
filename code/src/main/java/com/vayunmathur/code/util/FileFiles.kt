package com.vayunmathur.code.util

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * A single entry (file or directory) on the real filesystem.
 *
 * Replaces the SAF `DocEntry`: with `MANAGE_EXTERNAL_STORAGE` the editor works over real
 * [File] paths, so identity is just the [file] itself — no document ids to track.
 */
data class FileEntry(
    val file: File,
    val name: String,
    val isDirectory: Boolean,
)

/**
 * Thin wrappers over [java.io.File].
 *
 * All calls here hit the disk and may block, so callers run them off the main thread. Nothing
 * is cached: the file tree is rebuilt lazily as folders expand, which keeps large trees cheap
 * and always in sync with the on-disk state. This is the single file backend — the app holds
 * All-files access, so `content://` only appears for VIEW/EDIT intents from other apps
 * ([readTextFromUri]).
 */
object FileFiles {

    /** Wraps a [File] into a browsable [FileEntry]. */
    fun entryFor(file: File): FileEntry = FileEntry(file, file.name, file.isDirectory)

    /**
     * Lists the direct children of [dir], sorted with directories first and then
     * case-insensitively by name (VS Code's ordering, matching the old SAF backend).
     */
    fun listChildren(dir: File): List<FileEntry> {
        val children = dir.listFiles() ?: return emptyList()
        return children
            .map { FileEntry(it, it.name, it.isDirectory) }
            .sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    /** Reads the full UTF-8 text of a file. */
    fun readText(file: File): String = file.readText(Charsets.UTF_8)

    /** Overwrites a file with [text] (see [writeBytes] for the atomicity contract). */
    fun writeText(file: File, text: String) = writeBytes(file, text.toByteArray(Charsets.UTF_8))

    /** Reads a file's raw bytes, so encoding/BOM/line endings can be sniffed and preserved. */
    fun readBytes(file: File): ByteArray = file.readBytes()

    /**
     * Overwrites a file with raw [bytes] atomically: the bytes land in a sibling temp file
     * first and are then renamed over the target, so a crash, kill or concurrent reader can
     * never observe (or be left with) a truncated file. Falls back to a direct write when
     * the rename is refused (exotic filesystems), and always cleans the temp file up.
     */
    fun writeBytes(file: File, bytes: ByteArray) {
        val dir = file.absoluteFile.parentFile
        if (dir == null) {
            file.writeBytes(bytes)
            return
        }
        val tmp = File(dir, ".${file.name}.tmp-${System.nanoTime()}")
        try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                file.writeBytes(bytes)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * Creates a new empty file (and any missing parent directories) under [parent]. Returns the
     * new [File], or null if it already exists or creation failed.
     */
    fun createFile(parent: File, name: String): File? = runCatching {
        val target = File(parent, name)
        target.parentFile?.mkdirs()
        if (target.createNewFile()) target else null
    }.getOrNull()

    /** Creates a new directory under [parent]. Returns it, or null on failure. */
    fun createDirectory(parent: File, name: String): File? {
        val target = File(parent, name)
        val ok = runCatching { target.mkdirs() }.getOrDefault(false) || target.isDirectory
        return if (ok) target else null
    }

    /** Renames a file/dir within its parent. Returns the new [File], or null on failure. */
    fun rename(file: File, newName: String): File? {
        val target = File(file.parentFile, newName)
        return if (runCatching { file.renameTo(target) }.getOrDefault(false)) target else null
    }

    /** Deletes a file, or a directory and everything under it. Returns true on success. */
    fun delete(file: File): Boolean = runCatching { file.deleteRecursively() }.getOrDefault(false)

    /** Reads text from an external `content://`/`file://` URI, for VIEW/EDIT intents. */
    fun readTextFromUri(context: Context, uri: Uri): String {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open $uri for reading" }
            return input.bufferedReader().use { it.readText() }
        }
    }

    /** Best-effort display name for an external URI. */
    fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    /** A best-effort MIME type for a file name; defaults to plain text. */
    fun mimeForFileName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "json" -> "application/json"
            "xml" -> "text/xml"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js", "mjs", "cjs" -> "text/javascript"
            "md", "markdown" -> "text/markdown"
            else -> "text/plain"
        }
    }
}
