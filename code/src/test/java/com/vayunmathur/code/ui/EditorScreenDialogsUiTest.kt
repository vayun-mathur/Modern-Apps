package com.vayunmathur.code.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.code.syntax.Language
import com.vayunmathur.code.util.CodeActions
import com.vayunmathur.code.util.CodeUiState
import com.vayunmathur.code.util.Diagnostic
import com.vayunmathur.code.util.DiagnosticSeverity
import com.vayunmathur.code.util.TabUiState
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertTrue

/** Robolectric tests driving [EditorScreen] dialogs through its real toolbar/top-bar controls. */
@RunWith(AndroidJUnit4::class)
class EditorScreenDialogsUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun clickRow(text: String) {
        rule.onNode(hasClickAction() and hasAnyDescendant(hasText(text)), useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
    }

    private fun tabbed(text: String = "fun f() {}", dirty: Boolean = false) = CodeUiState(
        tabs = listOf(TabUiState(name = "a.kt", value = TextFieldValue(text), language = Language.KOTLIN, isDirty = dirty)),
        currentIndex = 0,
    )

    private fun openMore() = rule.onNodeWithContentDescription("More").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)

    @Test
    fun quickOpenDialogFromTopBar() {
        rule.setContent { EditorScreen(state = tabbed(), actions = CodeActions.Noop) }
        rule.onNodeWithContentDescription("Search")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.onNodeWithText("Quick open").assertIsDisplayed()
    }

    @Test
    fun settingsIconFiresCallback() {
        var settings = false
        rule.setContent { EditorScreen(state = tabbed(), actions = CodeActions.Noop, onOpenSettings = { settings = true }) }
        rule.onNodeWithContentDescription("Settings")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertTrue(settings)
    }

    @Test
    fun commandPaletteDialogFromOverflow() {
        rule.setContent { EditorScreen(state = tabbed(), actions = CodeActions.Noop) }
        openMore()
        clickRow("Command palette")
        rule.onNodeWithText("Type a command").assertExists()
    }

    @Test
    fun goToLineDialogFromOverflow() {
        var line = -1
        val actions = object : CodeActions {
            override fun goToLine(target: Int) { line = target }
        }
        rule.setContent { EditorScreen(state = tabbed(), actions = actions) }
        openMore()
        clickRow("Go to line")
        rule.onNodeWithText("Line", substring = true).assertExists()
        assertTrue(line == -1)
    }

    @Test
    fun outlineDialogFromOverflow() {
        rule.setContent { EditorScreen(state = tabbed("fun alpha() {}\nfun beta() {}"), actions = CodeActions.Noop) }
        openMore()
        clickRow("Go to symbol")
        rule.onNodeWithText("alpha").assertExists()
    }

    @Test
    fun problemsDialogFromOverflow() {
        val s = tabbed().copy(diagnostics = listOf(Diagnostic(0, 0, 3, DiagnosticSeverity.ERROR, "boom-marker")))
        rule.setContent { EditorScreen(state = s, actions = CodeActions.Noop) }
        openMore()
        clickRow("Problems")
        rule.onNodeWithText("boom-marker").assertExists()
    }

    @Test
    fun mergeResolverFromOverflow() {
        val text = "a\n<<<<<<< HEAD\nours\n=======\ntheirs\n>>>>>>> branch\nb"
        rule.setContent { EditorScreen(state = tabbed(text), actions = CodeActions.Noop) }
        openMore()
        clickRow("Resolve conflicts")
        rule.onNodeWithText("ours").assertExists()
        rule.onNodeWithText("theirs").assertExists()
    }

    @Test
    fun drawerOpensFromMenuIcon() {
        rule.setContent { EditorScreen(state = CodeUiState(), actions = CodeActions.Noop) }
        rule.onNodeWithContentDescription("Menu")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNodeWithText("No file open").assertIsDisplayed()
    }

    @Test
    fun exitGuardOnBackWithDirtyTab() {
        var exited = false
        rule.setContent {
            EditorScreen(state = tabbed(dirty = true), actions = CodeActions.Noop, onExitApp = { exited = true })
        }
        rule.runOnUiThread<Unit> { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("Unsaved changes").assertIsDisplayed()
        clickRow("Discard")
        assertTrue(exited)
    }

    @Test
    fun editorPageBindsViewModel() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>() as android.app.Application
        val v = com.vayunmathur.code.util.EditorViewModel(ctx)
        rule.setContent { EditorPage(v) }
        rule.onNodeWithText("No file open").assertIsDisplayed()
    }
}
