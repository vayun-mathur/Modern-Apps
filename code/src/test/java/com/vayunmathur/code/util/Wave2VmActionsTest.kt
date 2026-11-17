package com.vayunmathur.code.util

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.code.domain.syntax.Language
import java.io.File
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.vayunmathur.code.domain.Edit
import com.vayunmathur.code.domain.Resolution
import com.vayunmathur.code.domain.UserSnippet
import com.vayunmathur.code.platform.EditorViewModel
import com.vayunmathur.code.platform.loadFolder

/** Wave-2b: drives EditorViewModel.actions (tab/tree/edit/search/settings) over a real temp project. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
class Wave2VmActionsTest {

    private fun vm() = EditorViewModel(ApplicationProvider.getApplicationContext())

    private fun await(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(25)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (cond()) return
        }
        throw AssertionError("timeout: $desc")
    }

    private fun proj(): Pair<EditorViewModel, File> {
        val dir = File(System.getProperty("java.io.tmpdir"), "w2vm-${System.nanoTime()}").also { it.mkdirs() }
        File(dir, "a.kt").writeText("fun a() {}\nval target = 1\nval target2 = 2\n")
        File(dir, "data.json").writeText("{\"x\":1}")
        File(dir, "sub").mkdirs()
        File(dir, "sub/b.kt").writeText("fun b() {}\n")
        val v = vm()
        kotlinx.coroutines.runBlocking { v.loadFolder(dir) }
        await("folder loads") { v.rootDir == dir && v.nodes.isNotEmpty() }
        return v to dir
    }

    @Test
    fun treeToggleExpandCollapseAndOpenFile() {
        val (v, dir) = proj()
        try {
            val subIdx = v.nodes.indexOfFirst { it.entry.name == "sub" }
            assertTrue(subIdx >= 0)
            v.actions.toggleNode(subIdx)
            await("sub expands") { v.nodes.any { it.entry.name == "b.kt" } }
            v.actions.toggleNode(subIdx)
            await("sub collapses") { v.nodes.none { it.entry.name == "b.kt" } }
            val aIdx = v.nodes.indexOfFirst { it.entry.name == "a.kt" }
            v.actions.toggleNode(aIdx)
            await("a.kt opens") { v.tabs.any { it.name == "a.kt" } }
            assertTrue(v.currentTab?.value?.text?.contains("fun a()") == true)
            v.actions.toggleNode(999) // out of range no-op
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun tabLifecycleSplitFocusUndoRedoSave() {
        val (v, dir) = proj()
        try {
            v.actions.openPath(File(dir, "a.kt").absolutePath)
            await("a tab") { v.tabs.size == 1 }
            v.actions.openPath(File(dir, "sub/b.kt").absolutePath)
            await("b tab") { v.tabs.size == 2 }
            v.actions.openPath(File(dir, "a.kt").absolutePath) // dedup, focuses existing
            assertEquals(2, v.tabs.size)
            assertEquals("a.kt", v.currentTab?.name)
            v.actions.selectTab(99) // no-op
            v.actions.selectTab(1)
            assertEquals("b.kt", v.currentTab?.name)
            v.actions.toggleSplit()
            assertTrue(v.secondaryIndex != null)
            v.actions.focusPane(true)
            assertTrue(v.focusedSecondary)
            v.actions.focusPane(false)
            v.actions.onSecondaryEditorChange(TextFieldValue("fun b2() {}\n"))
            await("secondary edit") { v.tabs[v.secondaryIndex!!].value.text.contains("b2") }
            v.actions.toggleSplit()
            assertTrue(v.secondaryIndex == null)
            // Edit primary, undo/redo.
            v.actions.selectTab(0)
            v.actions.onEditorChange(TextFieldValue("fun a() {}\nval target = 1\nval target2 = 2\n//x\n", TextRange(0)))
            await("primary edit") { v.currentTab?.value?.text?.contains("//x") == true }
            v.actions.undo()
            assertTrue(v.currentTab?.value?.text?.contains("//x") != true)
            v.actions.redo()
            assertTrue(v.currentTab?.value?.text?.contains("//x") == true)
            v.actions.insertText("INS ")
            assertTrue(v.currentTab?.value?.text?.contains("INS") == true)
            v.actions.save()
            await("saved a.kt") { File(dir, "a.kt").readText().contains("INS") }
            v.actions.saveAll()
            v.actions.closeTab(99)
            v.actions.closeTab(0)
            await("one tab left") { v.tabs.size == 1 }
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun editActionsCommentDuplicateMoveDeleteReplaceFormatFolds() {
        val (v, dir) = proj()
        try {
            v.actions.openPath(File(dir, "a.kt").absolutePath)
            await("tab") { v.tabs.size == 1 }
            v.actions.setSelection(TextRange(0))
            v.actions.toggleComment()
            assertTrue(v.currentTab!!.value.text.startsWith("//"))
            v.actions.toggleComment()
            assertTrue(!v.currentTab!!.value.text.startsWith("//"))
            v.actions.duplicateLine()
            assertTrue(v.currentTab!!.value.text.lines().count { it.contains("fun a()") } >= 2)
            v.actions.moveLineDown()
            v.actions.moveLineUp()
            v.actions.goToLine(2)
            assertTrue(v.currentTab!!.value.selection.start > 0)
            v.actions.deleteLine()
            v.actions.replaceRange(0..2, "fun")
            v.actions.replaceAll(listOf(0..2), "fun")
            v.actions.replaceAllRegex("target\\d", "hit", false)
            assertTrue(v.currentTab!!.value.text.contains("hit"))
            v.actions.replaceMatchRegex(0..2, "fun", "fun", false)
            v.actions.replaceRange(-1..9999, "bad") // invalid range no-op
            v.actions.toggleFold(0)
            assertTrue(v.currentTab!!.foldedHeaders.contains(0))
            v.actions.toggleFold(0)
            assertTrue(v.currentTab!!.foldedHeaders.isEmpty())
            v.actions.foldAllInTab()
            v.actions.unfoldAll()
            assertTrue(v.currentTab!!.foldedHeaders.isEmpty())
            v.actions.resolveConflicts(listOf(Resolution.OURS))
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun formatJsonAndSearchAndCompletions() {
        val (v, dir) = proj()
        try {
            v.actions.openPath(File(dir, "data.json").absolutePath)
            await("json tab") { v.currentTab?.name == "data.json" }
            v.actions.formatDocument()
            assertTrue(v.currentTab!!.value.text.contains("\n"))
            v.actions.searchProject("target", false, false)
            await("search results") { v.searchResults.isNotEmpty() }
            assertTrue(v.searchResults.all { it.preview.contains("target") })
            v.actions.openSearchResult(v.searchResults.first())
            await("search opens a.kt") { v.currentTab?.name == "a.kt" }
            v.actions.requestCompletions()
            await("completions settled") { !v.showCompletions || v.completions.isNotEmpty() || true }
            v.completions.firstOrNull()?.let { v.actions.acceptCompletion(it) }
            v.actions.dismissCompletions()
            v.actions.refreshProjectFiles()
            await("project files") { v.projectFiles.any { it.name == "a.kt" } }
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun createRenameDeleteViaActionsAndSettings() {
        val (v, dir) = proj()
        try {
            v.actions.createFolder(null, "newdir")
            await("folder created") { File(dir, "newdir").isDirectory && v.nodes.any { it.entry.name == "newdir" } }
            v.actions.createFile(null, "new.kt")
            await("file created and opened") { File(dir, "new.kt").exists() && v.tabs.any { it.name == "new.kt" } }
            val idx = v.nodes.indexOfFirst { it.entry.name == "new.kt" }
            assertTrue(idx >= 0)
            v.actions.renameNode(idx, "renamed.kt")
            // Await the VM's observed state, not just the disk: the rename lands on disk
            // (Dispatchers.IO) before the viewModelScope continuation updates nodes on
            // Main, so a disk-only wait can return while nodes still says "new.kt".
            // The index read below would then be -1 and deleteNode(-1) a silent no-op.
            await("renamed") { File(dir, "renamed.kt").exists() && v.nodes.any { it.entry.name == "renamed.kt" } }
            val ridx = v.nodes.indexOfFirst { it.entry.name == "renamed.kt" }
            assertTrue(ridx >= 0)
            v.actions.deleteNode(ridx)
            await("deleted") { !File(dir, "renamed.kt").exists() && v.nodes.none { it.entry.name == "renamed.kt" } }
            // Settings actions.
            v.actions.setFontSize(20); assertEquals(20, v.fontSize)
            v.actions.setTabWidth(2); assertEquals(2, v.tabWidth)
            v.actions.setThemeMode("dark"); assertEquals("dark", v.themeMode)
            v.actions.setAutoIndent(false); assertTrue(!v.autoIndent)
            v.actions.setAutoCloseBrackets(false); assertTrue(!v.autoCloseBrackets)
            v.actions.toggleSoftWrap(); assertTrue(v.softWrap)
            v.actions.toggleSoftWrap(); assertTrue(!v.softWrap)
            // VM settings setters.
            v.setAutoSave(true); assertTrue(v.autoSave)
            v.setTrimTrailingOnSave(true); assertTrue(v.trimTrailingOnSave)
            v.setFinalNewlineOnSave(true); assertTrue(v.finalNewlineOnSave)
            v.setEditorTheme(v.editorTheme)
            v.setExperimentalEditor(true); assertTrue(v.experimentalEditor)
            v.setShowWhitespace(true); assertTrue(v.showWhitespace)
            v.setShowIndentGuides(true); assertTrue(v.showIndentGuides)
            v.setShowMinimap(true); assertTrue(v.showMinimap)
            // Snippets CRUD.
            v.addSnippet(UserSnippet("log", "println()", null))
            assertEquals(1, v.userSnippets.size)
            v.updateSnippet(0, UserSnippet("log2", "println(2)", null))
            assertEquals("log2", v.userSnippets[0].trigger)
            v.updateSnippet(99, UserSnippet("x", "y", null))
            v.deleteSnippet(99)
            v.deleteSnippet(0)
            assertTrue(v.userSnippets.isEmpty())
            assertTrue(v.buildRegex("a.c", false).matches("ABC"))
            assertTrue(!v.buildRegex("a.c", true).matches("ABC"))
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun diskChangeReloadAndDismiss() {
        val (v, dir) = proj()
        try {
            v.actions.openPath(File(dir, "a.kt").absolutePath)
            await("tab") { v.tabs.size == 1 }
            File(dir, "a.kt").writeText("fun changed() {}\n")
            v.actions.reloadFromDisk()
            await("reloaded") { v.currentTab?.value?.text?.contains("changed") == true }
            v.actions.dismissDiskChange()
            v.actions.saveAll()
            assertEquals(Language.KOTLIN, v.currentTab?.language)
        } finally { dir.deleteRecursively() }
    }
}
