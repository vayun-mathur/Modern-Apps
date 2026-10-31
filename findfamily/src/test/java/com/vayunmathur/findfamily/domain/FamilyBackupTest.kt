package com.vayunmathur.findfamily.domain

import com.vayunmathur.library.util.BackupHelper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for the manual backup contract ([FamilyBackup]).
 *
 * The zip layer under test is the real [BackupHelper] used on device — only
 * the database codec is stood in for with raw bytes, because SQLCipher needs a
 * device. Every case builds its zip with the historic entry names, so a rename
 * that would orphan older backup files fails here first.
 */
class FamilyBackupTest {

    private val dbBytes = "fake-sqlite-payload-v14".toByteArray()
    private val datastoreBytes = "fake-datastore-identity".toByteArray()

    /** Zips [files] (sitting in [baseDir]) and returns the unzipped name→bytes map. */
    private fun roundTrip(files: List<File>, baseDir: File): Map<String, ByteArray> {
        val zipped = ByteArrayOutputStream().also { BackupHelper.zipFiles(files, baseDir, it) }.toByteArray()
        val outDir = Files.createTempDirectory("ff-restore").toFile()
        try {
            BackupHelper.unzipFiles(ByteArrayInputStream(zipped), outDir)
            return outDir.walkTopDown().filter { it.isFile }.associate { it.name to it.readBytes() }
        } finally {
            outDir.deleteRecursively()
        }
    }

    private fun stageDir(block: (File) -> List<File>): Pair<List<File>, File> {
        val dir = Files.createTempDirectory("ff-backup").toFile()
        return try {
            block(dir) to dir
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    // --- config builders ---

    @Test
    fun dbConfigsCarryHistoricDbName() {
        assertEquals(listOf("passwords-db" to "pw"), FamilyBackup.dbConfigs("pw"))
    }

    @Test
    fun datastoreNamesCarryDefaultStore() {
        assertEquals(listOf("datastore_default"), FamilyBackup.datastoreNames())
    }

    // --- serialize / deserialize round trip ---

    @Test
    fun fullBackupRoundTripsThroughRealZipLayer() {
        val (files, dir) = stageDir { d ->
            listOf(
                File(d, FamilyBackup.dbEntryName()).apply { writeBytes(dbBytes) },
                File(d, FamilyBackup.datastoreEntryName()).apply { writeBytes(datastoreBytes) },
            )
        }
        try {
            val restored = roundTrip(files, dir)
            assertContentEquals(dbBytes, restored[FamilyBackup.dbEntryName()])
            assertContentEquals(datastoreBytes, restored[FamilyBackup.datastoreEntryName()])
            val contents = FamilyBackup.describeEntries(restored.keys)
            assertTrue(contents.isRestorable)
            assertFalse(contents.isLegacy)
        } finally {
            dir.deleteRecursively()
        }
    }

    // --- legacy-format restore ---

    @Test
    fun legacyDatabaseOnlyBackupIsRecognizedAndRestorable() {
        // What backups looked like before the DataStore entry was added: the
        // reader must still accept them (the missing store is skipped on import).
        val (files, dir) = stageDir { d ->
            listOf(File(d, FamilyBackup.dbEntryName()).apply { writeBytes(dbBytes) })
        }
        try {
            val restored = roundTrip(files, dir)
            assertContentEquals(dbBytes, restored[FamilyBackup.dbEntryName()])
            val contents = FamilyBackup.describeEntries(restored.keys)
            assertTrue(contents.isRestorable)
            assertTrue(contents.isLegacy)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun foreignFileIsNotRestorable() {
        val contents = FamilyBackup.describeEntries(listOf("notes-backup.zip", "photo.jpg"))
        assertFalse(contents.isRestorable)
        assertFalse(contents.isLegacy)
    }

    @Test
    fun emptyBackupIsNotRestorable() {
        val contents = FamilyBackup.describeEntries(emptyList())
        assertFalse(contents.isRestorable)
    }
}
