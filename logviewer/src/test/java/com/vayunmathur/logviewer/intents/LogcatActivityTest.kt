package com.vayunmathur.logviewer.intents

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.logviewer.platform.LogcatReader
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class LogcatActivityTest {

    @get:Rule
    val composeRule = AndroidComposeTestRule<ActivityScenarioRule<LogcatActivity>, LogcatActivity>(
        ActivityScenarioRule(
            Intent(ApplicationProvider.getApplicationContext(), LogcatActivity::class.java)
                .setAction(LogViewerIntents.ACTION_LOGCAT),
        ),
    ) { rule ->
        var activity: LogcatActivity? = null
        rule.scenario.onActivity { activity = it }
        activity!!
    }

    companion object {
        private val originalExecutor = LogcatReader.executeCommand

        @JvmStatic
        @BeforeClass
        fun installFakeLogcat() {
            // Echo the level selector back as a line so reloads are observable on screen.
            LogcatReader.executeCommand = { command ->
                listOf("log line for ${command.last()}", "second log line")
            }
        }

        @JvmStatic
        @AfterClass
        fun restoreLogcat() {
            LogcatReader.executeCommand = originalExecutor
        }
    }

    @Test
    fun `system log loads and shows the fake logcat output`() {
        composeRule.onNodeWithText("System log").assertIsDisplayed()
        composeRule.onNodeWithText("log line for *:V").assertIsDisplayed()
        composeRule.onNodeWithText("second log line").assertIsDisplayed()
    }

    @Test
    fun `changing the level reloads with the new filter in the title`() {
        composeRule.onNodeWithText("V").performClick()
        composeRule.onNodeWithText("Debug").performClick()
        composeRule.onNodeWithText("log line for *:D").assertIsDisplayed()
        composeRule.onNodeWithText("System log | D+").assertIsDisplayed()
    }

    // The filter dialog itself is covered by LogViewerScreenTest; opening it in a real
    // activity under Robolectric never reaches idle (focus/keyboard), so it is not
    // re-asserted here. The reload it triggers is covered by the level change above.

    @Test
    fun `activity stays alive for the system log`() {
        assertTrue(!composeRule.activity.isFinishing)
    }
}
