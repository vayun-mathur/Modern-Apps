package com.vayunmathur.calculator.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.calculator.util.AngleMode
import com.vayunmathur.calculator.util.CalculatorActions
import com.vayunmathur.calculator.util.CalculatorUiState
import com.vayunmathur.calculator.util.HistoryEntry
import com.vayunmathur.calculator.util.UnitRegistry
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class KeypadDirectComposeTest {

    @get:Rule val rule = createComposeRule()

    @Test fun displayColumnShowsHistoryUnitsAndHandlesTaps() {
        var used: HistoryEntry? = null
        var selectedUnit: String? = null
        val lengthUnits = UnitRegistry.unitsFor(com.vayunmathur.calculator.util.Dimension.LENGTH)
        val actions = object : CalculatorActions {
            override fun useHistory(entry: HistoryEntry) { used = entry }
            override fun selectOutputUnit(token: String) { selectedUnit = token }
        }
        rule.setContent {
            DisplayColumn(
                state = CalculatorUiState(
                    input = "2km",
                    preview = "2000 m",
                    memory = 5.0,
                    history = listOf(HistoryEntry("2+3", "5")),
                    unitOptions = lengthUnits,
                    selectedUnit = lengthUnits.firstOrNull { it.token == "m" },
                ),
                actions = actions,
            )
        }
        rule.onNodeWithText("2km").assertIsDisplayed()
        rule.onNodeWithText("2000 m").assertIsDisplayed()
        rule.onNodeWithContentDescription("2+3 equals 5").performClick()
        assertEquals(HistoryEntry("2+3", "5"), used)
        // Unit chips: centimetre chip selects cm.
        rule.onNodeWithText("cm").performClick()
        assertEquals("cm", selectedUnit)
    }

    @Test fun keypadColumnRendersAllRowsAndSecondToggle() {
        val appended = mutableListOf<String>()
        var toggles = 0
        val actions = object : CalculatorActions {
            override fun append(text: String) { appended += text }
        }
        rule.setContent {
            KeypadColumn(
                rows = buildCalculatorRows(AngleMode.RADIANS),
                actions = actions,
                second = true,
                onToggleSecond = { toggles++ },
            )
        }
        rule.onNodeWithText("sin⁻¹").assertIsDisplayed()
        rule.onNodeWithText("sin⁻¹").performClick()
        assertEquals(listOf("asin("), appended)
        rule.onNodeWithText("2nd").performClick()
        assertEquals(1, toggles)
    }

    @Test fun pageBindingsRenderWithViewModel() {
        val vm = com.vayunmathur.calculator.util.CalculatorViewModel(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
        )
        rule.setContent { CalculatorPage(vm) }
        rule.onNodeWithText("7").assertIsDisplayed()
    }
}
