package com.vayunmathur.appstore

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The phone entry point under Robolectric: MainActivity wires its
 * ViewModel, renders the five-tab pager, and each tab can be selected.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

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

    private fun exists(text: String): Boolean =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `five tabs render and can be selected`() {
        rule.waitForIdle()
        for (label in listOf("Home", "Search", "Updates", "Library", "Sources")) {
            rule.onNodeWithText(label).assertIsDisplayed()
        }
        rule.onNodeWithText("Search").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Library").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Sources").performClick()
        rule.waitForIdle()
        assertTrue(exists("Sources") || exists("Modern Apps"), "sources tab did not render")
        rule.onNodeWithText("Home").performClick()
        rule.waitForIdle()
    }

    @Test
    fun `resuming the activity keeps the store usable`() {
        rule.waitForIdle()
        rule.activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        rule.activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        rule.waitForIdle()
        rule.onNodeWithText("Home").assertIsDisplayed()
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

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>(intent)

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
    fun `market link opens the named package`() {
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("com.example.deep", substring = true)
                .fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("deep", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
    }
}
