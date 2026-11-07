package com.vayunmathur.photos.util

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.VaultPhoto
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Slice 1/3 second pass: SecureFolderManager entry points beyond the chunk codec. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosSlice1VaultManager2Test {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val manager = SecureFolderManager(context)
    private val password = "correct horse battery staple"

    @AfterTest
    fun cleanUp() {
        File(context.filesDir, "secure_vault").deleteRecursively()
    }

    private fun encryptChunked(plain: ByteArray, pw: String = password): ByteArray {
        val key = SecretKeySpec(
            MessageDigest.getInstance("SHA-256").digest(pw.toByteArray(Charsets.UTF_8)), "AES",
        )
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x53, 0x45, 0x43, 0x32)) // "SEC2"
        var off = 0
        val chunk = 1024 * 1024
        while (off < plain.size) {
            val len = minOf(chunk, plain.size - off)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val enc = cipher.doFinal(plain, off, len)
            out.write(cipher.iv)
            out.write((enc.size ushr 24) and 0xFF)
            out.write((enc.size ushr 16) and 0xFF)
            out.write((enc.size ushr 8) and 0xFF)
            out.write(enc.size and 0xFF)
            out.write(enc)
            off += len
        }
        if (plain.isEmpty()) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val enc = cipher.doFinal(ByteArray(0))
            out.write(cipher.iv)
            out.write(0); out.write(0); out.write(0); out.write(enc.size)
            out.write(enc)
        }
        return out.toByteArray()
    }

    private fun vaultFile(bytes: ByteArray, name: String = "test-${System.nanoTime()}.enc"): File {
        val dir = File(context.filesDir, "secure_vault").also { it.mkdirs() }
        return File(dir, name).also { it.writeBytes(bytes) }
    }

    private fun jpegBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(200, 30, 30))
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    @Test
    fun decryptThumbnailDecodesEncryptedJpeg() {
        val file = vaultFile(encryptChunked(jpegBytes()), "thumb.enc")
        val bitmap = manager.decryptThumbnail(file.absolutePath, password)
        assertNotNull(bitmap)
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
        bitmap.recycle()
    }

    @Test
    fun decryptThumbnailRejectsWrongPasswordAndGarbage() {
        val good = vaultFile(encryptChunked(jpegBytes()), "good.enc")
        assertNull(manager.decryptThumbnail(good.absolutePath, "wrong password"))
        // Crypto-layer rejection is pinned at the cache-file API (Robolectric's
        // BitmapFactory shadow decodes leniently, so it cannot arbitrate here).
        val garbage = vaultFile(ByteArray(64) { 0x41 }, "garbage.enc")
        assertNull(manager.decryptToCacheFile(garbage.absolutePath, password, context.cacheDir))
        val notImage = vaultFile(encryptChunked("not an image".toByteArray()), "text.enc")
        assertNotNull(manager.decryptToCacheFile(notImage.absolutePath, password, context.cacheDir))
    }

    @Test
    fun encryptAndMoveFailureDeletesPartialsAndRethrows() {
        val vaultDir = File(context.filesDir, "secure_vault")
        val before = vaultDir.listFiles()?.size ?: 0
        assertFailsWith<Exception> {
            manager.encryptAndMove(
                Uri.parse("content://media/external/images/media/999999"),
                "missing.jpg",
                password,
            )
        }
        val after = vaultDir.listFiles()?.size ?: 0
        assertTrue(after <= before, "partial vault files leaked: before=$before after=$after")
    }

    @Test
    fun decryptAndRestoreMissingFileReturnsNull() {
        val photo = VaultPhoto(
            id = 1L, name = "gone.jpg", path = "/vault/does-not-exist.enc",
            thumbnailPath = "/vault/does-not-exist_thumb.enc", date = 1L,
            width = 10, height = 10, dateModified = 1L,
        )
        assertNull(manager.decryptAndRestore(photo, password))
    }

    @Test
    fun decryptAndRestorePublishesToMediaStoreAndDeletesVaultCopy() {
        val file = vaultFile(encryptChunked(jpegBytes()), "restore.enc")
        val thumb = File(file.absolutePath + "_thumb").also { it.writeBytes(byteArrayOf(1)) }
        val photo = VaultPhoto(
            id = 2L, name = "restore.jpg", path = file.absolutePath,
            thumbnailPath = thumb.absolutePath, date = 1L,
            width = 10, height = 10, dateModified = 1L, videoDuration = 1500L,
        )
        val restored = manager.decryptAndRestore(photo, password)
        assertNotNull(restored)
        assertTrue(restored.toString().contains("/video/"), restored.toString())
        // Successful restore removes both encrypted files.
        assertTrue(!file.exists())
        assertTrue(!thumb.exists())
    }

    @Test
    fun decryptAndRestoreImageUsesImageCollection() {
        val file = vaultFile(encryptChunked(jpegBytes()), "restore-img.enc")
        val photo = VaultPhoto(
            id = 3L, name = "restore.jpg", path = file.absolutePath,
            thumbnailPath = file.absolutePath + "_thumb", date = 1L,
            width = 10, height = 10, dateModified = 1L,
        )
        val restored = manager.decryptAndRestore(photo, password)
        assertNotNull(restored)
        assertTrue(restored.toString().contains("/images/"), restored.toString())
    }

    @Test
    fun clearViewerCacheToleratesMissingDir() {
        val dir = File(context.cacheDir, "never-created-${System.nanoTime()}")
        manager.clearViewerCache(dir) // must not throw
        assertTrue(!File(dir, "vault_view").exists())
    }
}
