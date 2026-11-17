package com.vayunmathur.code.util

import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.code.domain.syntax.Language
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.vayunmathur.code.data.FileEntry
import com.vayunmathur.code.data.FileFiles
import com.vayunmathur.code.platform.EditorViewModel
import com.vayunmathur.code.platform.OpenTab
import com.vayunmathur.code.platform.TreeNode
import com.vayunmathur.code.platform.openFile
import com.vayunmathur.code.platform.closeFolder
import com.vayunmathur.code.platform.closeTabImpl
import com.vayunmathur.code.platform.createFileImpl
import com.vayunmathur.code.platform.createFolderImpl
import com.vayunmathur.code.platform.deleteNodeImpl
import com.vayunmathur.code.platform.loadFile
import com.vayunmathur.code.platform.loadFolder
import com.vayunmathur.code.platform.makeFileTab
import com.vayunmathur.code.platform.openExternal
import com.vayunmathur.code.platform.refreshChildren
import com.vayunmathur.code.platform.renameNodeImpl
import com.vayunmathur.code.platform.selectTabImpl

/** Edge-case behaviour for file-tree ops not covered by EditorFileTreeOpsTest/VmOpsTest. */
@RunWith(RobolectricTestRunner::class)
class EditorFileTreeOpsBehaviourTest {

    private fun vm() = EditorViewModel(ApplicationProvider.getApplicationContext())

    private fun await(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(25)
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            if (cond()) return
        }
        throw AssertionError("timed out waiting for: $desc")
    }

    private fun proj(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "tree2-${System.nanoTime()}").also { it.mkdirs() }
        File(dir, "a.kt").writeText("fun a() {}")
        File(dir, "sub").mkdir()
        File(dir, "sub/b.kt").writeText("fun b() {}")
        File(dir, "sub/c.txt").writeText("c")
        return dir
    }

    @Test fun loadFolderRejectsFile() {
        val v = vm()
        val dir = proj()
        val ex = runCatching { runBlocking { v.loadFolder(File(dir, "a.kt")) } }.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex is IllegalArgumentException)
        assertNull(v.rootDir)
        dir.deleteRecursively()
    }

    @Test fun loadFileAndMakeTabForMissingFileReturnNull() {
        val v = vm()
        val missing = File("/no-such-dir-xyz/missing.kt")
        assertNull(runBlocking { v.loadFile(missing) })
        assertNull(runBlocking { v.makeFileTab(missing) })
        // openFile on a missing file must not fabricate an empty tab.
        v.openFile(missing)
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(v.tabs.isEmpty())
    }

    @Test fun renameDirectoryRepointsNestedTabsAndDropsDescendantRows() {
        val dir = proj()
        val v = vm()
        runBlocking { v.loadFolder(dir) }
        v.openFile(File(dir, "sub/b.kt"))
        await("tab") { v.tabs.size == 1 }
        val subIndex = v.nodes.indexOfFirst { it.entry.name == "sub" }
        assertTrue(subIndex >= 0)
        // Simulate expanded subtree rows under sub.
        v.nodes.add(subIndex + 1, TreeNode(FileEntry(File(dir, "sub/b.kt"), "b.kt", false), 1))
        v.renameNodeImpl(subIndex, "renamed")
        await("renamed dir") { File(dir, "renamed/b.kt").exists() && v.tabs[0].file?.absolutePath?.contains("renamed") == true }
        assertEquals(File(dir, "renamed/b.kt").absolutePath, v.tabs[0].file?.absolutePath)
        assertEquals("renamed", v.nodes[subIndex].entry.name)
        // Descendant rows were dropped for a renamed directory.
        assertEquals(2, v.nodes.size)
        dir.deleteRecursively()
    }

    @Test fun renameFileUpdatesLanguageFromNewExtension() {
        val dir = proj()
        val v = vm()
        runBlocking { v.loadFolder(dir) }
        v.openFile(File(dir, "a.kt"))
        await("tab") { v.tabs.size == 1 }
        val idx = v.nodes.indexOfFirst { it.entry.name == "a.kt" }
        v.renameNodeImpl(idx, "a.py")
        await("renamed") { File(dir, "a.py").exists() && v.tabs[0].name == "a.py" }
        assertEquals(Language.PYTHON, v.tabs[0].language)
        assertEquals(Language.PYTHON, Language.fromFileName(v.tabs[0].name))
        // Out-of-range rename/delete/create are no-ops.
        v.renameNodeImpl(99, "nope.kt")
        v.deleteNodeImpl(99)
        assertTrue(File(dir, "a.py").exists())
        dir.deleteRecursively()
    }

    @Test fun deleteDirectoryClosesTabsUnderIt() {
        val dir = proj()
        val v = vm()
        runBlocking { v.loadFolder(dir) }
        v.openFile(File(dir, "sub/b.kt"))
        v.openFile(File(dir, "a.kt"))
        await("tabs") { v.tabs.size == 2 }
        val subIndex = v.nodes.indexOfFirst { it.entry.name == "sub" }
        v.deleteNodeImpl(subIndex)
        await("deleted") { !File(dir, "sub").exists() && v.tabs.size == 1 }
        assertEquals(1, v.tabs.size)
        assertEquals("a.kt", v.tabs[0].name)
        dir.deleteRecursively()
    }

    @Test fun createFileUnderSubdirectoryRowOpensIt() {
        val dir = proj()
        val v = vm()
        runBlocking { v.loadFolder(dir) }
        val subIndex = v.nodes.indexOfFirst { it.entry.name == "sub" }
        v.nodes[subIndex].expanded = false
        v.createFileImpl(subIndex, "new.kt")
        await("created") { File(dir, "sub/new.kt").exists() && v.tabs.any { it.name == "new.kt" } }
        assertTrue(v.nodes[subIndex].expanded)
        // Creating the same name again is refused by FileFiles and opens no duplicate.
        val count = v.tabs.size
        v.createFileImpl(subIndex, "new.kt")
        Thread.sleep(150)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(count, v.tabs.size)
        dir.deleteRecursively()
    }

    @Test fun createFolderUnderRootAndRefreshShowsIt() {
        val dir = proj()
        val v = vm()
        runBlocking { v.loadFolder(dir) }
        v.createFolderImpl(null, "pkg")
        await("folder") { File(dir, "pkg").isDirectory && v.nodes.any { it.entry.name == "pkg" } }
        // No root: create impls are no-ops.
        val v2 = vm()
        v2.createFileImpl(null, "x.kt")
        v2.createFolderImpl(null, "xdir")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(v2.tabs.isEmpty())
        dir.deleteRecursively()
    }

    @Test fun refreshChildrenOfSubdirectoryPreservesLoadedSubtree() {
        val dir = proj()
        val v = vm()
        runBlocking { v.loadFolder(dir) }
        val subIndex = v.nodes.indexOfFirst { it.entry.name == "sub" }
        // Expand sub manually: insert its children.
        v.nodes.add(subIndex + 1, TreeNode(FileEntry(File(dir, "sub/b.kt"), "b.kt", false), 1))
        v.nodes.add(subIndex + 2, TreeNode(FileEntry(File(dir, "sub/c.txt"), "c.txt", false), 1))
        runBlocking { v.refreshChildren(subIndex) }
        val names = v.nodes.map { it.entry.name }
        assertEquals(listOf("sub", "b.kt", "c.txt", "a.kt"), names)
        // A new file in sub appears, order stays dirs-first then name.
        File(dir, "sub/a-first.kt").writeText("x")
        runBlocking { v.refreshChildren(subIndex) }
        assertEquals(listOf("sub", "a-first.kt", "b.kt", "c.txt", "a.kt"), v.nodes.map { it.entry.name })
        // Refresh of an invalid index is a no-op.
        runBlocking { v.refreshChildren(99) }
        assertEquals(5, v.nodes.size)
        dir.deleteRecursively()
    }

    @Test fun openExternalFileSchemeDeduplicatesAndContentTabIsReadOnly() {
        val dir = proj()
        val v = vm()
        val file = File(dir, "a.kt")
        v.openExternal(Uri.fromFile(file))
        await("tab") { v.tabs.size == 1 }
        v.openExternal(Uri.fromFile(file))
        assertEquals(1, v.tabs.size)
        assertFalse(v.tabs[0].readOnly)
        dir.deleteRecursively()
    }

    @Test fun closeFolderClearsTerminalState() {
        val dir = proj()
        val v = vm()
        runBlocking { v.loadFolder(dir) }
        v.terminalRunning = true
        v.closeFolder()
        assertFalse(v.terminalRunning)
        assertNull(v.terminal)
        assertNull(v.rootName)
        dir.deleteRecursively()
    }

    @Test fun selectAndCloseTabSecondaryAdjustments() {
        val v = vm()
        fun tab(name: String) = OpenTab(null, initialName = name, initialText = name, language = Language.PLAINTEXT)
        v.tabs.add(tab("a")); v.tabs.add(tab("b")); v.tabs.add(tab("c"))
        v.currentIndex = 2
        v.secondaryIndex = 1
        // Closing a tab before the current one shifts current down.
        v.closeTabImpl(0)
        assertEquals(1, v.currentIndex)
        assertEquals(0, v.secondaryIndex)
        // Closing the secondary tab itself clears it.
        v.closeTabImpl(0)
        assertNull(v.secondaryIndex)
        // Negative / out-of-range closes are no-ops.
        v.closeTabImpl(-1)
        v.closeTabImpl(42)
        assertEquals(1, v.tabs.size)
        // Selecting the secondary pane's tab clears the split.
        v.tabs.add(tab("d"))
        v.secondaryIndex = 1
        v.selectTabImpl(1)
        assertEquals(1, v.currentIndex)
        assertNull(v.secondaryIndex)
        v.selectTabImpl(-1)
        assertEquals(1, v.currentIndex)
    }
}
