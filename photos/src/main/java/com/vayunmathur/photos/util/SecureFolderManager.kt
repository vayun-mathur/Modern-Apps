package com.vayunmathur.photos.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import com.vayunmathur.photos.data.VaultPhoto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SecureFolderManager(val context: Context) {
    private val secureFolder = File(context.filesDir, "secure_vault")

    init {
        if (!secureFolder.exists()) secureFolder.mkdirs()
    }

    private fun getSecretKey(password: String): SecretKey {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(password.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun getCipher(mode: Int, key: SecretKey, iv: ByteArray? = null): Cipher {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        if (iv != null) {
            cipher.init(mode, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        } else {
            cipher.init(mode, key)
        }
        return cipher
    }

    // Chunked streaming encryption to avoid OOM on large files.
    // AES/GCM via CipherOutputStream in Conscrypt buffers the entire plaintext
    // until doFinal() (OpenSSLAeadCipher.appendToBuf), causing OutOfMemoryError
    // for +100MB files. We split into 1MiB chunks, each encrypted independently
    // with a fresh random IV and written as: [MAGIC(4)][IV(12)][len(4)][ciphertext].
    companion object {
        private const val GCM_IV_LENGTH = 12
        private const val CHUNK_SIZE = 1024 * 1024 // 1 MiB
        private val MAGIC_SEC2 = byteArrayOf(0x53, 0x45, 0x43, 0x32) // "SEC2" = Secure v2 chunked
        private const val MAGIC_LENGTH = 4
        private const val LENGTH_PREFIX_BYTES = 4
        private const val BYTE_MASK = 0xFF
        private const val INT_BYTE_3_SHIFT = 24
        private const val INT_BYTE_2_SHIFT = 16
        private const val INT_BYTE_1_SHIFT = 8
        private const val LEGACY_IV_PREFIX = 4
        private const val LEGACY_IV_SUFFIX = 8
        private const val THUMBNAIL_SIZE = 512
        private const val GCM_TAG_BITS = 128
        private const val THUMBNAIL_JPEG_QUALITY = 80
    }

    private fun InputStream.readFully(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Boolean {
        var remaining = length
        var off = offset
        while (remaining > 0) {
            val r = read(buffer, off, remaining)
            if (r == -1) return false
            remaining -= r
            off += r
        }
        return true
    }

    private fun encryptStreamChunked(input: InputStream, output: OutputStream, key: SecretKey) {
        output.write(MAGIC_SEC2)
        val buffer = ByteArray(CHUNK_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            if (read > 0) {
                encryptChunk(buffer, read, output, key)
            }
        }
    }

    private fun encryptChunk(buffer: ByteArray, read: Int, output: OutputStream, key: SecretKey) {
        val cipher = getCipher(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv // 12 bytes random
        val encrypted = cipher.doFinal(buffer, 0, read)
        output.write(iv)
        output.write((encrypted.size ushr INT_BYTE_3_SHIFT) and BYTE_MASK)
        output.write((encrypted.size ushr INT_BYTE_2_SHIFT) and BYTE_MASK)
        output.write((encrypted.size ushr INT_BYTE_1_SHIFT) and BYTE_MASK)
        output.write(encrypted.size and BYTE_MASK)
        output.write(encrypted)
    }

    /**
     * Decrypts both new chunked format (SEC2) and legacy single-IV format.
     * Returns false on malformed / auth failure.
     */
    private fun decryptToOutputStream(input: InputStream, output: OutputStream, key: SecretKey): Boolean {
        val first4 = ByteArray(MAGIC_LENGTH)
        if (!input.readFully(first4)) return false
        return if (first4.contentEquals(MAGIC_SEC2)) {
            decryptChunkedStream(input, output, key)
        } else {
            decryptLegacyStream(first4, input, output, key)
        }
    }

    private fun decryptChunkedStream(input: InputStream, output: OutputStream, key: SecretKey): Boolean {
        val lenBytes = ByteArray(LENGTH_PREFIX_BYTES)
        while (true) {
            // Detect clean EOF
            val firstIvByte = input.read()
            if (firstIvByte == -1) break // done
            if (!decryptOneChunk(input, output, key, firstIvByte, lenBytes)) return false
        }
        return true
    }

    private fun decryptOneChunk(
        input: InputStream,
        output: OutputStream,
        key: SecretKey,
        firstIvByte: Int,
        lenBytes: ByteArray,
    ): Boolean {
        val iv = ByteArray(GCM_IV_LENGTH)
        iv[0] = firstIvByte.toByte()
        if (!input.readFully(iv, 1, GCM_IV_LENGTH - 1)) return false
        if (!input.readFully(lenBytes)) return false
        val encLen = ((lenBytes[0].toInt() and BYTE_MASK) shl INT_BYTE_3_SHIFT) or
                ((lenBytes[1].toInt() and BYTE_MASK) shl INT_BYTE_2_SHIFT) or
                ((lenBytes[2].toInt() and BYTE_MASK) shl INT_BYTE_1_SHIFT) or
                (lenBytes[3].toInt() and BYTE_MASK)
        if (encLen <= 0) return false
        val encData = ByteArray(encLen)
        if (!input.readFully(encData)) return false
        return try {
            val cipher = getCipher(Cipher.DECRYPT_MODE, key, iv)
            val decrypted = cipher.doFinal(encData)
            output.write(decrypted)
            true
        } catch (_: GeneralSecurityException) {
            false
        }
    }

    /** Legacy format: [12 byte IV][ciphertext+tag] single GCM encryption. */
    private fun decryptLegacyStream(
        first4: ByteArray,
        input: InputStream,
        output: OutputStream,
        key: SecretKey,
    ): Boolean {
        val iv = ByteArray(GCM_IV_LENGTH)
        System.arraycopy(first4, 0, iv, 0, LEGACY_IV_PREFIX)
        if (!input.readFully(iv, LEGACY_IV_PREFIX, LEGACY_IV_SUFFIX)) return false
        return try {
            val cipher = getCipher(Cipher.DECRYPT_MODE, key, iv)
            val encrypted = input.readBytes()
            val decrypted = cipher.doFinal(encrypted)
            output.write(decrypted)
            true
        } catch (_: GeneralSecurityException) {
            false
        }
    }

    fun encryptAndMove(uri: Uri, name: String, password: String): Pair<String, String> {
        val key = getSecretKey(password)
        val timestamp = System.currentTimeMillis()
        val fileName = "${timestamp}_${name}.enc"
        val thumbName = "${timestamp}_${name}_thumb.enc"

        val outputFile = File(secureFolder, fileName)
        val thumbFile = File(secureFolder, thumbName)

        try {
            // 1. Generate Thumbnail
            val bitmap = context.contentResolver.loadThumbnail(uri, Size(THUMBNAIL_SIZE, THUMBNAIL_SIZE), null)

            // 2. Encrypt Thumbnail (small, but also use chunked format for consistency)
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_JPEG_QUALITY, baos)
            bitmap.recycle()
            val thumbBytes = baos.toByteArray()

            FileOutputStream(thumbFile).use { fos ->
                ByteArrayInputStream(thumbBytes).use { input ->
                    encryptStreamChunked(input, fos, key)
                }
            }

            // 3. Encrypt Main File with streaming chunks to avoid OOM on large videos
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outputFile).use { fos ->
                    encryptStreamChunked(input, fos, key)
                }
            } ?: throw IllegalStateException("Unable to open input stream for $uri")

            return outputFile.absolutePath to thumbFile.absolutePath
        } catch (e: IOException) {
            encryptFailure(outputFile, thumbFile, e)
        } catch (e: GeneralSecurityException) {
            encryptFailure(outputFile, thumbFile, e)
        } catch (e: SecurityException) {
            // Revoked/expired read grant mid-move: openInputStream throws the
            // raw SecurityException (never wrapped). Clean up partials and
            // rethrow so the per-photo caller skips this photo, same as any
            // other per-photo failure.
            encryptFailure(outputFile, thumbFile, e)
        }
    }

    /** Delete partial outputs on failure, then rethrow — never leave corrupt vault files behind. */
    private fun encryptFailure(outputFile: File, thumbFile: File, e: Exception): Nothing {
        outputFile.delete()
        thumbFile.delete()
        throw e
    }

    fun decryptThumbnail(path: String, password: String): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null

        val key = getSecretKey(password)
        return try {
            FileInputStream(file).use { fis ->
                ByteArrayOutputStream().use { baos ->
                    if (!decryptToOutputStream(fis, baos, key)) return null
                    val bytes = baos.toByteArray()
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }
        } catch (_: IOException) {
            null
        }
    }

    /**
     * Decrypt the vault file at [path] into the app cache so it can be viewed
     * full-screen without touching the MediaStore. The output lives under
     * [cacheDir]/vault_view/ and is plaintext while it exists, so callers must
     * serve it via FileProvider (never a file:// Uri to another app) and delete
     * it when the viewer closes (see [clearViewerCache]).
     * Returns null when the file is missing or decryption fails.
     */
    fun decryptToCacheFile(path: String, password: String, cacheDir: File): File? {
        val inputFile = File(path)
        if (!inputFile.exists()) return null
        val viewerDir = File(cacheDir, "vault_view")
        if (!viewerDir.exists() && !viewerDir.mkdirs()) return null
        val outputFile = File(viewerDir, inputFile.nameWithoutExtension)
        return try {
            val key = getSecretKey(password)
            FileInputStream(inputFile).use { fis ->
                FileOutputStream(outputFile).use { fos ->
                    if (!decryptToOutputStream(fis, fos, key)) {
                        outputFile.delete()
                        return null
                    }
                }
            }
            outputFile
        } catch (_: IOException) {
            outputFile.delete()
            null
        }
    }

    /** Delete plaintext files left under [cacheDir]/vault_view (see [decryptToCacheFile]). */
    fun clearViewerCache(cacheDir: File) {
        File(cacheDir, "vault_view").listFiles()?.forEach { runCatching { it.delete() } }
    }

    fun decryptAndRestore(vaultPhoto: VaultPhoto, password: String): Uri? {
        val inputFile = File(vaultPhoto.path)
        if (!inputFile.exists()) return null

        val key = getSecretKey(password)
        val uri = insertRestoreTarget(vaultPhoto) ?: return null

        val success = try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                FileInputStream(inputFile).use { fis ->
                    decryptToOutputStream(fis, output, key)
                }
            } ?: false
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }

        if (!success) {
            context.contentResolver.delete(uri, null, null)
            return null
        }

        // Delete encrypted files
        inputFile.delete()
        File(vaultPhoto.thumbnailPath).delete()

        return uri
    }

    /** Insert the MediaStore row the vault file is restored into, or null if rejected. */
    private fun insertRestoreTarget(vaultPhoto: VaultPhoto): Uri? {
        val isVideo = vaultPhoto.videoDuration != null
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, vaultPhoto.name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Restored")
        }
        val collection = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        return context.contentResolver.insert(collection, contentValues)
    }
}
