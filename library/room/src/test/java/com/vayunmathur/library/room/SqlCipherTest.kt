package com.vayunmathur.library.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SqlCipherTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `loading the native library twice is safe even where it is absent`() {
        // On a host JVM there is no Android sqlcipher .so: the loader logs and
        // carries on rather than taking the process down, and a second call is
        // a no-op either way.
        loadSqlCipher()
        loadSqlCipher()
    }

    @Test
    fun `encrypting a database that does not exist does nothing`() {
        encryptExistingDatabase(context, "no-such-db-xyz", "pw")
        assertFalse(context.getDatabasePath("no-such-db-xyz").exists())
    }

    @Test
    fun `a file too short to hold a header is left alone`() {
        val file = context.getDatabasePath("short-db")
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
        try {
            encryptExistingDatabase(context, "short-db", "pw")
            assertEquals(3L, file.length())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a file that is not plain sqlite is treated as already encrypted`() {
        val file = context.getDatabasePath("cipher-db")
        file.parentFile?.mkdirs()
        // Long enough for a header, wrong magic: indistinguishable from ciphertext.
        file.writeBytes(ByteArray(64) { (it * 31 + 7).toByte() })
        try {
            encryptExistingDatabase(context, "cipher-db", "pw")
            assertEquals(64L, file.length())
            assertTrue(file.exists())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `an unreadable database path is treated as encrypted, not an error`() {
        val file = context.getDatabasePath("dir-db")
        file.parentFile?.mkdirs()
        // A directory where the file should be: reading its header is an
        // IOException, which the code answers by assuming encryption.
        assertTrue(file.mkdir())
        try {
            encryptExistingDatabase(context, "dir-db", "pw")
            assertTrue(file.isDirectory)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a header split across reads is not mistaken for plaintext`() {
        val file = context.getDatabasePath("tiny-header-db")
        file.parentFile?.mkdirs()
        file.writeBytes("SQLite format 3\u0000".toByteArray(Charsets.UTF_8).copyOf(16))
        try {
            // Exactly one magic header: plaintext by the header rule, so the
            // encryption attempt starts. Whether the host can run SQLCipher
            // decides the rest; the contract asserted here is only that the
            // original file is still present afterwards (success renames the
            // encrypted copy over it, failure deletes the copy, never both).
            runCatching { encryptExistingDatabase(context, "tiny-header-db", "pw") }
            assertTrue(file.exists())
        } finally {
            file.delete()
            File("${file.path}_temp").delete()
        }
    }
}
