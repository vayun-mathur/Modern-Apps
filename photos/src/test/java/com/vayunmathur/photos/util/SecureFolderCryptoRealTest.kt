package com.vayunmathur.photos.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import javax.crypto.SecretKey
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SecureFolderManager] chunked AES-GCM format: files encrypted with the
 * production chunk writer decrypt back byte-for-byte through the public
 * viewer/restore paths, wrong passwords fail closed, and the viewer cache
 * is wiped on demand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecureFolderCryptoRealTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun manager() = SecureFolderManager(app)

    private fun encryptTo(manager: SecureFolderManager, password: String, plaintext: ByteArray, name: String): File {
        val keyMethod = SecureFolderManager::class.java.getDeclaredMethod("getSecretKey", String::class.java)
            .apply { isAccessible = true }
        val key = keyMethod.invoke(manager, password) as SecretKey
        val encrypt = SecureFolderManager::class.java.getDeclaredMethod(
            "encryptStreamChunked", java.io.InputStream::class.java, java.io.OutputStream::class.java, SecretKey::class.java,
        ).apply { isAccessible = true }
        val file = File(app.cacheDir, name)
        FileOutputStream(file).use { out ->
            encrypt.invoke(manager, ByteArrayInputStream(plaintext), out, key)
        }
        return file
    }

    @Test fun chunkedRoundTripThroughViewerCache() {
        val manager = manager()
        val plaintext = ByteArray(1_500_000) { (it % 251).toByte() } // > 1 chunk
        val encrypted = encryptTo(manager, "correct horse", plaintext, "vault-photo.enc")
        val restored = manager.decryptToCacheFile(encrypted.absolutePath, "correct horse", app.cacheDir)
        assertNotNull(restored)
        assertTrue(plaintext.contentEquals(restored.readBytes()))
        // Wrong password: GCM tag failure deletes the partial plaintext.
        val denied = manager.decryptToCacheFile(encrypted.absolutePath, "wrong", app.cacheDir)
        assertNull(denied)
        // Missing input is a clean null.
        assertNull(manager.decryptToCacheFile(File(app.cacheDir, "nope.enc").absolutePath, "correct horse", app.cacheDir))
        // Clearing the viewer cache removes the decrypted copy.
        manager.clearViewerCache(app.cacheDir)
        assertTrue(!restored.exists())
    }

    @Test fun decryptThumbnailMissingAndCorrupt() {
        val manager = manager()
        assertNull(manager.decryptThumbnail(File(app.cacheDir, "missing.enc").absolutePath, "pw"))
        val corrupt = File(app.cacheDir, "corrupt.enc").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertNull(manager.decryptThumbnail(corrupt.absolutePath, "pw"))
    }

    @Test fun restoreOfMissingVaultFileIsNull() {
        val manager = manager()
        val photo = com.vayunmathur.photos.data.VaultPhoto(
            name = "gone.jpg", path = File(app.cacheDir, "gone.enc").absolutePath,
            thumbnailPath = File(app.cacheDir, "gone_thumb.enc").absolutePath,
            date = 1L, width = 10, height = 10, dateModified = 1L,
        )
        assertNull(manager.decryptAndRestore(photo, "pw"))
    }
}
