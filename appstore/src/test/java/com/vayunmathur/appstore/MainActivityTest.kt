package com.vayunmathur.appstore

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.library.util.databases
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner

/**
 * Runs before the activity launches: WorkManager must exist before
 * MainActivity schedules the update check in onCreate, and the
 * production SQLCipher database (AndroidKeyStore) cannot open under
 * Robolectric, so an in-memory database is bound into the shared
 * builder cache the activity's repository consults first.
 */
private class AppStoreSeedRule : TestRule {
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
 * The phone entry point under Robolectric: MainActivity wires its
 * ViewModel, renders the five-tab pager, and each tab can be selected.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityTest {

    val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rule: RuleChain = RuleChain.outerRule(AppStoreSeedRule()).around(composeRule)

    private fun exists(text: String): Boolean =
        composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `five tabs render and can be selected`() {
        composeRule.waitForIdle()
        for (label in listOf("Home", "Search", "Updates", "Library", "Sources")) {
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
        composeRule.onNodeWithText("Search").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Library").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Sources").performClick()
        composeRule.waitForIdle()
        assertTrue(exists("Sources") || exists("Modern Apps"), "sources tab did not render")
        composeRule.onNodeWithText("Home").performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun `resuming the activity keeps the store usable`() {
        composeRule.waitForIdle()
        composeRule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        composeRule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Home").assertIsDisplayed()
    }
}

/**
 * Launching with a `market://` link opens that package's detail page:
 * the external-package hand-off in AppRoot selects it and pushes Detail.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityExternalLinkTest {

    private val intent = Intent(Intent.ACTION_VIEW).apply {
        data = Uri.parse("market://details?id=com.example.deep")
        component = android.content.ComponentName(
            "com.vayunmathur.appstore",
            "com.vayunmathur.appstore.MainActivity",
        )
    }

    val composeRule = AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>(
        ActivityScenarioRule<MainActivity>(intent),
    ) { scenarioRule ->
        lateinit var activity: MainActivity
        scenarioRule.scenario.onActivity { activity = it }
        activity
    }

    @get:Rule
    val rule: RuleChain = RuleChain.outerRule(AppStoreSeedRule()).around(composeRule)

    @Test
    fun `market link opens the named package`() {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("com.example.deep", substring = true)
                .fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("deep", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }
}
