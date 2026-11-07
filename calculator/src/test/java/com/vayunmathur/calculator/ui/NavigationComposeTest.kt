package com.vayunmathur.calculator.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.calculator.Navigation
import com.vayunmathur.calculator.util.CalculatorViewModel
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test

@RunWith(RobolectricTestRunner::class)
class NavigationComposeTest {

    @get:Rule val rule = createComposeRule()

    @Test fun navigationRendersCalculatorTab() {
        // NavDisplay entry animations never go idle under Robolectric's
        // paused clock, so drive frames manually instead of waiting.
        rule.mainClock.autoAdvance = false
        val vm = CalculatorViewModel(ApplicationProvider.getApplicationContext<Application>())
        rule.setContent { Navigation(vm) }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Calculator").assertIsDisplayed()
        rule.onNodeWithText("7").assertIsDisplayed()
    }

    @Test fun navigationRendersUnitsFirstWhenRequested() {
        rule.mainClock.autoAdvance = false
        val vm = CalculatorViewModel(ApplicationProvider.getApplicationContext<Application>())
        rule.setContent { Navigation(vm, unitsRequest = 1) }
        rule.mainClock.advanceTimeBy(500)
        rule.onAllNodesWithText("Units")[0].assertIsDisplayed()
    }
}
