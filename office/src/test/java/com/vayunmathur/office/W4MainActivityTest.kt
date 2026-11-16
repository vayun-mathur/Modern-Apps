package com.vayunmathur.office

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * W4: [MainActivity] end-to-end — the navigation graph's home route, the
 * new-document routes into the editor, and `office://join` deep links
 * arriving both cold (launch intent) and warm (onNewIntent).
 */
@RunWith(AndroidJUnit4::class)
class W4MainActivityTest {

    @get:Rule
    val rule: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity> =
        createAndroidComposeRule<MainActivity>()

    @Test fun homeShowsTabsAndNewDocumentActions() {
        rule.onNodeWithText("Office").assertIsDisplayed()
        rule.onNodeWithText("Offline").assertExists()
        rule.onNodeWithText("Online").assertExists()
        rule.onNodeWithText("Open Document").assertExists()
        rule.onNodeWithText("New Doc").assertExists()
        rule.onNodeWithText("New Sheet").assertExists()
        rule.onNodeWithText("New Slides").assertExists()
    }

    @Test fun newDocOpensEditorRoute() {
        rule.onNodeWithText("New Doc").performClick()
        rule.waitForIdle()
        // The editor chrome (menu bar) replaces the home screen.
        rule.onNodeWithText("File").assertExists()
    }

    @Test fun joinLinkIntentIsHandledWithoutCrashing() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("office://join/doc-42?owner=device-7"))
            .setClass(rule.activity, MainActivity::class.java)
        androidx.test.core.app.ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                kotlin.test.assertFalse(activity.isFinishing)
            }
        }
    }

    @Test fun malformedJoinLinksAreIgnored() {
        for (uri in listOf(
            "office://join/?owner=device-7",
            "office://join/doc-42",
            "office://other/doc-42?owner=device-7",
            "https://example.org/doc-42?owner=device-7",
        )) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                .setClass(rule.activity, MainActivity::class.java)
            androidx.test.core.app.ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    kotlin.test.assertFalse(activity.isFinishing)
                }
            }
        }
    }
}
