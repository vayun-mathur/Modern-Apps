package com.vayunmathur.code.util

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.code.syntax.Language
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Robolectric tests for the file-tree operations: refresh, remove, select/close impls, tab repoint. */
@RunWith(RobolectricTestRunner::class)
class EditorFileTreeOpsTest {

    private fun vm() = EditorViewModel(ApplicationProvider.getApplicationContext())

    private fun proj(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "tree-${System.nanoTime()}").also { it.mkdirs() }
        File(dir, "a.kt").writeText("a")
        File(dir, "sub").mkdir()
        File(dir, "sub/b.kt").writeText("b")
        return dir
    }

    @Test
    fun loadFolderListsTopLevel() = runTest {
        val v = vm()
        val dir = proj()
        v.loadFolder(dir)
        assertEquals(dir, v.rootDir)
        assertEquals(dir.name, v.rootName)
        assertEquals(listOf("sub", "a.kt"), v.nodes.map { it.entry.name })
        assertEquals(0, v.nodes[0].depth)
        dir.deleteRecursively()
    }

    @Test
    fun removeDescendantsDropsSubtree() = runTest {
        val v = vm()
        val dir = proj()
        v.loadFolder(dir)
        // Simulate an expanded subtree: insert a fake child row.
        val sub = v.nodes.first { it.entry.name == "sub" }
        val at = v.nodes.indexOf(sub)
        v.nodes.add(at + 1, TreeNode(FileEntry(File(dir, "sub/b.kt"), "b.kt", false), 1))
        assertEquals(3, v.nodes.size)
        v.removeDescendants(at)
        assertEquals(2, v.nodes.size)
        dir.deleteRecursively()
    }

    @Test
    fun refreshChildrenPreservesExpansion() = runTest {
        val v = vm()
        val dir = proj()
        v.loadFolder(dir)
        val before = v.nodes.map { it.entry.name }
        v.refreshChildren(null)
        assertEquals(before, v.nodes.map { it.entry.name })
        // New file appears after refresh.
        File(dir, "c.kt").writeText("c")
        v.refreshChildren(null)
        assertTrue("c.kt" in v.nodes.map { it.entry.name })
        dir.deleteRecursively()
    }

    @Test
    fun parentFileForResolvesRootAndRows() = runTest {
        val v = vm()
        assertNull(v.parentFileFor(null))
        val dir = proj()
        v.loadFolder(dir)
        assertEquals(dir, v.parentFileFor(null))
        assertEquals(File(dir, "a.kt"), v.parentFileFor(1))
        assertNull(v.parentFileFor(99))
        dir.deleteRecursively()
    }

    @Test
    fun selectTabImplMirrorsAction() {
        val v = vm()
        v.tabs.add(OpenTab(null, initialName = "a.kt", initialText = "a", language = Language.KOTLIN))
        v.tabs.add(OpenTab(null, initialName = "b.kt", initialText = "b", language = Language.KOTLIN))
        v.secondaryIndex = 1
        v.selectTabImpl(1)
        assertEquals(1, v.currentIndex)
        assertNull(v.secondaryIndex)
        v.selectTabImpl(9) // no-op
        assertEquals(1, v.currentIndex)
    }

    @Test
    fun closeTabImplMirrorsAction() {
        val v = vm()
        v.tabs.add(OpenTab(null, initialName = "a.kt", initialText = "a", language = Language.KOTLIN))
        v.currentIndex = 0
        v.closeTabImpl(0)
        assertTrue(v.tabs.isEmpty())
        assertEquals(-1, v.currentIndex)
        v.closeTabImpl(0) // no-op on empty
    }

    @Test
    fun closeTabsUnderClosesMatching() {
        val v = vm()
        val dir = proj()
        val f = File(dir, "a.kt")
        v.tabs.add(OpenTab(f, initialName = "a.kt", initialText = "a", language = Language.KOTLIN))
        v.tabs.add(OpenTab(null, initialName = "x", initialText = "x", language = Language.PLAINTEXT))
        v.currentIndex = 0
        v.closeTabsUnder(dir)
        assertEquals(1, v.tabs.size)
        assertEquals("x", v.tabs[0].name)
        dir.deleteRecursively()
    }

    @Test
    fun closeFolderThenLoadFolderRoundTrip() = runTest {
        val v = vm()
        val dir = proj()
        v.loadFolder(dir)
        assertTrue(v.nodes.isNotEmpty())
        v.closeFolder()
        assertTrue(v.nodes.isEmpty())
        assertNull(v.rootDir)
        dir.deleteRecursively()
    }
}
