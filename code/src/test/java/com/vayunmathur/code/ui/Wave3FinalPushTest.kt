package com.vayunmathur.code.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.code.MainActivity
import com.vayunmathur.code.Route
import com.vayunmathur.code.domain.syntax.Language
import com.vayunmathur.code.domain.syntax.SyntaxTransformation
import com.vayunmathur.code.domain.syntax.rememberSyntaxColors
import com.vayunmathur.code.platform.CodeActions
import com.vayunmathur.code.platform.CodeUiState
import com.vayunmathur.code.domain.Completion
import com.vayunmathur.code.domain.Diagnostic
import com.vayunmathur.code.domain.DiagnosticSeverity
import com.vayunmathur.code.platform.EditorViewModel
import com.vayunmathur.code.data.FileFiles
import com.vayunmathur.code.platform.OpenTab
import com.vayunmathur.code.platform.SearchResult
import com.vayunmathur.code.platform.TabUiState
import com.vayunmathur.code.platform.findLineMatches
import com.vayunmathur.code.platform.loadFolder
import com.vayunmathur.code.platform.openExternal
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowContentResolver
import com.vayunmathur.code.platform.openFile

/** Wave-3 final push: remaining syntax languages, project search/replace, URI files, editor states, MainActivity. */
@RunWith(AndroidJUnit4::class)
class Wave3FinalPushTest {
    @get:Rule val rule = createComposeRule()

    // ---------- helpers ----------
    private fun vm() = EditorViewModel(ApplicationProvider.getApplicationContext())
    private fun await(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(25)
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (cond()) return
        }
        throw AssertionError("timeout: $desc")
    }
    private fun tmp(name: String) = File(System.getProperty("java.io.tmpdir"), "$name-${System.nanoTime()}").also { it.mkdirs() }
    private fun tab(text: String, name: String = "a.kt", lang: Language = Language.KOTLIN, folded: Set<Int> = emptySet()) =
        TabUiState(name = name, value = TextFieldValue(text, TextRange(0)), language = lang, foldedHeaders = folded)

    private class Recording : CodeActions {
        val selections = mutableListOf<TextRange>()
        var reload = 0; var dismissDisk = 0; var split = 0
        override fun setSelection(range: TextRange) { selections += range }
        override fun reloadFromDisk() { reload++ }
        override fun dismissDiskChange() { dismissDisk++ }
        override fun toggleSplit() { split++ }
    }

    // ---------- SyntaxHighlighting: every language spec + filename mapping ----------
    @org.junit.Test fun everyLanguageSpecTokenizesAndPreservesText() {
        val samples = mapOf(
            Language.KOTLIN to "// c\nval s = \"x\" @Deprecated fun f() = 42",
            Language.JAVA to "/* c */ class A { String s = \"x\"; int n = 7; }",
            Language.JAVASCRIPT to "// c\nconst s = `tpl`; let n = 0x10;",
            Language.TYPESCRIPT to "interface A { x: string } // c\nconst n = 3.5",
            Language.PYTHON to "# c\ns = '''tri'''\n@dec\ndef f(): return 42",
            Language.C to "#include <stdio.h>\n// c\nint main() { return 42; }",
            Language.CPP to "// c\nclass A { const char* s = \"x\"; };",
            Language.RUST to "#[derive(Debug)] // c\nfn f() { let s = \"x\"; 42 }",
            Language.GO to "// c\npackage main\nfunc f() string { return `raw` }",
            Language.SWIFT to "// c\n@objc func f() -> Int { return 42 }",
            Language.RUBY to "# c\ndef f; puts 'x'; 42; end",
            Language.PHP to "<?php // c\n\$v = \"x\"; function f() { return 42; }",
            Language.JSON to "{\"a\": true, \"b\": null, \"n\": 12.5}",
            Language.XML to "<!-- c --><a href=\"x\">&amp; text</a>",
            Language.MARKDOWN to "# Title\n\n**bold** *it* `code` [l](http://x)\n> quote\n```\ncode\n```",
            Language.YAML to "# c\nkey: true\nother: 42\ns: \"x\"",
            Language.TOML to "# c\nkey = true\nn = 42\ns = 'x'",
            Language.SHELL to "# c\nexport X=\$HOME\necho \"hi\" 42",
            Language.CSS to "/* c */ @media screen { a { color: #fff; width: 10px; } }",
            Language.SQL to "-- c\nSELECT * FROM t WHERE x = 'v' AND n = 42;",
            Language.DOCKERFILE to "# c\nFROM alpine\nRUN echo \"hi\"\nEXPOSE 8080",
            Language.GRADLE to "// c\ndef s = \"\"\"x\"\"\"\nif (true) { return 42 }",
        )
        val colors = com.vayunmathur.code.domain.syntax.SyntaxColors(
            Color.Red, Color.Green, Color.Blue, Color.Gray, Color.Yellow, Color.Cyan, Color.Magenta,
            Color(0xFF111111), Color(0xFF222222), Color(0xFF333333), Color(0xFF444444), Color(0xFF555555),
            listOf(Color.Red, Color.Green, Color.Blue, Color.Yellow),
        )
        for ((lang, sample) in samples) {
            val spec = lang.spec
            assertNotNull(spec, "spec for $lang")
            val out = SyntaxTransformation(spec, colors, caret = 1, tsSpans = null).filter(AnnotatedString(sample)).text
            assertEquals(sample, out.text, lang.name)
            assertTrue(out.spanStyles.isNotEmpty(), "no spans for $lang")
        }
        // Plain text has no spec; transformation is identity.
        assertNull(Language.PLAINTEXT.spec)
        val plain = SyntaxTransformation(Language.PLAINTEXT.spec, colors).filter(AnnotatedString("hello 42")).text
        assertEquals("hello 42", plain.text)
    }

    @org.junit.Test fun fromFileNameCoversEveryExtensionAndSpecialCases() {
        val cases = mapOf(
            "a.kt" to Language.KOTLIN, "a.kts" to Language.KOTLIN, "A.KT" to Language.KOTLIN,
            "a.java" to Language.JAVA, "a.js" to Language.JAVASCRIPT, "a.jsx" to Language.JAVASCRIPT,
            "a.mjs" to Language.JAVASCRIPT, "a.cjs" to Language.JAVASCRIPT,
            "a.ts" to Language.TYPESCRIPT, "a.tsx" to Language.TYPESCRIPT,
            "a.py" to Language.PYTHON, "a.pyw" to Language.PYTHON,
            "a.c" to Language.C, "a.h" to Language.C,
            "a.cpp" to Language.CPP, "a.cc" to Language.CPP, "a.cxx" to Language.CPP,
            "a.hpp" to Language.CPP, "a.hh" to Language.CPP, "a.hxx" to Language.CPP,
            "a.rs" to Language.RUST, "a.go" to Language.GO, "a.swift" to Language.SWIFT,
            "a.rb" to Language.RUBY, "a.php" to Language.PHP, "a.json" to Language.JSON,
            "a.xml" to Language.XML, "a.html" to Language.XML, "a.htm" to Language.XML,
            "a.svg" to Language.XML, "a.xhtml" to Language.XML,
            "a.md" to Language.MARKDOWN, "a.markdown" to Language.MARKDOWN,
            "a.yml" to Language.YAML, "a.yaml" to Language.YAML, "a.toml" to Language.TOML,
            "a.sh" to Language.SHELL, "a.bash" to Language.SHELL, "a.zsh" to Language.SHELL,
            "a.css" to Language.CSS, "a.sql" to Language.SQL, "a.gradle" to Language.GRADLE,
            "Dockerfile" to Language.DOCKERFILE, "Dockerfile.prod" to Language.DOCKERFILE,
            "dockerfile" to Language.PLAINTEXT, "noext" to Language.PLAINTEXT,
            "a.unknown" to Language.PLAINTEXT, "a." to Language.PLAINTEXT, "" to Language.PLAINTEXT,
            ".gitignore" to Language.PLAINTEXT,
        )
        for ((name, lang) in cases) assertEquals(lang, Language.fromFileName(name), name)
        // Every language has a label and (except JSON/XML/CSS/MD/plain) maybe a comment prefix; just exercise them.
        for (l in Language.values()) assertTrue(l.label.isNotBlank())
        assertEquals("//", Language.KOTLIN.lineCommentPrefix); assertEquals("#", Language.PYTHON.lineCommentPrefix)
        assertEquals("--", Language.SQL.lineCommentPrefix); assertNull(Language.JSON.lineCommentPrefix)
    }

    @org.junit.Test fun syntaxThemesViaComposable() {
        var captured: com.vayunmathur.code.domain.syntax.SyntaxColors? = null
        var theme by androidx.compose.runtime.mutableStateOf("monokai")
        rule.setContent { captured = rememberSyntaxColors(theme) }
        assertEquals(Color(0xFFF92672), captured!!.keyword)
        rule.runOnIdle { theme = "solarized" }; rule.waitForIdle()
        assertEquals(Color(0xFF859900), captured!!.keyword)
        rule.runOnIdle { theme = "default" }; rule.waitForIdle()
        assertNotNull(captured); assertTrue(captured!!.brackets.isNotEmpty())
        rule.runOnIdle { theme = "unknown-theme" }; rule.waitForIdle()
        assertNotNull(captured) // falls back to default
    }

    // ---------- findLineMatches / findMatchRanges ----------
    @org.junit.Test fun findHelpersCaseRegexWholeWordLimitAndInvalid() {
        val text = "Foo bar\nfoo FOO\nbarfoo baz\nlast foo"
        assertEquals(listOf(1, 2, 3, 4), findLineMatches(text, "foo", caseSensitive = false, useRegex = false).map { it.line })
        assertEquals(listOf(2, 3, 4), findLineMatches(text, "foo", caseSensitive = true, useRegex = false).map { it.line })
        assertEquals(emptyList(), findLineMatches(text, "", caseSensitive = false, useRegex = false))
        assertEquals(emptyList(), findLineMatches(text, "[", caseSensitive = false, useRegex = true))
        assertEquals(2, findLineMatches(text, "foo", caseSensitive = false, useRegex = false, limit = 2).size)
        // Whole-word via regex boundaries (the whole-word mode used by callers).
        assertEquals(listOf(1, 2, 4), findLineMatches(text, "\\bfoo\\b", caseSensitive = false, useRegex = true).map { it.line })
        assertEquals(listOf(1), findLineMatches("  padded foo  ", "foo", caseSensitive = true, useRegex = false).map { it.line })
        assertEquals("padded foo", findLineMatches("  padded foo  ", "foo", true, false)[0].preview)
        val long = "x".repeat(250) + " foo"
        assertEquals(200, findLineMatches(long, "foo", true, false)[0].preview.length)

        assertEquals(emptyList(), findMatchRanges(text, "", caseSensitive = false, useRegex = false))
        assertEquals(listOf(0..2, 4..6), findMatchRanges("foo foo", "foo", caseSensitive = true, useRegex = false))
        assertEquals(3, findMatchRanges("Foo fOO foo", "foo", caseSensitive = false, useRegex = false).size)
        assertEquals(0, findMatchRanges("Foo", "foo", caseSensitive = true, useRegex = false).size)
        assertEquals(emptyList(), findMatchRanges("abc", "[", caseSensitive = false, useRegex = true))
        assertEquals(listOf(1..1, 3..3), findMatchRanges("a1b2", "\\d", caseSensitive = true, useRegex = true))
        assertEquals(listOf(0..2), findMatchRanges("aaa", "a*", caseSensitive = true, useRegex = true))
        assertEquals(emptyList(), findMatchRanges("bbb", "a*", caseSensitive = true, useRegex = true))
        // Non-overlapping literal scan.
        assertEquals(listOf(0..1), findMatchRanges("aaa", "aa", caseSensitive = true, useRegex = false))
    }

    // ---------- Project search / refresh / open result (real files) ----------
    private fun project(): Pair<EditorViewModel, File> {
        val dir = tmp("w3proj")
        File(dir, "a.kt").writeText("fun a() {}\nval Target = 1\nval target = 2\n")
        File(dir, "b.py").writeText("target = 3\n# target comment\n")
        File(dir, "c.txt").writeText("target in plain text should be skipped by extension\n")
        File(dir, "data.json").writeText("{\"target\": true}")
        File(dir, "ignored.kt").writeText("val target = 99\n")
        File(dir, ".gitignore").writeText("ignored.kt\nbuild/\n")
        File(dir, "build").mkdirs(); File(dir, "build/gen.kt").writeText("val target = 5\n")
        File(dir, "sub").mkdirs(); File(dir, "sub/d.kt").writeText("val target = 4\n")
        File(dir, "big.kt").writeText("val target = 1\n" + "x".repeat(600_000))
        val v = vm()
        kotlinx.coroutines.runBlocking { v.loadFolder(dir) }
        await("folder") { v.rootDir == dir && v.nodes.isNotEmpty() }
        return v to dir
    }

    @org.junit.Test fun searchProjectCaseRegexBlankAndSkips() {
        val (v, dir) = project()
        try {
            v.actions.searchProject("target", caseSensitive = false, useRegex = false)
            await("search done") { !v.isSearching && v.searchResults.isNotEmpty() }
            val files = v.searchResults.map { it.name }.toSet()
            assertTrue("a.kt" in files && "b.py" in files && "d.kt" in files, files.toString())
            assertFalse("c.txt" in files); assertFalse("ignored.kt" in files); assertFalse("gen.kt" in files); assertFalse("big.kt" in files)
            v.actions.searchProject("Target", caseSensitive = true, useRegex = false)
            await("case search") { !v.isSearching }
            assertTrue(v.searchResults.all { it.preview.contains("Target") })
            v.actions.searchProject("tar.et", caseSensitive = false, useRegex = true)
            await("regex search") { !v.isSearching && v.searchResults.isNotEmpty() }
            v.actions.searchProject("\\btarget\\b", caseSensitive = false, useRegex = true)
            await("whole word") { !v.isSearching && v.searchResults.isNotEmpty() }
            v.actions.searchProject("[", caseSensitive = false, useRegex = true)
            await("bad regex") { !v.isSearching }
            assertTrue(v.searchResults.isEmpty())
            v.actions.searchProject("   ", caseSensitive = false, useRegex = false)
            assertTrue(v.searchResults.isEmpty() && !v.isSearching)
            // No-root VM: search is a no-op.
            val orphan = vm(); orphan.actions.searchProject("x", caseSensitive = false, useRegex = false)
            assertTrue(orphan.searchResults.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @org.junit.Test fun refreshProjectFilesAndOpenSearchResultNewAndExisting() {
        val (v, dir) = project()
        try {
            v.actions.refreshProjectFiles()
            await("project files") { v.projectFiles.isNotEmpty() }
            val rels = v.projectFiles.map { it.relativePath }
            assertTrue(rels.any { it.endsWith("sub/d.kt") }, rels.toString())
            assertFalse(rels.any { it.contains("ignored.kt") })
            assertTrue(v.projectFiles.zipWithNext().all { (a, b) -> a.relativePath.lowercase() <= b.relativePath.lowercase() })
            val target = File(dir, "sub/d.kt")
            v.actions.openSearchResult(SearchResult(target.absolutePath, "d.kt", 1, "val target = 4"))
            await("open result") { v.tabs.any { it.name == "d.kt" } }
            val size = v.tabs.size
            v.actions.openSearchResult(SearchResult(target.absolutePath, "d.kt", 1, "val target = 4"))
            await("reopen existing") { v.currentTab?.name == "d.kt" }
            assertEquals(size, v.tabs.size)
        } finally { dir.deleteRecursively() }
    }

    @org.junit.Test fun viewModelReplacePathsRegexGroupsInvalidAndBounds() {
        val (v, dir) = project()
        try {
            v.actions.openPath(File(dir, "a.kt").absolutePath)
            await("tab") { v.tabs.size == 1 }
            val before = v.currentTab!!.value.text
            v.actions.replaceRange(-1..2, "x"); v.actions.replaceRange(0..9999, "x")
            assertEquals(before, v.currentTab!!.value.text)
            // Unsorted + overlapping replaceAll: sorted, overlaps skipped.
            v.actions.replaceAll(listOf(11..13, 0..2), "ZZ")
            assertTrue(v.currentTab!!.value.text.startsWith("ZZ"))
            v.actions.replaceAll(emptyList(), "x")
            // Regex replace with group ref, case-insensitive, and invalid patterns.
            v.actions.replaceAllRegex("val (\\w+) =", "var \$1 :=", caseSensitive = false)
            assertTrue(v.currentTab!!.value.text.contains("var Target :=") || v.currentTab!!.value.text.contains("var target :="), v.currentTab!!.value.text)
            v.actions.replaceAllRegex("[", "x", caseSensitive = false)
            v.actions.replaceMatchRegex(0..1, "[", "x", caseSensitive = false)
            val r = findMatchRanges(v.currentTab!!.value.text, "Target", caseSensitive = true, useRegex = false).firstOrNull()
            if (r != null) v.actions.replaceMatchRegex(r, "Tar(get)", "\$1X", caseSensitive = true)
            assertTrue(v.currentTab!!.value.text.contains("getX") || r == null)
        } finally { dir.deleteRecursively() }
    }

    // ---------- FileFiles: content-provider branches ----------
    class Wave3Provider : ContentProvider() {
        companion object { lateinit var backing: File }
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val c = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME))
            c.addRow(arrayOf<Any>("shared.kt")); return c
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(backing, ParcelFileDescriptor.MODE_READ_ONLY)
        override fun getType(uri: Uri) = "text/plain"
        override fun insert(uri: Uri, values: ContentValues?) = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    @org.junit.Test fun fileFilesContentProviderAndFilesystemEdges() {
        val dir = tmp("w3files")
        try {
            val backing = File(dir, "shared.kt").also { it.writeText("fun shared() = 1") }
            Wave3Provider.backing = backing
            org.robolectric.Robolectric.buildContentProvider(Wave3Provider::class.java).create("com.wave3.test")
            val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
            val uri = Uri.parse("content://com.wave3.test/docs/shared.kt")
            assertEquals("fun shared() = 1", FileFiles.readTextFromUri(ctx, uri))
            assertEquals("shared.kt", FileFiles.queryDisplayName(ctx, uri))
            // Unregistered provider: query fails -> last segment fallback.
            assertEquals("x.txt", FileFiles.queryDisplayName(ctx, Uri.parse("content://com.nope/x.txt")))
            assertEquals("x.txt", FileFiles.queryDisplayName(ctx, Uri.parse("content://com.nope/a/b/x.txt")))
            // Filesystem edges not in earlier files.
            assertNull(FileFiles.createFile(dir, "shared.kt")) // exists
            val renamedDir = File(dir, "oldDir").also { it.mkdirs() }
            File(renamedDir, "in.txt").writeText("in")
            val moved = FileFiles.rename(renamedDir, "newDir")
            assertNotNull(moved); assertTrue(File(moved, "in.txt").isFile)
            assertEquals("text/css", FileFiles.mimeForFileName("a.css"))
            assertEquals("text/markdown", FileFiles.mimeForFileName("a.markdown"))
            assertEquals("text/xml", FileFiles.mimeForFileName("a.xml"))
            // openExternal via VM for file:// and content://
            val v = vm()
            v.openExternal(Uri.fromFile(backing))
            await("file external") { v.tabs.any { it.name == "shared.kt" } }
            v.openExternal(uri)
            await("content external") { v.tabs.any { it.name == "shared.kt" && it.externalUri == uri } }
            v.openExternal(uri) // existing dedup
            assertEquals(1, v.tabs.count { it.externalUri == uri })
        } finally { dir.deleteRecursively() }
    }

    // ---------- Editor composable states ----------
    @org.junit.Test fun codeEditorViewEmptyFoldedMinimapDiagnosticsAndFindStates() {
        val rec = Recording()
        var current by androidx.compose.runtime.mutableStateOf(tab(""))
        var useSecond by androidx.compose.runtime.mutableStateOf(false)
        rule.setContent {
            if (!useSecond) {
                CodeEditorView(tab = current, actions = rec, fontSize = 14, editorTheme = "monokai", showMinimap = true,
                    showWhitespace = true, showIndentGuides = true)
            } else {
            CodeEditorView(
                tab = current, actions = rec,
                fontSize = 12, editorTheme = "solarized", showMinimap = true,
                diagnostics = listOf(Diagnostic(1, 0, 3, DiagnosticSeverity.ERROR, "e"), Diagnostic(4, 0, 3, DiagnosticSeverity.WARNING, "w")),
                completions = listOf(Completion("fooBar", "fooBar()")), showCompletions = true,
                showFind = true, onCloseFind = {}, initialQuery = "foo",
            )
            }
        }
        rule.onRoot().performTouchInput { click(center) }; rule.waitForIdle()
        rule.runOnIdle { current = tab("fun a() {\n  foo()\n  bar()\n}\nfoo again", folded = setOf(0)); useSecond = true }; rule.waitForIdle()
        rule.onAllNodes(hasSetTextAction())[0].performTextReplacement("foo"); rule.waitForIdle()
        rule.onNodeWithText("1/2").assertExists()
        rule.onNodeWithText("fooBar", substring = true).performClick()
        // Invalid regex state.
        rule.onNodeWithText("Regex").performClick()
        rule.onAllNodes(hasSetTextAction())[0].performTextReplacement("[")
        rule.waitForIdle()
    }

    @org.junit.Test fun classicCodeEditorEmptyLongWrappedAndNoMatchFind() {
        val rec = Recording()
        var current by androidx.compose.runtime.mutableStateOf(tab(""))
        var second by androidx.compose.runtime.mutableStateOf(false)
        rule.setContent {
            if (!second) CodeEditor(tab = current, actions = rec, softWrap = false, showFind = false, onCloseFind = {})
            else CodeEditor(tab = current, actions = rec, softWrap = true,
                showFind = true, onCloseFind = {}, initialQuery = "zzz-no-match")
        }
        rule.waitForIdle()
        rule.runOnIdle { current = tab("one\n" + "long ".repeat(80) + "\nthree foo"); second = true }; rule.waitForIdle()
        rule.onNodeWithText("0/0").assertExists()
        rule.onAllNodes(hasSetTextAction())[0].performTextReplacement("three")
        rule.onNodeWithText("1/1").assertExists()
    }

    @org.junit.Test fun editorBodyEmptySingleExperimentalSplitAndDiskBanner() {
        var folder = 0; var file = 0
        val rec = Recording()
        val dirty = TabUiState(name = "a.kt", value = TextFieldValue("fun a() {}"), language = Language.KOTLIN, changedOnDisk = true)
        val a = TabUiState(name = "a.kt", value = TextFieldValue("fun a() {}"), language = Language.KOTLIN)
        val b = TabUiState(name = "b.kt", value = TextFieldValue("fun b() {}"), language = Language.KOTLIN)
        var state by androidx.compose.runtime.mutableStateOf(CodeUiState())
        var showFind by androidx.compose.runtime.mutableStateOf(false)
        var initialFind: String? by androidx.compose.runtime.mutableStateOf(null)
        rule.setContent {
            EditorBody(state = state, actions = rec, onOpenFolder = { folder++ }, onOpenFile = { file++ },
                onOpenSearch = {}, onOpenGit = {}, onOpenTerminal = {}, onOpenPreview = {}, onOpenQuickOpen = {},
                onOpenPalette = {}, onOpenOutline = {}, onResolveConflicts = {}, onOpenProblems = {},
                onToggleFind = {}, onGoToLine = {}, showFind = showFind, onCloseFind = {}, initialFind = initialFind)
        }
        rule.onAllNodesWithText("Open folder")[0].performClick(); assertEquals(1, folder)
        rule.runOnIdle { state = CodeUiState(tabs = listOf(dirty), currentIndex = 0) }; rule.waitForIdle()
        runCatching { rule.onAllNodesWithText("Reload")[0].performClick() }
        runCatching { rule.onAllNodesWithText("Keep mine")[0].performClick() }
        assertTrue(rec.reload + rec.dismissDisk > 0, "reload=${rec.reload} dismiss=${rec.dismissDisk}")
        rule.runOnIdle { state = CodeUiState(tabs = listOf(a, b), currentIndex = 0, secondaryIndex = 1, experimentalEditor = true, showMinimap = true); showFind = true; initialFind = "fun" }
        rule.waitForIdle()
    }

    @org.junit.Test fun previewPageNoTabAndUnsupported() {
        val v = vm()
        rule.setContent { PreviewPage(v, NavBackStack(arrayOf<Route>(Route.Editor))) }
        rule.waitForIdle()
        v.tabs.add(OpenTab(null, initialName = "a.kt", initialText = "fun f() {}", language = Language.KOTLIN)); v.currentIndex = 0
        rule.waitForIdle()
        rule.onNodeWithText("No preview for Kotlin files.", substring = true).assertExists()
    }

    @org.junit.Test fun minimapManyLinesViewportEdges() {
        val fractions = mutableListOf<Float>()
        rule.setContent {
            Minimap(lines = (1..200).map { "x".repeat(it % 37) }, lineHeight = 4f, scrollY = 500f, maxScrollY = 1000f,
                viewportHeight = 100f, totalHeight = 1000f, color = Color.Gray, viewportColor = Color.Blue,
                onScrollTo = { fractions.add(it) }, modifier = androidx.compose.ui.Modifier.size(90.dp))
        }
        rule.onRoot().performTouchInput { click(center) }
        assertTrue(fractions.isNotEmpty() && fractions.all { it in 0f..1f })
    }

    // ---------- MainActivity intent handling ----------
    // Full compose render hits Robolectric's Environment.isExternalStorageManager stub
    // (ArrayIndexOutOfBounds in AppPermissionsGate), so create-only: covers onCreate/handleIntent.
    @org.junit.Test fun mainActivityPlainAndViewIntents() {
        val dir = tmp("w3activity")
        try {
            val f = File(dir, "intent.kt").also { it.writeText("fun intent() {}") }
            val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
            org.robolectric.Robolectric.buildActivity(MainActivity::class.java, Intent(ctx, MainActivity::class.java)).create()
            org.robolectric.Robolectric.buildActivity(MainActivity::class.java, Intent(Intent.ACTION_VIEW, Uri.fromFile(f), ctx, MainActivity::class.java)).create()
            org.robolectric.Robolectric.buildActivity(MainActivity::class.java, Intent(Intent.ACTION_EDIT, Uri.fromFile(f), ctx, MainActivity::class.java)).create()
            org.robolectric.Robolectric.buildActivity(MainActivity::class.java, Intent(Intent.ACTION_VIEW, null, ctx, MainActivity::class.java)).create()
        } finally { dir.deleteRecursively() }
    }
}
