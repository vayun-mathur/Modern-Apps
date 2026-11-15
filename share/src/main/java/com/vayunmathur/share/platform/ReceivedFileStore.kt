package com.vayunmathur.share.platform

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.vayunmathur.share.domain.protocol.ReceivedChunk
import java.io.File
import java.io.FileOutputStream

/**
 * Staging area for incoming files, under app-private `filesDir/received`.
 *
 * Received bytes deliberately do **not** land in public storage. `:share` declares no
 * storage permission, and writing where the user did not ask is worse than making them
 * choose: the UI offers Share (system chooser) and Save (a directory they pick), and
 * those are the only ways a received file leaves this directory.
 *
 * One [FileOutputStream] stays open per in-flight payload, keyed by session as well as
 * payload id because ids are only unique within a session. Streams are opened on the
 * first chunk and closed on the last, so nothing is buffered in memory.
 */
class ReceivedFileStore(private val context: Context) {

    private class Open(val file: File, val stream: FileOutputStream)

    private val lock = Any()
    private val open = mutableMapOf<Pair<Long, Long>, Open>()

    private val dir: File get() = File(context.filesDir, "received")

    /**
     * Append [chunk] to its file, returning the completed [File] on the last chunk and
     * null otherwise. Returns null on an I/O failure, having closed the stream.
     */
    fun append(sessionKey: Long, chunk: ReceivedChunk): File? {
        val key = sessionKey to chunk.payloadId
        synchronized(lock) {
            val entry = open[key] ?: openFor(key, chunk.name) ?: return null
            return try {
                entry.stream.write(chunk.body)
                if (!chunk.isLast) return null
                entry.stream.close()
                open.remove(key)
                entry.file
            } catch (_: Exception) {
                closeQuietly(entry.stream)
                entry.file.delete()
                open.remove(key)
                null
            }
        }
    }

    private fun openFor(key: Pair<Long, Long>, name: String): Open? {
        dir.mkdirs()
        val safeName = name.replace('/', '_').replace('\\', '_').ifBlank { "received_file" }
        val file = uniqueFile(dir, safeName)
        return try {
            Open(file, FileOutputStream(file)).also { open[key] = it }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * A fresh, uniquely named file in the staging directory for a whole-file writer such as
     * the AirDrop archive extractor. [name] is reduced to its last path component.
     */
    fun newFile(name: String): File {
        dir.mkdirs()
        val safeName = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "received_file" }
        return synchronized(lock) { uniqueFile(dir, safeName).also { it.createNewFile() } }
    }

    /**
     * Abandon any streams still open for [sessionKey], e.g. on disconnect.
     *
     * A partly written file is deleted: nothing publishes it, resuming is not
     * implemented, so leaving it would only fill the data directory with fragments no
     * one can reach.
     */
    fun closeSession(sessionKey: Long) {
        synchronized(lock) {
            val keys = open.keys.filter { it.first == sessionKey }
            keys.forEach { key ->
                open.remove(key)?.let { entry ->
                    closeQuietly(entry.stream)
                    entry.file.delete()
                }
            }
        }
    }

    fun closeAll() {
        synchronized(lock) {
            open.values.forEach {
                closeQuietly(it.stream)
                it.file.delete()
            }
            open.clear()
        }
    }

    /**
     * Delete staged files older than [maxAgeMillis].
     *
     * Cold-start recovery: nothing else survives process death — sockets, native handles and
     * the open-stream map all go — but the half-written files do, and no [ReceivedFile] was
     * ever published for them, so nothing can reach them again. Age-based rather than
     * delete-everything because a completed file the user has not saved yet is still theirs.
     *
     * Returns how many files were removed.
     */
    fun gcOrphans(maxAgeMillis: Long): Int {
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        val stillOpen = synchronized(lock) { open.values.map { it.file.absolutePath }.toSet() }
        return dir.listFiles()
            ?.filter { it.isFile && it.lastModified() < cutoff && it.absolutePath !in stillOpen }
            ?.count { it.delete() }
            ?: 0
    }

    /**
     * A content URI another app can read, granted per-intent.
     *
     * The authority is derived from the package name so it stays correct across build
     * variants, matching the other two call sites in the repo (`notes`, `files`).
     */
    fun contentUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun closeQuietly(stream: FileOutputStream) {
        try {
            stream.close()
        } catch (_: Exception) {
        }
    }

    /** Shared constants and helpers for ReceivedFileStore. */
    companion object {
        /**
         * What an unrecognised extension resolves to, and what the protocol layer substitutes
         * for a `FileMetadata.mime_type` the peer left empty.
         *
         * Callers deciding what a file *is* must treat it as "unknown" rather than as a type,
         * because a GMS peer's attachments routinely arrive carrying nothing else.
         */
        const val GENERIC_MIME_TYPE = "application/octet-stream"

        /** MIME type for [file] from its extension, for the share chooser. */
        fun mimeTypeOf(file: File): String = mimeTypeOf(file.name)

        /**
         * MIME type for a file [name] from its extension.
         *
         * The name overload is what the send path needs: it announces types for files it has
         * only staged a copy of, and `""` made every image and video arrive at the peer as a
         * generic document.
         */
        fun mimeTypeOf(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: GENERIC_MIME_TYPE
        }

        /** `report.pdf` -> `report_1.pdf` when the name is taken. */
        fun uniqueFile(dir: File, name: String): File {
            var file = File(dir, name)
            if (!file.exists()) return file
            val dot = name.lastIndexOf('.')
            val base = if (dot >= 0) name.substring(0, dot) else name
            val ext = if (dot >= 0) name.substring(dot) else ""
            var i = 1
            while (file.exists()) {
                file = File(dir, "${base}_$i$ext")
                i++
            }
            return file
        }
    }
}
