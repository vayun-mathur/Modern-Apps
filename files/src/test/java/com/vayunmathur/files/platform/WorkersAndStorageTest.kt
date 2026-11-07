package com.vayunmathur.files.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.vayunmathur.files.data.saf.StorageUris
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowEnvironmentFix::class])
class WorkersAndStorageTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun tempDir(name: String): File =
        File(ctx.cacheDir, "worker-$name-${System.nanoTime()}").apply { mkdirs() }

    @Test
    fun `zip worker archives files and folders`() { runBlocking {
        val dir = tempDir("zip")
        val srcFile = File(dir, "a.txt").apply { writeText("alpha") }
        val srcDir = File(dir, "folder").apply { mkdirs() }
        File(srcDir, "b.txt").writeText("beta")
        File(dir, "emptyDir").mkdirs()
        val dest = File(dir, "out.zip")
        val worker = TestListenableWorkerBuilder<ZipWorker>(ctx)
            .setInputData(
                workDataOf(
                    "source_paths" to arrayOf(srcFile.absolutePath, srcDir.absolutePath, File(dir, "emptyDir").absolutePath),
                    "dest_path" to dest.absolutePath,
                ),
            )
            .build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertTrue(dest.isFile)
        ZipFile(dest).use { zf ->
            val names = zf.entries().asSequence().map { it.name }.toSet()
            assertTrue("a.txt" in names)
            assertTrue("folder/b.txt" in names)
            assertTrue("emptyDir/" in names)
            assertEquals("alpha", zf.getInputStream(zf.getEntry("a.txt")).readBytes().decodeToString())
        }
        dir.deleteRecursively()
    } }

    @Test
    fun `zip worker fails without input`() { runBlocking {
        val worker = TestListenableWorkerBuilder<ZipWorker>(ctx).build()
        assertEquals(ListenableWorker.Result.failure(), worker.doWork())
    } }

    @Test
    fun `unzip worker extracts and refuses zip slip and budget`() { runBlocking {
        val dir = tempDir("unzip")
        // build an archive with a normal entry, a nested entry and an evil entry
        val zipFile = File(dir, "in.zip")
        java.util.zip.ZipOutputStream(zipFile.outputStream()).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry("hello.txt")); out.write("hello".toByteArray()); out.closeEntry()
            out.putNextEntry(java.util.zip.ZipEntry("sub/world.txt")); out.write("world".toByteArray()); out.closeEntry()
            out.putNextEntry(java.util.zip.ZipEntry("../evil.txt")); out.write("evil".toByteArray()); out.closeEntry()
            out.putNextEntry(java.util.zip.ZipEntry("emptydir/")); out.closeEntry()
        }
        val destDir = File(dir, "out").apply { mkdirs() }
        val worker = TestListenableWorkerBuilder<UnzipWorker>(ctx)
            .setInputData(
                workDataOf(
                    "zip_path" to zipFile.absolutePath,
                    "dest_path" to destDir.absolutePath,
                    "size_budget" to 1_000_000L,
                ),
            )
            .build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals("hello", File(destDir, "hello.txt").readText())
        assertEquals("world", File(destDir, "sub/world.txt").readText())
        assertFalse(File(dir, "evil.txt").exists())
        assertTrue(File(destDir, "emptydir").isDirectory)

        // tiny budget: extraction is refused and partial output cleaned up
        val dest2 = File(dir, "out2").apply { mkdirs() }
        val stingy = TestListenableWorkerBuilder<UnzipWorker>(ctx)
            .setInputData(
                workDataOf(
                    "zip_path" to zipFile.absolutePath,
                    "dest_path" to dest2.absolutePath,
                    "size_budget" to 2L,
                ),
            )
            .build()
        assertEquals(ListenableWorker.Result.failure(), stingy.doWork())
        assertFalse(File(dest2, "hello.txt").exists())

        val noInput = TestListenableWorkerBuilder<UnzipWorker>(ctx).build()
        assertEquals(ListenableWorker.Result.failure(), noInput.doWork())
        dir.deleteRecursively()
    } }

    @Test
    fun `unzip worker refuses to overwrite an existing file`() { runBlocking {
        val dir = tempDir("unzip-clash")
        val zipFile = File(dir, "in.zip")
        java.util.zip.ZipOutputStream(zipFile.outputStream()).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry("fresh.txt")); out.write("fresh".toByteArray()); out.closeEntry()
            out.putNextEntry(java.util.zip.ZipEntry("hello.txt")); out.write("replacement".toByteArray()); out.closeEntry()
        }
        val destDir = File(dir, "out").apply { mkdirs() }
        File(destDir, "hello.txt").writeText("original")
        val worker = TestListenableWorkerBuilder<UnzipWorker>(ctx)
            .setInputData(
                workDataOf(
                    "zip_path" to zipFile.absolutePath,
                    "dest_path" to destDir.absolutePath,
                    "size_budget" to 1_000_000L,
                ),
            )
            .build()
        assertEquals(ListenableWorker.Result.failure(), worker.doWork())
        // the pre-existing file is untouched and this run's partial output is gone
        assertEquals("original", File(destDir, "hello.txt").readText())
        assertFalse(File(destDir, "fresh.txt").exists())
        dir.deleteRecursively()
    } }

    @Test
    fun `storage uris mime matching`() {
        assertEquals("text/plain", StorageUris.mimeTypeOf(File("a.txt")))
        assertEquals("image/png", StorageUris.mimeTypeOf(File("a.PNG")))
        assertEquals("application/octet-stream", StorageUris.mimeTypeOf(File("a.unknownext")))
        assertTrue(StorageUris.matchesMime(File("a.txt"), emptyList()))
        assertTrue(StorageUris.matchesMime(File("a.txt"), listOf("*/*")))
        assertTrue(StorageUris.matchesMime(File("a.txt"), listOf("text/plain")))
        assertTrue(StorageUris.matchesMime(File("a.txt"), listOf("text/*")))
        assertTrue(StorageUris.matchesMime(File("photo.jpg"), listOf("image/*", "text/plain")))
        assertFalse(StorageUris.matchesMime(File("a.txt"), listOf("image/*")))
        assertFalse(StorageUris.matchesMime(File("a.txt"), listOf("noslash")))
        assertTrue(StorageUris.matchesMime(File("a.txt"), listOf("*/plain")))
        // no known volume in the sandbox: document ids are unavailable rather than wrong
        val anyFile = File(ctx.cacheDir, "x.txt").apply { writeText("x") }
        val docId = StorageUris.documentIdFor(ctx, anyFile)
        assertTrue(docId == null || docId.contains(":"))
        // Files outside every configured FileProvider root are refused, which is
        // what keeps a stray path from being handed to another app.
        val outside = File(ctx.cacheDir, "outside-${System.nanoTime()}.txt").apply { writeText("x") }
        val refused = runCatching { StorageUris.fileProviderUriFor(ctx, outside) }
        assertTrue(refused.isFailure)
    }
}
