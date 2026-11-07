package com.vayunmathur.calculator.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.calculator.util.CalculatorViewModel
import com.vayunmathur.calculator.util.UnitConverterActions
import com.vayunmathur.calculator.util.UnitConverterUiState
import com.vayunmathur.calculator.util.UnitCategory
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class PageBindingsComposeTest {

    @get:Rule val rule = createComposeRule()

    private fun vm() = CalculatorViewModel(ApplicationProvider.getApplicationContext<Application>())

    @Test fun graphPageBindingRenders() {
        rule.mainClock.autoAdvance = false
        rule.setContent { GraphPage(vm()) }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Add function").assertIsDisplayed()
    }

    @Test fun converterPageBindingRenders() {
        rule.mainClock.autoAdvance = false
        rule.setContent { UnitConverterPage(vm()) }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Units").assertIsDisplayed()
    }

    @Test fun currencyErrorStateOffersRetry() {
        var retries = 0
        val actions = object : UnitConverterActions {
            override fun retryCurrency() { retries++ }
        }
        rule.setContent {
            UnitConverterScreen(
                state = UnitConverterUiState(
                    categories = listOf(UnitCategory("Currency", emptyList(), inEquations = false)),
                    selectedCategoryIndex = 0,
                    isCurrencyCategory = true,
                    currencyLoading = false,
                    currencyError = "Couldn't load exchange rates",
                ),
                actions = actions,
            )
        }
        rule.onNodeWithText("Couldn't load exchange rates").assertIsDisplayed()
        rule.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
    }
}
