package com.vayunmathur.backup.data.files

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.backup.data.backend.BackupRepository
import com.vayunmathur.backup.data.backend.InMemoryBackend
import com.vayunmathur.backup.domain.crypto.Crypto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class FileBackupManagerTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val key = ByteArray(32) { (it + 2).toByte() }

    private fun setup(): Triple<BackupRepository, InMemoryBackend, FileBackupManager> {
        val backend = InMemoryBackend()
        val repo = BackupRepository(backend, Crypto(key))
        return Triple(repo, backend, FileBackupManager(ctx, repo))
    }

    @Test fun backupWithNoMediaWritesEmptyIndex() = runBlocking<Unit> {
        val (repo, _, manager) = setup()
        val progress = mutableListOf<Pair<Int, Int>>()
        val count = manager.backupAll { d, t -> progress.add(d to t) }
        assertEquals(0, count)
        assertTrue(progress.isNotEmpty()); assertEquals(0 to 0, progress.first())
        assertEquals(emptyList(), manager.listBackedUp())
        assertTrue(repo.exists("files/index.json"))
    }

    @Test fun corruptIndexYieldsEmptyList() = runBlocking<Unit> {
        val (repo, _, manager) = setup()
        repo.writeEncrypted("files/index.json", "not json".toByteArray())
        assertEquals(emptyList(), manager.listBackedUp())
    }

    @Test fun restoreWritesDecryptedFiles() = runBlocking<Unit> {
        val (repo, _, manager) = setup()
        val entries = listOf(
            FileEntry("id1", "photo.jpg", "Pictures/", "image/jpeg", 3, 100L, "image"),
            FileEntry("id2", "song.mp3", "Music/", "audio/mpeg", 4, 200L, "audio"),
            FileEntry("missing", "gone.bin", "", "application/octet-stream", 1, 0L, "image"),
        )
        repo.writeEncrypted("files/index.json", Json.encodeToString(FileIndex.serializer(), FileIndex(entries)).toByteArray())
        repo.writeEncrypted(BackupRepository.filePath("id1"), "abc".toByteArray())
        repo.writeEncrypted(BackupRepository.filePath("id2"), "wxyz".toByteArray())
        assertEquals(entries, manager.listBackedUp())
        val progress = mutableListOf<Pair<Int, Int>>()
        val restored = manager.restoreAll { d, t -> progress.add(d to t) }
        // Third entry has no blob, so its write fails and is not counted.
        assertEquals(2, restored)
        val base = File(ctx.getExternalFilesDir(null), "BackupRestore")
        assertEquals("abc", File(base, "Pictures/photo.jpg").readText())
        assertEquals("wxyz", File(base, "Music/song.mp3").readText())
        assertEquals(0 to 3, progress.first()); assertEquals(3 to 3, progress.last())
    }

    @Test fun fileEntryAndIndexSerialization() {
        val e = FileEntry("id", "n", "p/", "text/plain", 12L, 34L, "image")
        assertEquals(e, FileEntry(e.fileId, e.displayName, e.relativePath, e.mimeType, e.size, e.dateModified, e.mediaType))
        val json = Json.encodeToString(FileIndex.serializer(), FileIndex(listOf(e)))
        assertEquals(listOf(e), Json.decodeFromString<FileIndex>(json).files)
        assertEquals(emptyList(), FileIndex().files)
        assertTrue(json.contains("\"fileId\":\"id\""))
    }
}
