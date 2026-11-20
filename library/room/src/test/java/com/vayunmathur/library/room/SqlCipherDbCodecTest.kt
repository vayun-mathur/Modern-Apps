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
    fun `failed import leaves the old database files untouched`() {
        // Temp-verify-swap: the replacement is built beside the live DB and
        // only swapped in after verification. On this host the native layer
        // cannot load (and a corrupt input would fail verification on a
        // device), so the import must fail with the live set intact.
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
            kotlin.test.assertTrue(dbFile.exists())
            kotlin.test.assertTrue(byteArrayOf(1).contentEquals(dbFile.readBytes()))
            kotlin.test.assertTrue(byteArrayOf(2).contentEquals(wal.readBytes()))
            kotlin.test.assertTrue(byteArrayOf(3).contentEquals(shm.readBytes()))
            kotlin.test.assertTrue(byteArrayOf(4).contentEquals(journal.readBytes()))
            // No temp import file is left behind in the databases directory.
            val leftovers = dbFile.parentFile?.listFiles()
                ?.filter { it.name.contains(".import-") || it.name.contains(".bak-") }
                .orEmpty()
            kotlin.test.assertTrue(leftovers.isEmpty(), "leftover swap files: $leftovers")
        } finally {
            dbFile.delete(); wal.delete(); shm.delete(); journal.delete()
            input.delete()
        }
    }

    @Test
    fun `corrupt input leaves the live database untouched`() {
        val dbFile = context.getDatabasePath("corrupt-target-db")
        dbFile.parentFile?.mkdirs()
        val liveBytes = ByteArray(64) { 7 }
        dbFile.writeBytes(liveBytes)
        val input = File(context.cacheDir, "corrupt-plaintext.db")
        input.writeBytes(ByteArray(64) { 0x55 }) // not a SQLite file at all
        try {
            runCatching {
                SqlCipherDbCodec.importDatabase(context, "corrupt-target-db", "pw", input)
            }
            kotlin.test.assertTrue(dbFile.exists())
            kotlin.test.assertTrue(liveBytes.contentEquals(dbFile.readBytes()))
        } finally {
            dbFile.delete()
            input.delete()
        }
    }

    @Test
    fun `missing input leaves the live database untouched`() {
        val dbFile = context.getDatabasePath("missing-input-db")
        dbFile.parentFile?.mkdirs()
        dbFile.writeBytes(byteArrayOf(8, 8, 8))
        val input = File(context.cacheDir, "does-not-exist-plaintext.db")
        if (input.exists()) input.delete()
        try {
            runCatching {
                SqlCipherDbCodec.importDatabase(context, "missing-input-db", "pw", input)
            }
            kotlin.test.assertTrue(dbFile.exists())
            kotlin.test.assertTrue(byteArrayOf(8, 8, 8).contentEquals(dbFile.readBytes()))
        } finally {
            dbFile.delete()
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
