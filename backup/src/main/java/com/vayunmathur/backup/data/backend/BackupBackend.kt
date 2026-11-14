package com.vayunmathur.backup.data.backend

import java.io.ByteArrayInputStream
import java.io.OutputStream

/**
 * A destination for encrypted backup blobs, addressed by POSIX-style relative
 * paths (e.g. `metadata.json`, `kv/<pkg>/<key>`, `full/<pkg>`, `files/<id>`). Blob
 * contents are opaque ciphertext; the backend never sees plaintext. Concrete
 * backends are a user-chosen SAF folder tree ([SafBackend]) or a WebDAV/Nextcloud
 * remote ([WebDavBackend]).
 *
 * Reads and writes are scoped so the backend controls stream lifetime: pass a
 * [writer]/[reader] that consumes the stream; it is closed when the lambda returns.
 * All methods are safe to call from any dispatcher (implementations move I/O off the
 * calling thread themselves).
 */
interface BackupBackend {
    /** Human-readable destination description for the UI (e.g. the folder name or host). */
    val displayName: String

    /** Creates [path] as a directory (and any missing parents). No-op if it exists. */
    suspend fun ensureDir(path: String)

    /** Writes the blob at [path], creating parent directories, overwriting any existing blob. */
    suspend fun write(path: String, writer: suspend (OutputStream) -> Unit)

    /** Reads the blob at [path]; throws if it does not exist. */
    suspend fun <T> read(path: String, reader: suspend (java.io.InputStream) -> T): T

    /** Names (leaf, not full paths) of the blobs directly under directory [dir]. */
    suspend fun list(dir: String): List<String>

    /** Deletes the blob or directory at [path]. No-op if it does not exist. */
    suspend fun delete(path: String)

    /** Whether a blob or directory exists at [path]. */
    suspend fun exists(path: String): Boolean

    suspend fun save(path: String, bytes: ByteArray) = write(path) { it.write(bytes); it.flush() }

    suspend fun load(path: String): ByteArray = read(path) { it.readBytes() }

    suspend fun loadOrNull(path: String): ByteArray? =
        if (exists(path)) load(path) else null

    /** Stream helpers shared by [BackupBackend] implementations and callers. */
    companion object {
        fun bytesWriter(bytes: ByteArray): suspend (OutputStream) -> Unit =
            { it.write(bytes); it.flush() }

        fun emptyStream() = ByteArrayInputStream(ByteArray(0))
    }
}
