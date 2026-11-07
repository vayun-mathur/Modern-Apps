package com.vayunmathur.logviewer.intents

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class ErrorReportActivityTest {

    @get:Rule
    val composeRule = AndroidComposeTestRule<ActivityScenarioRule<ErrorReportActivity>, ErrorReportActivity>(
        ActivityScenarioRule(reportIntent()),
    ) { rule ->
        var activity: ErrorReportActivity? = null
        rule.scenario.onActivity { activity = it }
        activity!!
    }

    companion object {
        private fun gzip(text: String): ByteArray {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            return out.toByteArray()
        }

        private fun reportIntent(): Intent {
            val appInfo = ApplicationInfo().apply {
                packageName = "com.example.crashed"
            }
            return Intent(
                ApplicationProvider.getApplicationContext(),
                ErrorReportActivity::class.java,
            )
                .setAction(LogViewerIntents.ACTION_ERROR_REPORT)
                .putExtra(
                    LogViewerIntents.EXTRA_GZIPPED_MESSAGE,
                    gzip("java.lang.RuntimeException: boom\n\tat com.example.Foo.bar(Foo.kt:7)"),
                )
                .putExtra(LogViewerIntents.EXTRA_SOURCE_APP_INFO, appInfo)
                .putExtra(LogViewerIntents.EXTRA_SHOW_REPORT_BUTTON, true)
        }
    }

    @Test
    fun `crash report loads with its title body and report button`() {
        composeRule.onNodeWithText("Error in com.example.crashed").assertIsDisplayed()
        composeRule.onNodeWithText("java.lang.RuntimeException: boom").assertIsDisplayed()
        composeRule.onNodeWithText("Report").assertIsDisplayed()
        // No tombstone is available, so More info is not offered; the crashed app's log is.
        composeRule.onNodeWithText("More info").assertDoesNotExist()
        composeRule.onNodeWithText("Show log").assertIsDisplayed()
    }

    @Test
    fun `show log opens the logcat activity for the crashed package`() {
        composeRule.onNodeWithText("Show log").performClick()
        val started = shadowOf(composeRule.activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(
            LogcatActivity::class.java.name,
            started.component?.className,
        )
        assertEquals(
            "com.example.crashed",
            started.getStringExtra(Intent.EXTRA_PACKAGE_NAME),
        )
    }

    @Test
    fun `report button copies and opens the tracker`() {
        composeRule.onNodeWithText("Report").performClick()
        val started = shadowOf(composeRule.activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertTrue(started.data.toString().contains("Modern-Apps/issues"))
    }
}
