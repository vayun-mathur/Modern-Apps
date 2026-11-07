package com.vayunmathur.files.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class FilesListingTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun tempDir(name: String): File =
        File(ctx.cacheDir, "listing-$name-${System.nanoTime()}").apply { mkdirs() }

    private fun item(name: String, dir: Boolean = false, size: Long? = 1, modified: Long = 0) =
        FileBrowserItem(
            name = name, isDirectory = dir, size = size,
            realFile = null, zipInnerPath = null, key = name, lastModified = modified,
        )

    @Test
    fun `sort by name date size and type both directions`() {
        val items = listOf(
            item("b.txt", size = 30, modified = 3),
            item("A.md", size = 10, modified = 1),
            item("c.txt", size = 20, modified = 2),
        )
        assertEquals(listOf("A.md", "b.txt", "c.txt"), sortItemsList(items, SortBy.NAME, true).map { it.name })
        assertEquals(listOf("c.txt", "b.txt", "A.md"), sortItemsList(items, SortBy.NAME, false).map { it.name })
        assertEquals(listOf("A.md", "c.txt", "b.txt"), sortItemsList(items, SortBy.DATE, true).map { it.name })
        assertEquals(listOf("A.md", "c.txt", "b.txt"), sortItemsList(items, SortBy.SIZE, true).map { it.name })
        // TYPE groups by extension first (md before txt), then name
        assertEquals(listOf("A.md", "b.txt", "c.txt"), sortItemsList(items, SortBy.TYPE, true).map { it.name })
        val (dirs, files) = sortListing(listOf(item("zdir", dir = true)) to items, SortBy.NAME, true)
        assertEquals(listOf("zdir"), dirs.map { it.name })
        assertEquals(3, files.size)
    }

    @Test
    fun `list real dir partitions and hides dotfiles unless asked`() {
        val dir = tempDir("real")
        File(dir, "visible.txt").writeText("hi")
        File(dir, ".hidden").writeText("secret")
        File(dir, "sub").mkdirs()
        val (dirs, files) = listRealDirItems(dir, showHidden = false)
        assertEquals(listOf("sub"), dirs.map { it.name })
        assertEquals(listOf("visible.txt"), files.map { it.name })
        val (dirs2, files2) = listRealDirItems(dir, showHidden = true)
        assertEquals(1, dirs2.size)
        assertEquals(setOf("visible.txt", ".hidden"), files2.map { it.name }.toSet())
        // missing directory lists as empty rather than crashing
        assertEquals(emptyList<FileBrowserItem>() to emptyList(), listRealDirItems(File(dir, "nope"), false))
        dir.deleteRecursively()
    }

    @Test
    fun `file toBrowserItem carries real file facts`() {
        val dir = tempDir("item")
        val f = File(dir, "note.txt").apply { writeText("hello") }
        val bi = f.toBrowserItem()
        assertEquals("note.txt", bi.name)
        assertFalse(bi.isDirectory)
        assertEquals(5L, bi.size)
        assertEquals(f, bi.realFile)
        assertEquals(f.absolutePath, bi.key)
        assertNull(bi.zipInnerPath)
        val dirItem = dir.toBrowserItem()
        assertTrue(dirItem.isDirectory)
        assertNull(dirItem.size)
        dir.deleteRecursively()
    }

    private fun makeZip(dir: File, name: String, entries: Map<String, String?>): File {
        val zip = File(dir, name)
        ZipOutputStream(zip.outputStream()).use { out ->
            for ((entryName, content) in entries) {
                out.putNextEntry(ZipEntry(entryName))
                if (content != null) out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return zip
    }

    @Test
    fun `list zip root shows folders and files`() {
        val dir = tempDir("zip")
        val zip = makeZip(
            dir, "a.zip",
            mapOf("docs/readme.txt" to "hello", "docs/inner/deep.txt" to "x", "top.txt" to "top", "emptydir/" to null),
        )
        val (dirs, files) = listZipDirItems(zip, "")
        assertEquals(setOf("docs", "emptydir"), dirs.map { it.name }.toSet())
        assertEquals(listOf("top.txt"), files.map { it.name })
        assertEquals("zip:docs", dirs.first { it.name == "docs" }.key)
        assertEquals(3L, files.first().size)

        val (subDirs, subFiles) = listZipDirItems(zip, "docs")
        assertEquals(listOf("inner"), subDirs.map { it.name })
        assertEquals(listOf("readme.txt"), subFiles.map { it.name })
        assertEquals("docs/readme.txt", subFiles.first().zipInnerPath)

        val (deepDirs, deepFiles) = listZipDirItems(zip, "docs/inner")
        assertTrue(deepDirs.isEmpty())
        assertEquals(listOf("deep.txt"), deepFiles.map { it.name })
        dir.deleteRecursively()
    }

    @Test
    fun `list zip of corrupt or missing file is empty`() {
        val dir = tempDir("badzip")
        val bad = File(dir, "bad.zip").apply { writeText("not a zip") }
        assertEquals(emptyList<FileBrowserItem>() to emptyList(), listZipDirItems(bad, ""))
        assertEquals(emptyList<FileBrowserItem>() to emptyList(), listZipDirItems(File(dir, "gone.zip"), ""))
        dir.deleteRecursively()
    }

    @Test
    fun `unique destination suffixes collisions`() {
        val dir = tempDir("unique")
        assertEquals("a.txt", uniqueDestination(dir, "a.txt").name)
        File(dir, "a.txt").writeText("1")
        assertEquals("a (1).txt", uniqueDestination(dir, "a.txt").name)
        File(dir, "a (1).txt").writeText("2")
        assertEquals("a (2).txt", uniqueDestination(dir, "a.txt").name)
        File(dir, "README").writeText("x")
        assertEquals("README (1)", uniqueDestination(dir, "README").name)
        dir.deleteRecursively()
    }

    @Test
    fun `atomic move renames files and directories`() {
        val dir = tempDir("move")
        val src = File(dir, "src.txt").apply { writeText("payload") }
        val dest = File(dir, "dest.txt")
        src.atomicMoveTo(dest)
        assertFalse(src.exists())
        assertEquals("payload", dest.readText())
        val srcDir = File(dir, "srcdir").apply { mkdirs(); File(this, "in.txt").writeText("in") }
        val destDir = File(dir, "destdir")
        srcDir.atomicMoveTo(destDir)
        assertFalse(srcDir.exists())
        assertEquals("in", File(destDir, "in.txt").readText())
        dir.deleteRecursively()
    }

    @Test
    fun `atomic move refuses to overwrite an existing destination`() {
        val dir = tempDir("move-clash")
        val src = File(dir, "src.txt").apply { writeText("payload") }
        val dest = File(dir, "dest.txt").apply { writeText("original") }
        val failure = runCatching { src.atomicMoveTo(dest) }
        assertTrue(failure.exceptionOrNull() is java.io.IOException, "expected IOException, got $failure")
        assertEquals("original", dest.readText())
        assertEquals("payload", src.readText())
        // Moving a file onto itself is a harmless no-op, not an error.
        src.atomicMoveTo(src)
        assertEquals("payload", src.readText())
        dir.deleteRecursively()
    }

    @Test
    fun `sanitized file name drops any directory part`() {
        assertEquals("report.txt", sanitizedFileName("report.txt"))
        assertEquals("evil.txt", sanitizedFileName("../../evil.txt"))
        assertEquals("evil.txt", sanitizedFileName("sub/dir/evil.txt"))
        assertEquals("evil.txt", sanitizedFileName("/abs/evil.txt"))
        assertNull(sanitizedFileName(null))
        assertNull(sanitizedFileName(""))
        assertNull(sanitizedFileName("   "))
        assertNull(sanitizedFileName("."))
        assertNull(sanitizedFileName(".."))
        assertNull(sanitizedFileName("/"))
    }

    @Test
    fun `bookmarks load only existing paths sorted by name`() {
        val dir = tempDir("bm")
        val b = File(dir, "beta").apply { mkdirs() }
        val a = File(dir, "Alpha").apply { mkdirs() }
        val prefs = ctx.getSharedPreferences("test-bm-${System.nanoTime()}", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("bookmarks", setOf(b.absolutePath, a.absolutePath, "/nonexistent/zzz")).commit()
        val loaded = loadBookmarkItems(prefs)
        assertEquals(listOf("Alpha", "beta"), loaded.map { it.name })
        assertTrue(loaded.all { it.isDirectory })
        dir.deleteRecursively()
    }

    @Test
    fun `media queries on empty provider return empty`() {
        assertTrue(queryRecentItems(ctx).isEmpty())
        assertTrue(queryCategoryItems(ctx, FileCategory.IMAGES).isEmpty())
        assertTrue(queryCategoryItems(ctx, FileCategory.VIDEOS).isEmpty())
        assertTrue(queryCategoryItems(ctx, FileCategory.AUDIO).isEmpty())
        assertTrue(queryCategoryItems(ctx, FileCategory.DOCUMENTS).isEmpty())
        assertEquals(emptyList(), queryCategoryItems(ctx, FileCategory.DOWNLOADS))
        assertTrue(queryTrashedItems(ctx).isEmpty())
        assertTrue(resolveTrashableUris(ctx, emptyList()).isEmpty())
    }

    @Test
    fun `storage info used bytes never negative`() {
        assertEquals(30, StorageInfo(100, 70).usedBytes)
        assertEquals(0, StorageInfo(50, 80).usedBytes)
        assertEquals(0, StorageInfo(0, 0).usedBytes)
    }

    @Test
    fun `category labels are distinct resources`() {
        val labels = FileCategory.entries.map { categoryLabel(it) }
        assertEquals(labels.size, labels.toSet().size)
        assertTrue(labels.all { it != 0 })
    }

    @Test
    fun `noop actions are all callable`() {
        val a = FilesActions.Noop
        a.clearSelection(); a.navigateTo(File("/")); a.handleBack()
        a.createFolder("x"); a.openTrash(); a.showMessage("m")
        assertFalse(a.handleBack())
        assertNotNull(a.toString())
    }
}
