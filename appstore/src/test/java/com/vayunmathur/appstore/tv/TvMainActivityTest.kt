package com.vayunmathur.appstore.tv

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        composeRule.waitForIdle()
        for (label in listOf("Home", "Search", "Updates", "Library", "Sources")) {
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
        composeRule.onNodeWithText("Library").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Sources").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Updates").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Search").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Home").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Home").assertIsDisplayed()
    }
}
