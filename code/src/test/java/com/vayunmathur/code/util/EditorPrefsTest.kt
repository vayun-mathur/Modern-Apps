package com.vayunmathur.code.util

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Robolectric tests for [EditorPrefs] DataStore round-trips (defaults, setters, clears). */
@RunWith(RobolectricTestRunner::class)
class EditorPrefsTest {

    private fun prefs() = EditorPrefs(ApplicationProvider.getApplicationContext())

    @Test
    fun defaults() = runTest {
        val p = prefs()
        assertNull(p.folderPath.first())
        assertEquals(false, p.softWrap.first())
        assertEquals(EditorPrefs.DEFAULT_FONT_SIZE, p.fontSize.first())
        assertEquals(EditorPrefs.DEFAULT_TAB_WIDTH, p.tabWidth.first())
        // Note: theme/git-credential values may persist from other tests sharing the
        // Robolectric application DataStore file, so only stateless/shape defaults here.
        assertEquals(true, p.autoIndent.first())
        assertEquals(true, p.autoCloseBrackets.first())
        assertEquals(false, p.autoSave.first())
        assertEquals(false, p.trimTrailingOnSave.first())
        assertEquals(false, p.finalNewlineOnSave.first())
        assertEquals(false, p.experimentalEditor.first())
        assertEquals(false, p.showWhitespace.first())
        assertEquals(false, p.showIndentGuides.first())
        assertEquals(false, p.showMinimap.first())
        assertTrue(p.sessionPaths.first().isEmpty())
        assertNull(p.sessionCurrent.first())
        assertTrue(p.recentFiles.first().isEmpty())
        assertTrue(p.userSnippets.first().isEmpty())
        assertTrue(p.foldState.first().isEmpty())
    }

    @Test
    fun folderPathSetAndClear() = runTest {
        val p = prefs()
        p.setFolderPath("/proj")
        assertEquals("/proj", p.folderPath.first())
        p.clearFolderPath()
        assertNull(p.folderPath.first())
    }

    @Test
    fun editorTogglesRoundTrip() = runTest {
        val p = prefs()
        p.setSoftWrap(true)
        p.setFontSize(20)
        p.setTabWidth(2)
        p.setThemeMode(EditorPrefs.THEME_DARK)
        p.setAutoIndent(false)
        p.setAutoCloseBrackets(false)
        p.setAutoSave(true)
        p.setTrimTrailingOnSave(true)
        p.setFinalNewlineOnSave(true)
        p.setExperimentalEditor(true)
        p.setShowWhitespace(true)
        p.setShowIndentGuides(true)
        p.setShowMinimap(true)
        p.setEditorTheme("monokai")
        assertEquals(true, p.softWrap.first())
        assertEquals(20, p.fontSize.first())
        assertEquals(2, p.tabWidth.first())
        assertEquals(EditorPrefs.THEME_DARK, p.themeMode.first())
        assertEquals(false, p.autoIndent.first())
        assertEquals(false, p.autoCloseBrackets.first())
        assertEquals(true, p.autoSave.first())
        assertEquals(true, p.trimTrailingOnSave.first())
        assertEquals(true, p.finalNewlineOnSave.first())
        assertEquals(true, p.experimentalEditor.first())
        assertEquals(true, p.showWhitespace.first())
        assertEquals(true, p.showIndentGuides.first())
        assertEquals(true, p.showMinimap.first())
        assertEquals("monokai", p.editorTheme.first())
    }

    @Test
    fun sessionSetAndClear() = runTest {
        val p = prefs()
        p.setSession(listOf("/a.kt", "/b.kt"), "/b.kt")
        assertEquals(listOf("/a.kt", "/b.kt"), p.sessionPaths.first())
        assertEquals("/b.kt", p.sessionCurrent.first())
        p.setSession(emptyList(), null)
        assertTrue(p.sessionPaths.first().isEmpty())
        assertNull(p.sessionCurrent.first())
    }

    @Test
    fun recentFilesSetAndClear() = runTest {
        val p = prefs()
        p.setRecentFiles(listOf("/a.kt"))
        assertEquals(listOf("/a.kt"), p.recentFiles.first())
        p.setRecentFiles(emptyList())
        assertTrue(p.recentFiles.first().isEmpty())
    }

    @Test
    fun userSnippetsSetAndClear() = runTest {
        val p = prefs()
        p.setUserSnippets(listOf(UserSnippet("log", "f($0)")))
        assertEquals(listOf(UserSnippet("log", "f($0)")), p.userSnippets.first())
        p.setUserSnippets(emptyList())
        assertTrue(p.userSnippets.first().isEmpty())
    }

    @Test
    fun foldStateSetAndClear() = runTest {
        val p = prefs()
        p.setFoldState(mapOf("/a.kt" to listOf(1, 2)))
        assertEquals(mapOf("/a.kt" to listOf(1, 2)), p.foldState.first())
        p.setFoldState(emptyMap())
        assertTrue(p.foldState.first().isEmpty())
        // Entries with empty line lists are dropped.
        p.setFoldState(mapOf("/a.kt" to emptyList()))
        assertTrue(p.foldState.first().isEmpty())
    }

    @Test
    fun gitCredentialsRoundTrip() = runTest {
        val p = prefs()
        p.setGitUsername("user")
        p.setGitToken("tok")
        p.setGitAuthorName("Name")
        p.setGitAuthorEmail("n@e.c")
        assertEquals("user", p.gitUsername.first())
        assertEquals("tok", p.gitToken.first())
        assertEquals("Name", p.gitAuthorName.first())
        assertEquals("n@e.c", p.gitAuthorEmail.first())
    }

    @Test
    fun themeConstants() {
        assertEquals("system", EditorPrefs.THEME_SYSTEM)
        assertEquals("light", EditorPrefs.THEME_LIGHT)
        assertEquals("dark", EditorPrefs.THEME_DARK)
        assertEquals(14, EditorPrefs.DEFAULT_FONT_SIZE)
        assertEquals(4, EditorPrefs.DEFAULT_TAB_WIDTH)
    }
}
