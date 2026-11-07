package com.vayunmathur.library.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SqlCipherDbCodecTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `export prepares the output file before the native open`() {
        // The source exists, so export creates the parent dirs, replaces any
        // stale output, and only then reaches for SQLCipher - which a host
        // JVM cannot load. Assert the pre-native contract, not the failure.
        val dbFile = context.getDatabasePath("export-source-db")
        dbFile.parentFile?.mkdirs()
        dbFile.writeBytes(ByteArray(32) { 4 })
        val output = File(context.cacheDir, "nested/deep/export.db")
        output.parentFile?.mkdirs()
        output.writeBytes(byteArrayOf(9, 9, 9))
        try {
            runCatching {
                SqlCipherDbCodec.exportDatabase(context, "export-source-db", "pw", output)
            }
            kotlin.test.assertTrue(output.exists(), "export should have created the output file")
            kotlin.test.assertEquals(0L, output.length(), "the stale output is replaced, not appended")
        } finally {
            dbFile.delete()
            output.delete()
        }
    }

    @Test
    fun `import deletes the old database files before the native open`() {
        val dbFile = context.getDatabasePath("import-target-db")
        dbFile.parentFile?.mkdirs()
        dbFile.writeBytes(byteArrayOf(1))
        val wal = File("${dbFile.path}-wal").apply { writeBytes(byteArrayOf(2)) }
        val shm = File("${dbFile.path}-shm").apply { writeBytes(byteArrayOf(3)) }
        val journal = File("${dbFile.path}-journal").apply { writeBytes(byteArrayOf(4)) }
        val input = File(context.cacheDir, "import-plaintext.db")
        input.writeBytes(ByteArray(32) { 5 })
        try {
            runCatching {
                SqlCipherDbCodec.importDatabase(context, "import-target-db", "pw", input)
            }
            // Whatever the native layer then does on this host, the old file
            // set is gone first: a half-finished import must not leave a WAL
            // from the previous database behind.
            kotlin.test.assertFalse(wal.exists())
            kotlin.test.assertFalse(shm.exists())
            kotlin.test.assertFalse(journal.exists())
        } finally {
            dbFile.delete()
            input.delete()
        }
    }

    @Test
    fun `exporting a database that does not exist writes nothing`() {
        val output = File(context.cacheDir, "export-should-not-exist.db")
        if (output.exists()) output.delete()
        SqlCipherDbCodec.exportDatabase(context, "no-such-db-xyz", "pw", output)
        assertFalse(output.exists(), "a missing source must not produce an export file")
        assertFalse(context.getDatabasePath("no-such-db-xyz").exists())
    }
}
