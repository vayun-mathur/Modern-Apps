package com.vayunmathur.code.util

import android.net.Uri
import android.os.Looper
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.code.syntax.Language
import java.io.File
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Robolectric tests for the ViewModel's async file/git/search/save operations (real temp dirs). */
@RunWith(RobolectricTestRunner::class)
class VmOpsTest {

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

    private fun tempDir(tag: String): File =
        File(System.getProperty("java.io.tmpdir"), "$tag-${System.nanoTime()}").also { it.mkdirs() }

    @Test
    fun openFolderAndFileLifecycle() {
        val dir = tempDir("vmops")
        File(dir, "a.kt").writeText("fun a() {}")
        File(dir, "sub").mkdirs()
        File(dir, "sub/b.kt").writeText("fun b() {}")
        val v = vm()
        kotlinx.coroutines.runBlocking { v.loadFolder(dir) }
        await("nodes loaded") { v.nodes.isNotEmpty() && v.rootName == dir.name }
        assertTrue(v.uiState.folderOpen)

        v.openFile(File(dir, "a.kt"))
        await("tab opened") { v.tabs.size == 1 }
        assertEquals("fun a() {}", v.tabs[0].value.text)
        // Re-opening the same file selects the existing tab.
        v.openFile(File(dir, "a.kt"))
        assertEquals(1, v.tabs.size)

        // Create a file under the root (parent index -1/null resolves to root).
        v.createFileImpl(null, "new.kt")
        await("created tab") { v.tabs.any { it.name == "new.kt" } }
        assertTrue(File(dir, "new.kt").exists())

        v.createFolderImpl(null, "pkg")
        await("folder created") { File(dir, "pkg").isDirectory }

        dir.deleteRecursively()
    }

    @Test
    fun renameAndDeleteNodes() {
        val dir = tempDir("vmrename")
        File(dir, "old.kt").writeText("x")
        val v = vm()
        v.rootDir = dir
        v.rootName = dir.name
        v.nodes.add(TreeNode(FileEntry(File(dir, "old.kt"), "old.kt", false), 0))
        v.openFile(File(dir, "old.kt"))
        await("tab") { v.tabs.size == 1 }

        v.renameNodeImpl(0, "renamed.kt")
        await("renamed on disk") { File(dir, "renamed.kt").exists() && v.tabs[0].name == "renamed.kt" }
        assertEquals("renamed.kt", v.tabs[0].name)

        v.deleteNodeImpl(0)
        await("deleted") { !File(dir, "renamed.kt").exists() }
        dir.deleteRecursively()
    }

    @Test
    fun saveTabWritesAndClearsDirty() {
        val dir = tempDir("vmsave")
        val file = File(dir, "a.kt").also { it.writeText("old") }
        val v = vm()
        val tab = OpenTab(file, initialName = "a.kt", initialText = "old", language = Language.KOTLIN)
        tab.value = TextFieldValue("new text  ")
        v.tabs.add(tab)
        v.currentIndex = 0
        v.saveTab(tab)
        await("saved") { file.readText() == "new text  " && tab.savedText == "new text  " }
        assertFalse(tab.isDirty)
        dir.deleteRecursively()
    }

    @Test
    fun saveTabTrimAndFinalNewline() {
        val dir = tempDir("vmsave2")
        val file = File(dir, "a.kt").also { it.writeText("old") }
        val v = vm()
        v.setTrimTrailingOnSave(true)
        v.setFinalNewlineOnSave(true)
        val tab = OpenTab(file, initialName = "a.kt", initialText = "old", language = Language.KOTLIN)
        tab.value = TextFieldValue("line  \nlast")
        v.tabs.add(tab)
        v.currentIndex = 0
        v.saveTab(tab)
        await("trimmed") { file.readText() == "line\nlast\n" }
        dir.deleteRecursively()
    }

    @Test
    fun checkExternalChangesReloadsCleanAndFlagsDirty() {
        val dir = tempDir("vmext")
        val file = File(dir, "a.kt").also { it.writeText("v1") }
        val v = vm()
        v.openFile(file)
        await("tab") { v.tabs.size == 1 }
        val tab = v.tabs[0]
        // Change on disk (different length so the snapshot differs).
        Thread.sleep(20)
        file.writeText("v2-longer")
        v.checkExternalChanges()
        await("reloaded") { tab.value.text == "v2-longer" }

        tab.value = TextFieldValue("unsaved edits here")
        file.writeText("v3-even-longer-content")
        v.checkExternalChanges()
        await("flagged") { tab.changedOnDisk }
        dir.deleteRecursively()
    }

    @Test
    fun searchAndRefreshProjectFiles() {
        val dir = tempDir("vmsearch")
        File(dir, "a.kt").writeText("fun target() {}\nval x = 1")
        File(dir, "b.txt").writeText("nothing here")
        File(dir, "sub").mkdirs()
        File(dir, "sub/c.kt").writeText("target again")
        val v = vm()
        v.rootDir = dir
        v.rootName = dir.name
        v.searchProjectImpl("target", caseSensitive = false, useRegex = false)
        await("results") { v.searchResults.isNotEmpty() }
        assertTrue(v.searchResults.any { it.preview.contains("target") })

        v.refreshProjectFilesImpl()
        await("project files") { v.uiState.projectFiles.size >= 3 }

        val first = v.searchResults.first()
        v.openSearchResultImpl(first)
        await("result opened") { v.tabs.isNotEmpty() }
        dir.deleteRecursively()
    }

    @Test
    fun gitOpsRoundTripThroughViewModel() {
        val dir = tempDir("vmgit")
        File(dir, "a.txt").writeText("hello")
        val v = vm()
        v.rootDir = dir
        v.gitInit()
        await("repo") { v.gitIsRepo }
        v.gitStage("a.txt")
        await("staged") { v.gitStatus?.staged?.contains("a.txt") == true }
        v.gitCommit("first")
        await("committed") { v.gitStatus?.isClean == true }
        v.loadGitDiff("a.txt", staged = false)
        await("diff loaded") { v.gitDiff != null }
        v.loadSideBySideDiff("a.txt", staged = false)
        await("rows loaded") { v.gitDiffRows != null }
        v.gitCreateBranch("feature")
        await("branch") { v.gitBranches.contains("feature") }
        v.gitCheckout("feature")
        v.refreshGit()
        await("refreshed") { !v.gitBusy && v.gitIsRepo }
        dir.deleteRecursively()
    }

    @Test
    fun openExternalFileUriAndUriHelpers() {
        val dir = tempDir("vmuri")
        val file = File(dir, "ext.md").also { it.writeText("# hi") }
        val v = vm()
        v.openExternal(Uri.fromFile(file))
        await("external tab") { v.tabs.size == 1 }
        assertEquals("# hi", v.tabs[0].value.text)

        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals("# hi", FileFiles.readTextFromUri(ctx, Uri.fromFile(file)))
        assertEquals("ext.md", FileFiles.queryDisplayName(ctx, Uri.fromFile(file)))
        dir.deleteRecursively()
    }

    @Test
    fun scheduleAutoSaveWritesAfterDelay() {
        val dir = tempDir("vmauto")
        val file = File(dir, "a.kt").also { it.writeText("old") }
        val v = vm()
        val tab = OpenTab(file, initialName = "a.kt", initialText = "old", language = Language.KOTLIN)
        tab.value = TextFieldValue("auto saved")
        v.tabs.add(tab)
        v.currentIndex = 0
        v.scheduleAutoSave()
        await("auto saved") { file.readText() == "auto saved" }
        dir.deleteRecursively()
    }
}
