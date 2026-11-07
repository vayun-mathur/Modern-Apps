package com.vayunmathur.library.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** BackupHelper zip staging, ZipBackupFormat delegation and a fake DbBackupCodec. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class LibraryBackupTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun createTempDir(name: String): File =
        java.nio.file.Files.createTempDirectory(name).toFile()

    private class FakeCodec(private val payload: String) : DbBackupCodec {
        val exported = mutableListOf<String>()
        val imported = mutableListOf<String>()
        override fun exportDatabase(context: Context, dbName: String, password: String, outputFile: File) {
            exported += "$dbName:$password"
            outputFile.writeText(payload)
        }
        override fun importDatabase(context: Context, dbName: String, password: String, inputFile: File) {
            imported += "$dbName:$password:" + inputFile.readText()
        }
    }

    @Test fun zipUnzipRoundTripsNestedFiles() {
        val base = createTempDir("zipbase")
        File(base, "a.txt").writeText("alpha")
        File(base, "sub").mkdirs()
        File(base, "sub/b.txt").writeText("beta")
        val out = ByteArrayOutputStream()
        BackupHelper.zipFiles(listOf(base), base, out)
        val target = createTempDir("zipout")
        BackupHelper.unzipFiles(ByteArrayInputStream(out.toByteArray()), target)
        assertEquals("alpha", File(target, "a.txt").readText())
        assertEquals("beta", File(target, "sub/b.txt").readText())
    }

    @Test fun zipEmptyListProducesValidEmptyZip() {
        val out = ByteArrayOutputStream()
        BackupHelper.zipFiles(emptyList(), createTempDir("emptybase"), out)
        val target = createTempDir("emptyout")
        BackupHelper.unzipFiles(ByteArrayInputStream(out.toByteArray()), target)
        assertTrue(target.listFiles()?.isEmpty() != false)
    }

    @Test fun fullBackupAndRestoreExtraFilesAndPrefsAndDatastore() {
        val extra = File(ctx.cacheDir, "extra-note.txt").apply { writeText("extra-body") }
        val dsDir = File(ctx.filesDir, "datastore").apply { mkdirs() }
        File(dsDir, "mystore.preferences_pb").writeBytes(byteArrayOf(1, 2, 3))
        val prefDir = File(ctx.dataDir, "shared_prefs").apply { mkdirs() }
        File(prefDir, "myprefs.xml").writeText("<map/>")

        val out = ByteArrayOutputStream()
        BackupHelper.performFullBackup(
            ctx,
            dbConfigs = emptyList(),
            datastoreNames = listOf("mystore"),
            prefNames = listOf("myprefs"),
            extraFiles = listOf(extra, File(ctx.cacheDir, "missing.bin")),
            outputStream = out,
        )
        assertTrue(out.size() > 0)

        // Wipe the originals, then restore into place.
        extra.delete(); File(dsDir, "mystore.preferences_pb").delete(); File(prefDir, "myprefs.xml").delete()
        BackupHelper.performFullRestore(
            ctx,
            dbConfigs = emptyList(),
            datastoreNames = listOf("mystore"),
            prefNames = listOf("myprefs"),
            extraFilesMapping = mapOf("extra-note.txt" to extra),
            inputStream = ByteArrayInputStream(out.toByteArray()),
        )
        assertEquals("extra-body", extra.readText())
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(File(dsDir, "mystore.preferences_pb").readBytes()))
        assertEquals("<map/>", File(prefDir, "myprefs.xml").readText())
    }

    @Test fun fullBackupWithCodecExportsDatabaseAndRestoreImportsIt() {
        val codec = FakeCodec("db-bytes")
        val out = ByteArrayOutputStream()
        BackupHelper.performFullBackup(ctx, listOf("notes" to "pw1"), emptyList(), emptyList(), emptyList(), out, codec)
        assertEquals(listOf("notes:pw1"), codec.exported)
        BackupHelper.performFullRestore(
            ctx, listOf("notes" to "pw1"), emptyList(), emptyList(), emptyMap(),
            ByteArrayInputStream(out.toByteArray()), codec,
        )
        assertEquals(listOf("notes:pw1:db-bytes"), codec.imported)
    }

    @Test fun backupWithoutCodecSkipsDatabasesButStillZipsExtras() {
        val extra = File(ctx.cacheDir, "only.txt").apply { writeText("x") }
        val out = ByteArrayOutputStream()
        BackupHelper.performFullBackup(ctx, listOf("db" to "pw"), emptyList(), emptyList(), listOf(extra), out, null)
        val target = createTempDir("nocodec")
        BackupHelper.unzipFiles(ByteArrayInputStream(out.toByteArray()), target)
        assertEquals("x", File(target, "only.txt").readText())
    }

    @Test fun restoreWithMissingEntriesIsNoOp() {
        val out = ByteArrayOutputStream()
        BackupHelper.zipFiles(emptyList(), createTempDir("b"), out)
        // Nothing in the zip matches the requested names: must not throw or create files.
        BackupHelper.performFullRestore(
            ctx, listOf("nope" to "pw"), listOf("ghost"), listOf("ghost"),
            mapOf("ghost.txt" to File(ctx.cacheDir, "ghost.txt")),
            ByteArrayInputStream(out.toByteArray()), FakeCodec("z"),
        )
        assertFalse(File(ctx.cacheDir, "ghost.txt").exists())
    }

    @Test fun defaultDatastoreRestoresToFilesRoot() {
        val src = File(ctx.filesDir, "datastore_default.preferences_pb").apply { writeBytes(byteArrayOf(9)) }
        val out = ByteArrayOutputStream()
        BackupHelper.performFullBackup(ctx, emptyList(), listOf("datastore_default"), emptyList(), emptyList(), out)
        src.delete()
        BackupHelper.performFullRestore(
            ctx, emptyList(), listOf("datastore_default"), emptyList(), emptyMap(),
            ByteArrayInputStream(out.toByteArray()),
        )
        assertTrue(src.exists())
        assertTrue(byteArrayOf(9).contentEquals(src.readBytes()))
    }

    @Test fun extraDirectoryIsCopiedRecursively() {
        val dir = File(ctx.cacheDir, "extradir").apply { mkdirs() }
        File(dir, "inner.txt").writeText("inner")
        val out = ByteArrayOutputStream()
        BackupHelper.performFullBackup(ctx, emptyList(), emptyList(), emptyList(), listOf(dir), out)
        val restored = File(ctx.cacheDir, "restoreddir")
        restored.deleteRecursively()
        BackupHelper.performFullRestore(
            ctx, emptyList(), emptyList(), emptyList(),
            mapOf("extradir" to restored), ByteArrayInputStream(out.toByteArray()),
        )
        assertEquals("inner", File(restored, "inner.txt").readText())
    }

    @Test fun zipFormatWithCodecAndNamedStoresDelegates() = runBlocking {
        val codec = FakeCodec("fmt-db")
        val format = ZipBackupFormat(
            dbConfigs = listOf("main" to "pw"),
            datastoreNames = listOf("mystore"),
            prefNames = listOf("myprefs"),
            dbCodec = codec,
        )
        val dsDir = File(ctx.filesDir, "datastore").apply { mkdirs() }
        File(dsDir, "mystore.preferences_pb").writeBytes(byteArrayOf(7))
        val prefDir = File(ctx.dataDir, "shared_prefs").apply { mkdirs() }
        File(prefDir, "myprefs.xml").writeText("<map><int name=\"a\" value=\"1\"/></map>")
        val out = ByteArrayOutputStream()
        format.export(ctx, null, out)
        assertEquals(listOf("main:pw"), codec.exported)
        File(dsDir, "mystore.preferences_pb").delete()
        File(prefDir, "myprefs.xml").delete()
        format.import(ctx, null, ByteArrayInputStream(out.toByteArray()))
        assertEquals(listOf("main:pw:fmt-db"), codec.imported)
        assertTrue(File(dsDir, "mystore.preferences_pb").exists())
        assertTrue(File(prefDir, "myprefs.xml").readText().contains("value=\"1\""))
    }

    @Test fun zipFormatMetadataAndDelegation() = runBlocking {
        val format = ZipBackupFormat(extraFiles = emptyList())
        assertEquals("application/zip", format.mimeType)
        assertEquals("backup.zip", format.defaultFileName)
        assertFalse(format.needsPassword)
        val extra = File(ctx.cacheDir, "fmt.txt").apply { writeText("fmt-body") }
        val withExtra = ZipBackupFormat(extraFiles = listOf(extra))
        val out = ByteArrayOutputStream()
        withExtra.export(ctx, null, out)
        assertTrue(out.size() > 0)
        extra.delete()
        withExtra.import(ctx, "ignored", ByteArrayInputStream(out.toByteArray()))
        assertEquals("fmt-body", extra.readText())
    }
}
