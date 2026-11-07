package com.vayunmathur.calculator.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.calculator.util.AngleMode
import com.vayunmathur.calculator.util.CalculatorActions
import com.vayunmathur.calculator.util.CalculatorUiState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class LandscapeAndDialogsTest {

    @get:Rule val rule = createComposeRule()

    @Test fun landscapeDisplayShowsEquationAndPreview() {
        rule.setContent {
            LandscapeDisplay(CalculatorUiState(input = "3*4", preview = "12"))
        }
        rule.onNodeWithText("3×4").assertIsDisplayed()
        rule.onNodeWithText("12").assertIsDisplayed()
    }

    @Test fun landscapeDisplayEmptyShowsZero() {
        rule.setContent { LandscapeDisplay(CalculatorUiState()) }
        rule.onNodeWithText("0").assertIsDisplayed()
    }

    @Test fun landscapeKeypadDispatchesKeys() {
        val appended = mutableListOf<String>()
        val actions = object : CalculatorActions {
            override fun append(text: String) { appended += text }
        }
        rule.setContent {
            LandscapeKeypad(
                rows = buildCalculatorRows(AngleMode.RADIANS),
                actions = actions,
                second = false,
                onToggleSecond = {},
            )
        }
        rule.onNodeWithText("7").assertIsDisplayed()
        rule.onNodeWithText("7").performClick()
        assertEquals(listOf("7"), appended)
        rule.onNodeWithText("sin").assertIsDisplayed()
    }

    @Test fun datePickerModalShowsOkAndCancel() {
        var dismissed = false
        rule.setContent {
            DatePickerModal(onDismiss = { dismissed = true }, onConfirm = {})
        }
        rule.onNodeWithText("OK").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        assertTrue(dismissed)
    }

    @Test fun timePickerModalConfirmReportsTime() {
        var confirmed: Pair<Int, Int>? = null
        rule.setContent {
            TimePickerModal(onDismiss = {}, onConfirm = { h, m -> confirmed = h to m })
        }
        rule.onNodeWithText("Select time").assertIsDisplayed()
        rule.onNodeWithText("OK").performClick()
        assertTrue(confirmed != null)
    }

    @Test fun unitPickerAbsoluteTimeTabOpensDatePicker() {
        rule.setContent {
            UnitPickerSheet(
                state = CalculatorUiState(
                    unitCategories = com.vayunmathur.calculator.util.UnitRegistry.categories,
                ),
                actions = CalculatorActions.Noop,
                onDismiss = {},
            )
        }
        rule.onNodeWithText("Absolute time").performClick()
        rule.onNodeWithText("Date").assertIsDisplayed()
        rule.onNodeWithText("Date").performClick()
        // The material date picker dialog opens over the sheet.
        rule.onNodeWithText("Cancel").assertIsDisplayed()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1000dp-h400dp-land")
class LandscapeScreenTest {

    @get:Rule val rule = createComposeRule()

    @Test fun calculatorScreenUsesLandscapeLayoutOnShortWindow() {
        rule.setContent {
            CalculatorScreen(
                state = CalculatorUiState(input = "2+2", preview = "4"),
                actions = CalculatorActions.Noop,
            )
        }
        rule.onNodeWithText("2+2").assertIsDisplayed()
        rule.onNodeWithText("7").assertIsDisplayed()
        rule.onNodeWithText("sin").assertIsDisplayed()
    }
}
