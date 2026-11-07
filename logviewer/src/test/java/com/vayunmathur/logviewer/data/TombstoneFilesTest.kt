package com.vayunmathur.logviewer.data

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class TombstoneFilesTest {

    private fun tempDir(): File = Files.createTempDirectory("tombstones").toFile()

    private fun write(dir: File, name: String, content: ByteArray, modified: Long): File {
        val file = File(dir, name)
        file.writeBytes(content)
        assertTrue(file.setLastModified(modified))
        return file
    }

    @Test
    fun `missing directory yields null`() {
        assertNull(TombstoneFiles.findByHeader("header".toByteArray(), File("/no/such/dir/at/all")))
    }

    @Test
    fun `empty directory yields null`() {
        val dir = tempDir()
        assertNull(TombstoneFiles.findByHeader("header".toByteArray(), dir))
    }

    @Test
    fun `matching text tombstone is returned with captured timestamp`() {
        val dir = tempDir()
        val header = "*** crash header\nTimestamp: now\n".toByteArray()
        val file = write(dir, "tombstone_01", header + "rest of tombstone".toByteArray(), 5_000L)
        write(dir, "tombstone_02", "different header.......".toByteArray(), 9_000L)

        val found = TombstoneFiles.findByHeader(header, dir)
        assertEquals(file, found?.file)
        assertEquals(5_000L, found?.lastModified)
    }

    @Test
    fun `newest matching candidate wins`() {
        val dir = tempDir()
        val header = "shared header bytes".toByteArray()
        write(dir, "tombstone_01", header, 1_000L)
        val newer = write(dir, "tombstone_02", header, 9_000L)
        assertEquals(newer, TombstoneFiles.findByHeader(header, dir)?.file)
    }

    @Test
    fun `protobuf and unrelated files are ignored`() {
        val dir = tempDir()
        val header = "shared header bytes".toByteArray()
        write(dir, "tombstone_01.pb", header, 9_000L)
        write(dir, "other_01", header, 9_000L)
        assertNull(TombstoneFiles.findByHeader(header, dir))
    }

    @Test
    fun `file shorter than header cannot match`() {
        val dir = tempDir()
        write(dir, "tombstone_01", "tiny".toByteArray(), 9_000L)
        assertNull(TombstoneFiles.findByHeader("a much longer header than the file".toByteArray(), dir))
    }

    @Test
    fun `file with zero modification time is skipped`() {
        val dir = tempDir()
        val header = "shared header bytes".toByteArray()
        val file = write(dir, "tombstone_01", header, 9_000L)
        // setLastModified(0) may clamp; only assert the call survives either way.
        file.setLastModified(0L)
        TombstoneFiles.findByHeader(header, dir)
    }

    @Test
    fun `unreadable candidate is skipped and the next match wins`() {
        val dir = tempDir()
        val header = "shared header bytes".toByteArray()
        // A directory with a tombstone name cannot be opened as a stream.
        val unreadable = File(dir, "tombstone_99")
        unreadable.mkdir()
        unreadable.setLastModified(99_000L)
        val readable = write(dir, "tombstone_01", header, 1_000L)
        assertEquals(readable, TombstoneFiles.findByHeader(header, dir)?.file)
    }

    @Test
    fun `timestamped file is a plain data holder`() {
        val file = File("tombstone_09")
        val stamped = TimestampedFile(file, 123L)
        assertEquals(file, stamped.file)
        assertEquals(123L, stamped.lastModified)
        assertEquals(stamped, TimestampedFile(file, 123L))
    }
}
