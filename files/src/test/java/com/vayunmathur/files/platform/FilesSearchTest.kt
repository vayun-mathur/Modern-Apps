package com.vayunmathur.files.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class FilesSearchTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun tempDir(name: String): File =
        File(ctx.cacheDir, "search-$name-${System.nanoTime()}").apply { mkdirs() }

    private fun File.file(name: String): File = File(this, name).apply { writeText("x") }
    private fun File.dir(name: String): File = File(this, name).apply { mkdirs() }

    @Test
    fun `tokenized match is AND case-insensitive and blank matches all`() {
        assertTrue(searchMatches("Annual Report.PDF", "report pdf"))
        assertTrue(searchMatches("Annual Report.PDF", "REPORT"))
        assertTrue(searchMatches("anything.txt", ""))
        assertTrue(searchMatches("anything.txt", "   "))
        assertFalse(searchMatches("Annual Report.PDF", "report xls"))
        assertFalse(searchMatches("notes.txt", "report"))
        assertEquals(listOf("report", "pdf"), searchTokens("  Report   PDF "))
        assertEquals(emptyList(), searchTokens("  "))
    }

    @Test
    fun `recursive search finds nested hits and matching folders`() = runBlocking<Unit> {
        val root = tempDir("nested")
        root.file("top-report.txt")
        val docs = root.dir("Docs")
        docs.file("report-2024.txt")
        docs.file("other.txt")
        val deep = docs.dir("inner")
        deep.file("deep report.txt")
        val hits = recursiveSearch(root, "report")
        assertEquals(
            setOf("top-report.txt", "report-2024.txt", "deep report.txt"),
            hits.map { it.item.name }.toSet(),
        )
        // Shallower hits come first (breadth-first).
        assertEquals("top-report.txt", hits.first().item.name)
        // The root itself is never a hit, and non-matches stay out.
        assertTrue(hits.none { it.item.realFile == root })
        root.deleteRecursively()
    }

    @Test
    fun `depth cap stops descent at the limit`() = runBlocking<Unit> {
        val root = tempDir("depth")
        var current = root
        // level1/target.txt at depth 2 ... levelN/target.txt at depth N+1
        repeat(10) { level ->
            current = current.dir("level${level + 1}")
            current.file("target.txt")
        }
        val capped = recursiveSearch(root, "target", maxDepth = 2)
        assertEquals(listOf("target.txt"), capped.map { it.item.name })
        assertEquals("level1", capped.single().parentSubtitle)

        val defaultDepth = recursiveSearch(root, "target")
        // Files at depths 2..8 are in, depths 9..11 are out.
        assertEquals(7, defaultDepth.size)
        assertEquals(8, SEARCH_MAX_DEPTH)
        root.deleteRecursively()
    }

    @Test
    fun `result cap truncates the flattened hits`() = runBlocking<Unit> {
        val root = tempDir("cap")
        repeat(10) { root.file("match-$it.txt") }
        val hits = recursiveSearch(root, "match", maxResults = 3)
        assertEquals(3, hits.size)
        assertEquals(500, SEARCH_MAX_RESULTS)
        root.deleteRecursively()
    }

    @Test
    fun `blank query and non-directory root search nothing`() = runBlocking<Unit> {
        val root = tempDir("blank")
        root.file("a.txt")
        assertTrue(recursiveSearch(root, "  ").isEmpty())
        assertTrue(recursiveSearch(File(root, "a.txt"), "a").isEmpty())
        root.deleteRecursively()
    }

    @Test
    fun `hidden folders are skipped unless showHidden`() = runBlocking<Unit> {
        val root = tempDir("hidden")
        val hidden = root.dir(".secret")
        hidden.file("report.txt")
        assertTrue(recursiveSearch(root, "report", showHidden = false).isEmpty())
        assertEquals(1, recursiveSearch(root, "report", showHidden = true).size)
        root.deleteRecursively()
    }

    @Test
    fun `cancellation abandons the walk`() = runBlocking<Unit> {
        val root = tempDir("cancel")
        val sub = root.dir("sub")
        sub.file("target.txt")
        root.dir("later").file("target.txt")
        lateinit var deferred: Deferred<List<FilesSearchHit>>
        deferred = async {
            recursiveSearch(root, "target", listChildren = { dir ->
                if (dir == sub) deferred.cancel()
                listDirChildren(dir, showHidden = false)
            })
        }
        assertFailsWith<CancellationException> { deferred.await() }
        assertTrue(deferred.isCancelled)
        root.deleteRecursively()
    }

    @Test
    fun `parent subtitle is the path relative to the search root`() = runBlocking<Unit> {
        val root = tempDir("subtitle")
        val docs = root.dir("Docs")
        val year = docs.dir("2024")
        val nested = year.file("report.txt")
        val direct = root.file("report.txt")
        assertEquals("Docs/2024", parentSubtitle(root, nested.toBrowserItem()))
        assertEquals(root.name, parentSubtitle(root, direct.toBrowserItem()))
        val hits = recursiveSearch(root, "report")
        assertEquals(
            mapOf("Docs/2024" to 1, root.name to 1),
            hits.groupingBy { it.parentSubtitle }.eachCount(),
        )
        // Zip entries derive the subtitle from the inner path instead.
        val zipItem = FileBrowserItem(
            name = "z.txt", isDirectory = false, size = 1,
            realFile = null, zipInnerPath = "docs/inner/z.txt", key = "zip:docs/inner/z.txt",
        )
        assertEquals("docs/inner", parentSubtitle(root, zipItem))
        val zipTop = zipItem.copy(zipInnerPath = "z.txt", key = "zip:z.txt")
        assertEquals("", parentSubtitle(root, zipTop))
        root.deleteRecursively()
    }

    @Test
    fun `symlink escaping the root is not followed`() = runBlocking<Unit> {
        val root = tempDir("scope")
        val outside = tempDir("outside")
        outside.file("escaped-target.txt")
        val link = File(root, "link")
        val created = runCatching {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        }.isSuccess
        if (created) {
            val hits = recursiveSearch(root, "target")
            assertTrue(hits.isEmpty(), "outside-root symlink must not be searched: $hits")
        }
        root.deleteRecursively()
        outside.deleteRecursively()
    }
}
