package com.vayunmathur.communicate.whatsapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.communicate.data.whatsapp.backup.Crypt15Decryptor
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Slice 1 (w5): Crypt15Decryptor — build a crypt15 blob with the documented
 * HKDF(info="backup encryption") derivation, decrypt, and assert the gunzipped
 * SQLite bytes round-trip. Plus malformed-input error paths.
 *
 * Robolectric: the success path logs via Android Log, so the class runs under
 * Robolectric (behavior-identical crypto on JVM).
 */
@RunWith(AndroidJUnit4::class)
class W5Slice1Crypt15Test {

    private fun hmac(key: ByteArray, msg: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(msg)
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArray(len)
        var t = ByteArray(0)
        var pos = 0
        var c = 1
        while (pos < len) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(c.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, len - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            c++
        }
        return out
    }

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun varint(v: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var x = v
        while (true) {
            var b = (x and 0x7F).toInt()
            x = x ushr 7
            if (x != 0L) b = b or 0x80
            out.write(b)
            if (x == 0L) break
        }
        return out.toByteArray()
    }

    /** Minimal header: [varint len][field1 = length-delimited 16-byte IV]. */
    private fun headerWithIv(iv: ByteArray): ByteArray {
        val inner = ByteArrayOutputStream()
        inner.write(0x0A) // field 1, wire type 2
        inner.write(varint(16))
        inner.write(iv)
        val innerBytes = inner.toByteArray()
        val outer = ByteArrayOutputStream()
        outer.write(varint(innerBytes.size.toLong()))
        outer.write(innerBytes)
        return outer.toByteArray()
    }

    private fun encryptBlob(plainDb: ByteArray, backupKey: ByteArray, iv: ByteArray): ByteArray {
        val aesKey = hkdf(backupKey, ByteArray(32), "backup encryption".toByteArray(Charsets.UTF_8), 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(gzip(plainDb))
        return headerWithIv(iv) + ct
    }

    @Test
    fun decrypt_roundTripsGzippedPayload() {
        val backupKey = ByteArray(32) { (it * 7 + 1).toByte() }
        val iv = ByteArray(16) { (it + 3).toByte() }
        val fakeDb = "SQLite format 3\u0000fake-db-bytes-1234567890".toByteArray(Charsets.ISO_8859_1)
        val blob = encryptBlob(fakeDb, backupKey, iv)

        val cacheDir = Files.createTempDirectory("crypt15test").toFile()
        try {
            val out = Crypt15Decryptor.decryptToFile(blob, backupKey, cacheDir)
            assertTrue(out.exists())
            assertTrue(out.readBytes().contentEquals(fakeDb))
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun decrypt_rejectsShortKey() {
        val ex = assertFailsWith<Crypt15Decryptor.DecryptException> {
            Crypt15Decryptor.decryptToFile(ByteArray(64), ByteArray(16), Files.createTempDirectory("c15").toFile())
        }
        assertTrue(ex.message!!.contains("32 bytes"), ex.message)
    }

    @Test
    fun decrypt_truncatedFile_throws() {
        assertFailsWith<Crypt15Decryptor.DecryptException> {
            Crypt15Decryptor.decryptToFile(
                byteArrayOf(0x40, 0x0A), // claims 64-byte header, has 1
                ByteArray(32),
                Files.createTempDirectory("c15").toFile(),
            )
        }
    }

    @Test
    fun decrypt_headerWithoutIv_throws() {
        // header with only a varint field, no 16-byte blob
        val inner = byteArrayOf(0x08, 0x01) // field 1 varint = 1
        val blob = varint(inner.size.toLong()) + inner + ByteArray(32)
        val ex = assertFailsWith<Crypt15Decryptor.DecryptException> {
            Crypt15Decryptor.decryptToFile(blob, ByteArray(32), Files.createTempDirectory("c15").toFile())
        }
        assertTrue(ex.message!!.contains("IV"), ex.message)
    }

    @Test
    fun decrypt_wrongKey_throwsDecryptException() {
        val rightKey = ByteArray(32) { 11 }
        val iv = ByteArray(16) { 5 }
        val blob = encryptBlob("db-bytes".toByteArray(), rightKey, iv)
        assertFailsWith<Crypt15Decryptor.DecryptException> {
            Crypt15Decryptor.decryptToFile(blob, ByteArray(32) { 12 }, Files.createTempDirectory("c15").toFile())
        }
    }

    @Test
    fun sha256_sanity() {
        val digest = MessageDigest.getInstance("SHA-256").digest("abc".toByteArray())
        assertEquals(32, digest.size)
    }
}
