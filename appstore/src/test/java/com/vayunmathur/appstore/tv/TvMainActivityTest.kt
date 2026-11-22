package com.vayunmathur.appstore.tv
import com.vayunmathur.appstore.ui.tv.TvMainActivity

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.library.util.databases
import kotlin.test.Test
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner

/**
 * Runs before the TV activity launches: WorkManager must exist before
 * TvMainActivity schedules the update check, and the production
 * SQLCipher database cannot open under Robolectric, so an in-memory
 * database is bound into the shared builder cache first.
 */
private class TvSeedRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            runCatching {
                androidx.work.WorkManager.initialize(
                    app,
                    androidx.work.Configuration.Builder().build(),
                )
            }
            synchronized(databases) {
                databases[AppDatabase::class] as? AppDatabase
                    ?: Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
                        .setDriver(BundledSQLiteDriver())
                        .allowMainThreadQueries()
                        .build()
                        .also { databases[AppDatabase::class] = it }
            }
            base.evaluate()
        }
    }
}

/**
 * The TV entry point under Robolectric: TvMainActivity hosts the rail,
 * and selecting rail destinations swaps the content beside it.
 */
@RunWith(RobolectricTestRunner::class)
class TvMainActivityTest {

    val composeRule = createAndroidComposeRule<TvMainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(TvSeedRule()).around(composeRule)

    @Test
    fun `rail renders and destinations can be selected`() {
        // The activity's first composition lands on the paused Robolectric
        // main looper, which waitForIdle alone does not pump: wait for the
        // rail itself, idling that looper per poll (as the destination
        // waits below already do).
        composeRule.waitUntil(timeoutMillis = 15_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            composeRule.onAllNodesWithText("Home").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        for (label in listOf("Home", "Search", "Updates", "Library", "Sources")) {
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
        composeRule.onNodeWithText("Library").performSemanticsAction(SemanticsActions.RequestFocus)
        // The destination content lands via viewModelScope (Dispatchers.Main):
        // Robolectric's paused main looper only runs when idled, and the
        // compose rule's waitUntil does not pump it, so idle it per poll.
        composeRule.waitUntil(timeoutMillis = 15_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            composeRule.onAllNodesWithText("Apps you install are listed here", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Sources").performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitUntil(timeoutMillis = 15_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            composeRule.onAllNodesWithText("Automatically install updates", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Updates").performSemanticsAction(SemanticsActions.RequestFocus)
        // The remaining destinations were only waitForIdle'd: their content
        // lands via viewModelScope on the paused main looper (and Updates /
        // Home first show an indeterminate loading spinner, which never
        // idles), so under load the focus hops raced content that had not
        // composed yet. Wait on each destination's own terminal text,
        // idling the looper per poll like the Library/Sources legs above.
        composeRule.waitUntil(timeoutMillis = 15_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            composeRule.onAllNodesWithText("Everything is up to date", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Search").performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitUntil(timeoutMillis = 15_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            composeRule.onAllNodesWithText("Search for an app", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Return home the way the frame's own test does (TvRouteAppFrameTest):
        // focus the rail item first (focusing is what selects it), then invoke
        // its click, which moves focus into the new content. Firing either
        // alone here left focus on the outgoing entry's rail during the
        // sibling crossfade, and its focus restorer re-selected Search.
        val homeNode = composeRule.onNodeWithText("Home")
        homeNode.performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitForIdle()
        homeNode.fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action?.invoke()
        // Home's terminal state is its first catalog section (the seeded
        // in-memory catalog is non-empty, so the empty state never shows).
        composeRule.waitUntil(timeoutMillis = 15_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            composeRule.onAllNodesWithText("New from Modern Apps", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Home").assertIsDisplayed()
    }
}
