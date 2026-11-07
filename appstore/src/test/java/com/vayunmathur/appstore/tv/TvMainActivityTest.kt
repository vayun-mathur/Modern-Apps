package com.vayunmathur.appstore.tv

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The TV entry point under Robolectric: TvMainActivity hosts the rail,
 * and selecting rail destinations swaps the content beside it.
 */
@RunWith(RobolectricTestRunner::class)
class TvMainActivityTest {

    @get:Rule
    val rule = createAndroidComposeRule<TvMainActivity>()

    @BeforeTest
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        runCatching {
            WorkManager.initialize(
                ctx,
                Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build(),
            )
        }
    }

    @Test
    fun `rail renders and destinations can be selected`() {
        rule.waitForIdle()
        for (label in listOf("Home", "Search", "Updates", "Library", "Sources")) {
            rule.onNodeWithText(label).assertIsDisplayed()
        }
        rule.onNodeWithText("Library").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Sources").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Updates").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Search").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Home").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Home").assertIsDisplayed()
    }
}
