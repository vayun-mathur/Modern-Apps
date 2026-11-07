package com.vayunmathur.files.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.vayunmathur.files.platform.TestFileProvider
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Registers the file provider and seeds the edited file before the activity launches. */
class SeedEditorFile : TestRule {
    companion object {
        lateinit var dir: File
    }

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val ctx = ApplicationProvider.getApplicationContext<Context>()
                dir = File(ctx.cacheDir, "editor-compose").apply { mkdirs() }
                File(dir, "note.txt").writeText("hello editor")
                TestFileProvider.rootDir = dir
                Robolectric.buildContentProvider(TestFileProvider::class.java).create("testdocs")
                base.evaluate()
            }
        }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w812dp-h1280dp")
class TextEditorComposeTest {

    private val intent = Intent(Intent.ACTION_VIEW).apply {
        component = ComponentName(
            "com.vayunmathur.files",
            "com.vayunmathur.files.ui.TextEditorActivity",
        )
        data = Uri.parse("content://testdocs/note.txt")
    }

    val rule = AndroidComposeTestRule(
        ActivityScenarioRule<TextEditorActivity>(intent),
        activityProvider = { r ->
            var found: TextEditorActivity? = null
            r.scenario.onActivity { found = it }
            checkNotNull(found)
        },
    )

    @get:Rule
    val chain: TestRule = RuleChain.outerRule(SeedEditorFile()).around(rule)

    @Test
    fun `editor shows content toggles editing and saves`() {
        rule.waitForIdle()
        rule.onNodeWithText("note.txt").assertIsDisplayed()
        // loaded content is shown (loading state resolves through the provider)
        val deadline = System.currentTimeMillis() + 10_000
        var loaded = false
        while (!loaded && System.currentTimeMillis() < deadline) {
            rule.waitForIdle()
            loaded = rule.onAllNodesWithText("hello editor").fetchSemanticsNodes().isNotEmpty()
            if (!loaded) Thread.sleep(50)
        }
        assertTrue(loaded)
        // the single top-bar action toggles edit mode; typing then saving writes back
        val nodes = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        val topBar = nodes.withIndex().filter { it.value.positionInRoot.y < 120f }
        assertTrue(topBar.isNotEmpty())
        rule.onAllNodes(hasClickAction())[topBar.last().index].performClick()
        rule.onNodeWithText("hello editor").performTextInput(" updated")
        rule.onAllNodes(hasClickAction())[
            rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().withIndex()
                .filter { it.value.positionInRoot.y < 120f }.last().index,
        ].performClick()
        // save runs on IO; poll the file
        val saveDeadline = System.currentTimeMillis() + 10_000
        var saved = false
        while (!saved && System.currentTimeMillis() < saveDeadline) {
            saved = File(SeedEditorFile.dir, "note.txt").readText().contains("updated")
            if (!saved) Thread.sleep(50)
        }
        assertTrue(saved)
    }
}
