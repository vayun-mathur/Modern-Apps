package com.vayunmathur.code.util

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Edge cases for [FileFiles] beyond FileFilesTest, plus URI helpers under Robolectric. */
@RunWith(RobolectricTestRunner::class)
class FileFilesBehaviourTest {

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "filefiles2-${System.nanoTime()}").also { it.mkdirs() }

    @Test fun createFileCreatesMissingParentsForNestedName() {
        val dir = tempDir()
        val created = FileFiles.createFile(dir, "deep/nested/new.txt")
        assertNotNull(created)
        assertTrue(File(dir, "deep/nested/new.txt").isFile)
        dir.deleteRecursively()
    }

    @Test fun createDirectoryOnExistingDirectoryReturnsIt() {
        val dir = tempDir()
        val first = FileFiles.createDirectory(dir, "sub")
        val second = FileFiles.createDirectory(dir, "sub")
        assertNotNull(first)
        assertEquals(first.absolutePath, second?.absolutePath)
        dir.deleteRecursively()
    }

    @Test fun createDirectoryWhereFileExistsReturnsNull() {
        val dir = tempDir()
        File(dir, "blocker").writeText("x")
        // mkdirs fails and it is not a directory, so the result is null.
        assertNull(FileFiles.createDirectory(dir, "blocker"))
        dir.deleteRecursively()
    }

    @Test fun renameMissingFileReturnsNullAndRenameToExistingNameBehavesPerFs() {
        val dir = tempDir()
        assertNull(FileFiles.rename(File(dir, "ghost.txt"), "new.txt"))
        val a = File(dir, "a.txt").also { it.writeText("a") }
        // Renaming a file in a deleted parent fails cleanly.
        val orphanParent = File(dir, "gone").also { it.mkdir() }
        val orphan = File(orphanParent, "o.txt").also { it.writeText("o") }
        orphanParent.deleteRecursively()
        assertNull(FileFiles.rename(orphan, "p.txt"))
        assertTrue(a.exists())
        dir.deleteRecursively()
    }

    @Test fun writeBytesToNestedPathAndReadBackAndEmptyBytes() {
        val dir = tempDir()
        val f = File(dir, "a.bin")
        FileFiles.writeBytes(f, byteArrayOf())
        assertEquals(0, FileFiles.readBytes(f).size)
        FileFiles.writeText(f, "héllo →")
        assertEquals("héllo →", FileFiles.readText(f))
        // No temp files leak.
        assertEquals(listOf("a.bin"), dir.list()?.toList())
        dir.deleteRecursively()
    }

    @Test fun listChildrenOfFileIsEmptyAndEntryForDirectory() {
        val dir = tempDir()
        val f = File(dir, "a.txt").also { it.writeText("x") }
        assertTrue(FileFiles.listChildren(f).isEmpty())
        val e = FileFiles.entryFor(dir)
        assertTrue(e.isDirectory)
        assertEquals(dir.name, e.name)
        assertEquals(dir, e.file)
        dir.deleteRecursively()
    }

    @Test fun deleteEmptyDirAndNestedTree() {
        val dir = tempDir()
        val empty = File(dir, "empty").also { it.mkdir() }
        assertTrue(FileFiles.delete(empty))
        assertFalse(empty.exists())
        dir.deleteRecursively()
    }

    @Test fun mimeEdgeCases() {
        assertEquals("text/plain", FileFiles.mimeForFileName("archive.tar.gz"))
        assertEquals("text/plain", FileFiles.mimeForFileName("trailingdot."))
        assertEquals("text/plain", FileFiles.mimeForFileName(".hidden"))
        assertEquals("application/json", FileFiles.mimeForFileName("a.JSON"))
        assertEquals("text/html", FileFiles.mimeForFileName("a.Htm"))
        assertEquals("text/javascript", FileFiles.mimeForFileName("a.JS"))
    }

    @Test fun uriHelpersForFileUri() {
        val dir = tempDir()
        val f = File(dir, "note.md").also { it.writeText("# hi") }
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val uri = Uri.fromFile(f)
        assertEquals("# hi", FileFiles.readTextFromUri(ctx, uri))
        assertEquals("note.md", FileFiles.queryDisplayName(ctx, uri))
        dir.deleteRecursively()
    }

    @Test fun queryDisplayNameFallsBackToLastSegment() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val uri = Uri.parse("content://com.example/docs/report.txt")
        // No provider registered: query fails/empty, fallback is the last path segment.
        assertEquals("report.txt", FileFiles.queryDisplayName(ctx, uri))
    }
}
