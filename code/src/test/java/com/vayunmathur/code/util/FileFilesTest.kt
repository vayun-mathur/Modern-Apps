package com.vayunmathur.code.util

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unit tests for [FileFiles] against real temp directories (pure JVM). */
class FileFilesTest {

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "filefiles-${System.nanoTime()}").also { it.mkdirs() }

    @Test
    fun listChildrenSortsDirsFirstThenCaseInsensitive() {
        val dir = tempDir()
        File(dir, "zebra.txt").writeText("z")
        File(dir, "Apple.txt").writeText("a")
        File(dir, "subdir").mkdir()
        val names = FileFiles.listChildren(dir).map { it.name }
        assertEquals(listOf("subdir", "Apple.txt", "zebra.txt"), names)
        val entries = FileFiles.listChildren(dir)
        assertTrue(entries.first().isDirectory)
        assertFalse(entries[1].isDirectory)
        dir.deleteRecursively()
    }

    @Test
    fun listChildrenOfMissingDirIsEmpty() {
        assertTrue(FileFiles.listChildren(File("/nonexistent-dir-xyz-123")).isEmpty())
    }

    @Test
    fun entryForWrapsFile() {
        val dir = tempDir()
        val f = File(dir, "a.txt").also { it.writeText("hi") }
        val entry = FileFiles.entryFor(f)
        assertEquals("a.txt", entry.name)
        assertFalse(entry.isDirectory)
        assertEquals(FileFiles.entryFor(dir).isDirectory, true)
        dir.deleteRecursively()
    }

    @Test
    fun readWriteTextRoundTrips() {
        val dir = tempDir()
        val f = File(dir, "a.txt")
        FileFiles.writeText(f, "hello")
        assertEquals("hello", FileFiles.readText(f))
        dir.deleteRecursively()
    }

    @Test
    fun readWriteBytesRoundTrips() {
        val dir = tempDir()
        val f = File(dir, "a.bin")
        val bytes = byteArrayOf(0, 1, 2, -1, 127)
        FileFiles.writeBytes(f, bytes)
        assertTrue(FileFiles.readBytes(f).contentEquals(bytes))
        dir.deleteRecursively()
    }

    @Test
    fun writeBytesReplacesAtomicallyAndLeavesNoTemp() {
        val dir = tempDir()
        val f = File(dir, "a.bin").also { it.writeText("old contents") }
        FileFiles.writeBytes(f, byteArrayOf(9, 8, 7))
        assertTrue(FileFiles.readBytes(f).contentEquals(byteArrayOf(9, 8, 7)))
        // No temp sibling may survive a successful (or failed) atomic write.
        assertEquals(listOf("a.bin"), dir.list()?.toList())
        dir.deleteRecursively()
    }

    @Test
    fun createFileCreatesAndRefusesDuplicates() {
        val dir = tempDir()
        assertNotNull(FileFiles.createFile(dir, "new.txt"))
        assertTrue(File(dir, "new.txt").isFile)
        assertNull(FileFiles.createFile(dir, "new.txt"))
        dir.deleteRecursively()
    }

    @Test
    fun createDirectoryCreates() {
        val dir = tempDir()
        val created = FileFiles.createDirectory(dir, "sub")
        assertNotNull(created)
        assertTrue(File(dir, "sub").isDirectory)
        dir.deleteRecursively()
    }

    @Test
    fun renameMovesFile() {
        val dir = tempDir()
        val f = File(dir, "old.txt").also { it.writeText("x") }
        val renamed = FileFiles.rename(f, "new.txt")
        assertNotNull(renamed)
        assertEquals("new.txt", renamed.name)
        assertTrue(File(dir, "new.txt").isFile)
        assertFalse(File(dir, "old.txt").exists())
        dir.deleteRecursively()
    }

    @Test
    fun deleteRemovesFilesAndTrees() {
        val dir = tempDir()
        val sub = File(dir, "sub").also { it.mkdir() }
        File(sub, "a.txt").writeText("x")
        assertTrue(FileFiles.delete(sub))
        assertFalse(sub.exists())
        assertTrue(FileFiles.delete(File(dir, "missing")))
        dir.deleteRecursively()
    }

    @Test
    fun mimeForFileName() {
        assertEquals("application/json", FileFiles.mimeForFileName("a.json"))
        assertEquals("text/xml", FileFiles.mimeForFileName("a.xml"))
        assertEquals("text/html", FileFiles.mimeForFileName("a.html"))
        assertEquals("text/html", FileFiles.mimeForFileName("a.htm"))
        assertEquals("text/css", FileFiles.mimeForFileName("a.css"))
        assertEquals("text/javascript", FileFiles.mimeForFileName("a.js"))
        assertEquals("text/javascript", FileFiles.mimeForFileName("a.mjs"))
        assertEquals("text/javascript", FileFiles.mimeForFileName("a.cjs"))
        assertEquals("text/markdown", FileFiles.mimeForFileName("a.md"))
        assertEquals("text/markdown", FileFiles.mimeForFileName("a.markdown"))
        assertEquals("text/plain", FileFiles.mimeForFileName("a.kt"))
        assertEquals("text/plain", FileFiles.mimeForFileName("noext"))
        assertEquals("application/json", FileFiles.mimeForFileName("A.JSON"))
    }
}
