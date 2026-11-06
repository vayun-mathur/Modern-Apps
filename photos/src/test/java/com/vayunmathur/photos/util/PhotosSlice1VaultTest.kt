package com.vayunmathur.photos.util

import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Vault crypto tests for slice 1/3 (`data/workers`): [SecureFolderManager]'s
 * chunked encrypt/decrypt round-trips plus its failure semantics.
 *
 * `encryptAndMove`/`decryptAndRestore` need a MediaStore + thumbnail pipeline, so
 * they stay on the emulator; everything here goes through the same private
 * chunked-format code via [SecureFolderManager.decryptToCacheFile] on files the
 * test encrypts itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosSlice1VaultTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val manager = SecureFolderManager(context)
    private val cacheDir: File = File(context.cacheDir, "vault-test-${System.nanoTime()}").also {
        it.mkdirs()
    }
    private val password = "correct horse battery staple"

    @AfterTest
    fun cleanUp() {
        cacheDir.deleteRecursively()
        File(context.filesDir, "secure_vault").deleteRecursively()
    }

    private fun vaultFile(bytes: ByteArray): File {
        val dir = File(context.filesDir, "secure_vault").also { it.mkdirs() }
        return File(dir, "test-${System.nanoTime()}.enc").also { it.writeBytes(bytes) }
    }

    private fun encryptChunked(plain: ByteArray, password: String = this.password): ByteArray {
        val keyBytes = MessageDigest.getInstance("SHA-256")
            .digest(password.toByteArray(Charsets.UTF_8))
        val key = SecretKeySpec(keyBytes, "AES")
        val out = java.io.ByteArrayOutputStream()
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
        return out.toByteArray()
    }

    @Test
    fun chunkedRoundTripPreservesBytes() {
        val plain = ByteArray(3000) { (it * 31 % 251).toByte() }
        val file = vaultFile(encryptChunked(plain))
        val out = manager.decryptToCacheFile(file.absolutePath, password, cacheDir)
        assertNotNull(out)
        assertContentEquals(plain, out.readBytes())
    }

    @Test
    fun chunkedRoundTripMultiChunkFile() {
        // Cross the 1 MiB chunk boundary so the multi-chunk decrypt loop is exercised.
        val plain = ByteArray(2 * 1024 * 1024 + 17) { (it % 253).toByte() }
        val file = vaultFile(encryptChunked(plain))
        val out = manager.decryptToCacheFile(file.absolutePath, password, cacheDir)
        assertNotNull(out)
        assertContentEquals(plain, out.readBytes())
    }

    @Test
    fun wrongPasswordReturnsNullAndDeletesPartialOutput() {
        val plain = "secret bytes".toByteArray()
        val file = vaultFile(encryptChunked(plain))
        assertNull(manager.decryptToCacheFile(file.absolutePath, "wrong password", cacheDir))
        // The failed output must not linger as plaintext in the viewer dir.
        assertTrue(File(cacheDir, "vault_view").listFiles().isNullOrEmpty())
    }

    @Test
    fun truncatedFileReturnsNull() {
        val full = encryptChunked("some plaintext here".toByteArray())
        val file = vaultFile(full.copyOf(full.size / 2))
        assertNull(manager.decryptToCacheFile(file.absolutePath, password, cacheDir))
    }

    @Test
    fun emptyFileReturnsNull() {
        assertNull(manager.decryptToCacheFile(vaultFile(ByteArray(0)).absolutePath, password, cacheDir))
    }

    @Test
    fun missingFileReturnsNull() {
        val missing = File(context.filesDir, "secure_vault/nope.enc")
        assertNull(manager.decryptToCacheFile(missing.absolutePath, password, cacheDir))
    }

    @Test
    fun tamperedCiphertextFailsAuth() {
        val full = encryptChunked("authenticated content".toByteArray())
        val tampered = full.copyOf()
        tampered[tampered.size - 1] = (tampered.last() + 1).toByte()
        assertNull(manager.decryptToCacheFile(vaultFile(tampered).absolutePath, password, cacheDir))
    }

    @Test
    fun legacySingleIvFormatStillDecrypts() {
        val plain = "legacy vault entry".toByteArray()
        val keyBytes = MessageDigest.getInstance("SHA-256")
            .digest(password.toByteArray(Charsets.UTF_8))
        val key = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val enc = cipher.doFinal(plain)
        // Legacy layout: [12-byte IV][ciphertext+tag], no magic header.
        val file = vaultFile(iv + enc)
        val out = manager.decryptToCacheFile(file.absolutePath, password, cacheDir)
        assertNotNull(out)
        assertContentEquals(plain, out.readBytes())
    }

    @Test
    fun legacyFormatWithWrongPasswordReturnsNull() {
        val keyBytes = MessageDigest.getInstance("SHA-256")
            .digest(password.toByteArray(Charsets.UTF_8))
        val key = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val file = vaultFile(cipher.iv + cipher.doFinal("x".toByteArray()))
        assertNull(manager.decryptToCacheFile(file.absolutePath, "nope", cacheDir))
    }

    @Test
    fun decryptThumbnailMissingFileReturnsNull() {
        assertNull(manager.decryptThumbnail("/nonexistent/thumb.enc", password))
    }

    @Test
    fun clearViewerCacheDeletesPlaintext() {
        val viewerDir = File(cacheDir, "vault_view").also { it.mkdirs() }
        File(viewerDir, "leftover.bin").writeBytes(ByteArray(16))
        manager.clearViewerCache(cacheDir)
        assertTrue(viewerDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun decryptToCacheFilePlacesOutputUnderViewerDir() {
        val plain = "view me".toByteArray()
        val file = vaultFile(encryptChunked(plain))
        val out = manager.decryptToCacheFile(file.absolutePath, password, cacheDir)
        assertNotNull(out)
        assertEquals(File(cacheDir, "vault_view"), out.parentFile)
        assertContentEquals(plain, out.readBytes())
    }

    @Test
    fun emptyPlaintextRoundTrips() {
        // Zero chunks: magic header only must still decrypt (to empty).
        val file = vaultFile(encryptChunked(ByteArray(0)))
        val out = manager.decryptToCacheFile(file.absolutePath, password, cacheDir)
        assertNotNull(out)
        assertEquals(0, out.length())
    }
}
