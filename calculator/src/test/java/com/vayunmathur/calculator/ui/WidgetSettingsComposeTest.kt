package com.vayunmathur.calculator.ui

import android.appwidget.AppWidgetManager
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.vayunmathur.calculator.widget.CalculatorWidgetSettingsActivity
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test

@RunWith(RobolectricTestRunner::class)
class WidgetSettingsComposeTest {

    private val intent = Intent(
        ApplicationProvider.getApplicationContext(),
        CalculatorWidgetSettingsActivity::class.java,
    ).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 7)

    @get:Rule
    val rule = AndroidComposeTestRule<ActivityScenarioRule<CalculatorWidgetSettingsActivity>, CalculatorWidgetSettingsActivity>(
        ActivityScenarioRule<CalculatorWidgetSettingsActivity>(intent),
    ) { scenarioRule ->
        lateinit var activity: CalculatorWidgetSettingsActivity
        scenarioRule.scenario.onActivity { activity = it }
        activity
    }

    @Test fun settingsScreenShowsTransparencySwitch() {
        rule.onNodeWithText("Transparent background").assertIsDisplayed()
        rule.onNodeWithText("Show the wallpaper behind the keypad. The keys stay filled in so they remain readable.")
            .assertIsDisplayed()
    }
}
