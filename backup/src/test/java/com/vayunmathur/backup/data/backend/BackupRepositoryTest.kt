package com.vayunmathur.backup.data.backend

import com.vayunmathur.backup.domain.crypto.Crypto
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InMemoryBackend(override val displayName: String = "memory") : BackupBackend {
    val blobs = mutableMapOf<String, ByteArray>()
    val dirs = mutableSetOf<String>()
    override suspend fun ensureDir(path: String) { dirs.add(path) }
    override suspend fun write(path: String, writer: suspend (OutputStream) -> Unit) {
        val out = ByteArrayOutputStream(); writer(out); blobs[path] = out.toByteArray()
    }
    override suspend fun <T> read(path: String, reader: suspend (InputStream) -> T): T {
        val bytes = blobs[path] ?: throw FileNotFoundException(path)
        return bytes.inputStream().use { reader(it) }
    }
    override suspend fun list(dir: String): List<String> {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        return blobs.keys.filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }.filter { '/' !in it }.sorted()
    }
    override suspend fun delete(path: String) {
        blobs.keys.removeAll { it == path || it.startsWith("$path/") }; dirs.remove(path)
    }
    override suspend fun exists(path: String): Boolean = path in blobs || path in dirs ||
        blobs.keys.any { it.startsWith("$path/") }
}

class BackupRepositoryTest {
    private val key = ByteArray(32) { (it * 3).toByte() }
    private fun repo(b: InMemoryBackend = InMemoryBackend()) = BackupRepository(b, Crypto(key)) to b

    @Test fun encryptedRoundTripAndPaths() = runBlocking<Unit> {
        val (repo, backend) = repo()
        repo.writeEncrypted("metadata.json", "hello".toByteArray())
        assertTrue(repo.exists("metadata.json"))
        assertFalse(backend.blobs.getValue("metadata.json").contentEquals("hello".toByteArray()))
        assertEquals("hello", repo.readEncrypted("metadata.json").decodeToString())
        val out = ByteArrayOutputStream(); repo.readEncryptedTo("metadata.json", out)
        assertEquals("hello", out.toString())
        repo.writeEncrypted(BackupRepository.kvPath("com.a", "k1"), "v".byteInputStream())
        assertEquals("v", repo.readEncrypted("kv/com.a/k1").decodeToString())
        assertEquals(listOf("k1"), repo.list(BackupRepository.kvPackageDir("com.a")))
        repo.delete(BackupRepository.kvPath("com.a", "k1"))
        assertFalse(repo.exists("kv/com.a/k1"))
        assertEquals("metadata.json", BackupRepository.METADATA)
        assertEquals("kv/com.a/k1", BackupRepository.kvPath("com.a", "k1"))
        assertEquals("kv/com.a", BackupRepository.kvPackageDir("com.a"))
        assertEquals("full/com.a", BackupRepository.fullPath("com.a"))
        assertEquals("files/abc", BackupRepository.filePath("abc"))
    }

    @Test fun backendDefaultHelpers() = runBlocking<Unit> {
        val backend = InMemoryBackend("disp")
        assertEquals("disp", backend.displayName)
        backend.save("a/b.bin", byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), backend.load("a/b.bin"))
        assertContentEquals(byteArrayOf(1, 2, 3), backend.loadOrNull("a/b.bin"))
        assertEquals(null, backend.loadOrNull("missing"))
        assertFailsWith<FileNotFoundException> { backend.load("missing") }
        assertEquals(0, BackupBackend.emptyStream().readBytes().size)
        val out = ByteArrayOutputStream(); BackupBackend.bytesWriter(byteArrayOf(9))(out)
        assertContentEquals(byteArrayOf(9), out.toByteArray())
        backend.ensureDir("a"); assertTrue(backend.exists("a"))
        backend.delete("a/b.bin"); assertFalse(backend.exists("a/b.bin"))
    }

    @Test fun wrongKeyCannotRead() = runBlocking<Unit> {
        val backend = InMemoryBackend()
        BackupRepository(backend, Crypto(key)).writeEncrypted("x", "secret".toByteArray())
        val other = BackupRepository(backend, Crypto(ByteArray(32) { 9 }))
        assertFailsWith<Exception> { other.readEncrypted("x") }
    }
}
