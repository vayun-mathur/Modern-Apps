package com.vayunmathur.madeveloper

import com.vayunmathur.madeveloper.data.FileLastGoodStore
import com.vayunmathur.madeveloper.data.GitHostRepository
import com.vayunmathur.madeveloper.data.LastGoodEntry
import com.vayunmathur.madeveloper.data.LastGoodStore
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Last-good disk serving in [GitHostRepository]: an outage must not blank repos/tests/catalog. */
class GitHostLastGoodTest {

    private class MemoryStore : LastGoodStore {
        val entries = mutableMapOf<String, LastGoodEntry>()
        override fun read(key: String): LastGoodEntry? = entries[key]
        override fun write(key: String, text: String, fetchedAtMillis: Long) {
            entries[key] = LastGoodEntry(text, fetchedAtMillis)
        }
    }

    private val reposJson = """[{"name":"Modern-Apps","defaultBranch":"main","headSha":"aabbccdd11223344","headSubject":"top","headTime":"2026-11-16T10:00:00Z"}]"""
    private val testsJson = """{"latest":{"id":"a","sha":"abc123","subject":"fix","status":"passed","failed":0,"tests":10},"history":[]}"""
    private val catalogJson = """{"packages":{"com.example.app":{"metadata":{"name":{"en-US":"Example"},"summary":{"en-US":"Demo"}},"versions":{"h1":{"file":{"name":"/a_1.apk","size":100},"manifest":{"versionCode":1,"versionName":"1.0"}}}}}}"""

    private fun fetcher(fail: () -> Boolean): suspend (String) -> String = { url ->
        if (fail()) throw IOException("HTTP 503 for $url")
        when {
            url.endsWith("/api/repos") -> reposJson
            url.contains("/tests.json") -> testsJson
            url.contains("index-v2.json") -> catalogJson
            else -> throw IOException("unexpected url: $url")
        }
    }

    @Test
    fun `repos tests and catalog serve last-good from disk on failure`() = runTest {
        var fail = false
        val store = MemoryStore()
        val repo = GitHostRepository(fetcher { fail }, store)
        assertFalse(repo.reposResult().stale)
        assertFalse(repo.testsResult().stale)
        assertFalse(repo.catalogResult().stale)
        assertEquals(setOf("repos", "tests", "catalog"), store.entries.keys)

        fail = true
        val repos = repo.reposResult()
        assertTrue(repos.stale)
        assertEquals("Modern-Apps", repos.value.single().name)
        assertTrue(repo.testsResult().stale)
        assertEquals("passed", repo.testsResult().value.latest?.status)
        val catalog = repo.catalogResult()
        assertTrue(catalog.stale)
        assertEquals("Example", catalog.value.single().name)
        // The plain APIs serve the same last-good values, not empty.
        assertEquals(1, repo.repos().size)
        assertEquals(1, repo.catalog().size)
    }

    @Test
    fun `failure with no last-good still throws`() = runTest {
        val repo = GitHostRepository(fetcher { true }, MemoryStore())
        assertFailsWith<IOException> { repo.reposResult() }
        assertFailsWith<IOException> { repo.catalogResult() }
    }

    @Test
    fun `file store round-trips payload and fetch time`() {
        val dir = Files.createTempDirectory("lastgood").toFile()
        try {
            val store = FileLastGoodStore(dir)
            store.write("repos", reposJson, 1234L)
            assertEquals(LastGoodEntry(reposJson, 1234L), store.read("repos"))
            assertEquals(null, store.read("missing"))
            assertTrue(File(dir, "repos.json").isFile)
        } finally {
            dir.deleteRecursively()
        }
    }
}
