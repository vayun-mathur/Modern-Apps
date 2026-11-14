package com.vayunmathur.backup.data.backend

import com.vayunmathur.backup.domain.crypto.Crypto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * The on-backend layout of an encrypted backup, and the bridge between [Crypto] and a
 * [BackupBackend]: every blob written here is [Crypto]-encrypted first and decrypted
 * on read, so the backend only ever stores ciphertext.
 *
 * Layout (all relative to the backend root):
 * ```
 * metadata.json           – encrypted backup manifest (version, device, timestamp)
 * kv/<pkg>/<keyId>        – key/value backup entities, one blob per record key
 * full/<pkg>              – full-data (tar) app backup blob
 * files/<fileId>         – standalone file/media backup blobs
 * ```
 * Ported concept (Seedvault repository layout) — see backup/LICENSE-Seedvault.
 */
class BackupRepository(
    val backend: BackupBackend,
    private val crypto: Crypto,
) {
    suspend fun writeEncrypted(path: String, input: InputStream) =
        backend.write(path) { out -> crypto.encrypt(input, out) }

    suspend fun writeEncrypted(path: String, bytes: ByteArray) =
        writeEncrypted(path, ByteArrayInputStream(bytes))

    suspend fun readEncryptedTo(path: String, out: OutputStream) =
        backend.read(path) { input -> crypto.decrypt(input, out) }

    suspend fun readEncrypted(path: String): ByteArray =
        ByteArrayOutputStream().also { readEncryptedTo(path, it) }.toByteArray()

    suspend fun exists(path: String) = backend.exists(path)

    suspend fun delete(path: String) = backend.delete(path)

    suspend fun list(dir: String) = backend.list(dir)

    /** Backend path constants and path builders for the backup layout. */
    companion object {
        const val METADATA = "metadata.json"
        const val KV_DIR = "kv"
        const val FULL_DIR = "full"
        const val FILES_DIR = "files"

        fun kvPath(packageName: String, keyId: String) = "$KV_DIR/$packageName/$keyId"
        fun kvPackageDir(packageName: String) = "$KV_DIR/$packageName"
        fun fullPath(packageName: String) = "$FULL_DIR/$packageName"
        fun filePath(fileId: String) = "$FILES_DIR/$fileId"
    }
}
