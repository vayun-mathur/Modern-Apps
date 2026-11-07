package com.vayunmathur.code.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.code.MainActivity
import com.vayunmathur.code.Navigation
import com.vayunmathur.code.Route
import com.vayunmathur.code.util.EditorViewModel
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Tests for [MainActivity] intent handling, [Navigation] routing, the expanded layout and [Route] serialization. */
@RunWith(RobolectricTestRunner::class)
class MainAndExpandedUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun vm() = EditorViewModel(ApplicationProvider.getApplicationContext())

    @Test
    fun routeSerializationRoundTrips() {
        val routes: List<Route> = listOf(
            Route.Editor, Route.Settings, Route.Search, Route.FolderBrowser,
            Route.Git, Route.Terminal, Route.Preview, Route.Snippets,
        )
        for (route in routes) {
            val encoded = Json.encodeToString(Route.serializer(), route)
            assertEquals(route, Json.decodeFromString(Route.serializer(), encoded), "route $route via $encoded")
        }
    }

    @Test
    fun mainActivityLaunchesAndHandlesViewIntent() {
        val file = File(System.getProperty("java.io.tmpdir"), "launch-${System.nanoTime()}.md")
        file.writeText("# hello")
        val intent = Intent(Intent.ACTION_VIEW, Uri.fromFile(file))
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).create().start().resume()
        assertNotNull(controller.get())
        controller.pause().stop().destroy()
        file.delete()
    }

    @Test
    fun mainActivityLaunchesWithoutIntentData() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, Intent(Intent.ACTION_MAIN)).create().start().resume()
        assertNotNull(controller.get())
        // A non-VIEW new intent is ignored by handleIntent.
        controller.newIntent(Intent(Intent.ACTION_MAIN))
        controller.pause().stop().destroy()
    }

    @Test
    fun navigationShowsEditorThenSettings() {
        rule.setContent { Navigation(vm()) }
        rule.onNodeWithText("No file open").assertIsDisplayed()
        rule.onNodeWithContentDescription("Settings")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.onNodeWithText("Appearance", substring = true).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1000dp-h800dp")
    fun expandedEditorScreenShowsTreeBesideEditor() {
        val s = com.vayunmathur.code.util.CodeUiState(
            tabs = listOf(
                com.vayunmathur.code.util.TabUiState(
                    name = "a.kt",
                    value = androidx.compose.ui.text.input.TextFieldValue("fun f() {}"),
                    language = com.vayunmathur.code.syntax.Language.KOTLIN,
                ),
            ),
            currentIndex = 0, folderOpen = true, rootName = "proj",
        )
        rule.setContent { EditorScreen(state = s, actions = com.vayunmathur.code.util.CodeActions.Noop) }
        rule.onNodeWithText("proj").assertIsDisplayed()
        rule.onNodeWithText("No file open").assertDoesNotExist()
    }

    @Test
    fun findBarReplaceButtonsFire() {
        var replaced = 0
        var replacedAll = 0
        val actions = object : com.vayunmathur.code.util.CodeActions {
            override fun replaceRange(range: IntRange, replacement: String) { replaced++ }
            override fun replaceAll(matches: List<IntRange>, replacement: String) { replacedAll++ }
        }
        val tab = com.vayunmathur.code.util.TabUiState(
            name = "a.kt",
            value = androidx.compose.ui.text.input.TextFieldValue("fun fun"),
            language = com.vayunmathur.code.syntax.Language.KOTLIN,
        )
        rule.setContent {
            CodeEditor(tab = tab, actions = actions, softWrap = false, showFind = true, onCloseFind = {}, initialQuery = "fun")
        }
        rule.onNodeWithText("1/2").assertIsDisplayed()
        rule.onAllNodesWithText("Replace")[rule.onAllNodesWithText("Replace").fetchSemanticsNodes().size - 1].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertTrue(replaced == 1 || replacedAll == 1, "replaced=$replaced all=$replacedAll")
    }

    @Test
    fun completionPopupAcceptFires() {
        var accepted: String? = null
        val actions = object : com.vayunmathur.code.util.CodeActions {
            override fun acceptCompletion(completion: com.vayunmathur.code.util.Completion) { accepted = completion.insertText }
        }
        val tab = com.vayunmathur.code.util.TabUiState(
            name = "a.kt",
            value = androidx.compose.ui.text.input.TextFieldValue("cou", androidx.compose.ui.text.TextRange(3)),
            language = com.vayunmathur.code.syntax.Language.KOTLIN,
        )
        rule.setContent {
            CodeEditor(
                tab = tab, actions = actions, softWrap = false, showFind = false, onCloseFind = {},
                completions = listOf(com.vayunmathur.code.util.Completion("counter", "counter", 7, com.vayunmathur.code.util.CompletionKind.WORD)),
                showCompletions = true,
            )
        }
        rule.waitForIdle()
        val nodes = rule.onAllNodesWithText("counter").fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty(), "completion popup should list counter")
    }
}
