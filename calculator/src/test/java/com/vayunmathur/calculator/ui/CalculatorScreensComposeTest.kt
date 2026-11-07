package com.vayunmathur.calculator.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.vayunmathur.calculator.util.AngleMode
import com.vayunmathur.calculator.util.CalculatorActions
import com.vayunmathur.calculator.util.CalculatorUiState
import com.vayunmathur.calculator.util.GraphActions
import com.vayunmathur.calculator.util.GraphFunction
import com.vayunmathur.calculator.util.GraphMarker
import com.vayunmathur.calculator.util.FeatureKind
import com.vayunmathur.calculator.util.GraphPoint
import com.vayunmathur.calculator.util.GraphUiState
import com.vayunmathur.calculator.util.HistoryEntry
import com.vayunmathur.calculator.util.UnitConverterActions
import com.vayunmathur.calculator.util.UnitConverterUiState
import com.vayunmathur.calculator.util.UnitRegistry
import androidx.compose.ui.graphics.Color
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class CalculatorScreensComposeTest {

    @get:Rule val rule = createComposeRule()

    // ---- Keypad rows (pure) ----

    @Test fun keypadRowsShapeAndAngleLabel() {
        val rad = buildCalculatorRows(AngleMode.RADIANS)
        assertEquals(8, rad.size)
        assertTrue(rad.all { it.size == 5 })
        assertEquals("RAD", rad[3][4].label)
        assertEquals("DEG", buildCalculatorRows(AngleMode.DEGREES)[3][4].label)
        assertEquals("=", rad[7][4].label)
    }

    @Test fun keyPressesDispatchToActions() {
        val appended = mutableListOf<String>()
        var evaluated = false
        var cleared = false
        val actions = object : CalculatorActions {
            override fun append(text: String) { appended += text }
            override fun evaluate() { evaluated = true }
            override fun clear() { cleared = true }
        }
        val rows = buildCalculatorRows(AngleMode.RADIANS)
        rows[4][0].onPress(actions) // 7
        rows[7][4].onPress(actions) // =
        rows[0][4].onPress(actions) // AC
        assertEquals(listOf("7"), appended)
        assertTrue(evaluated)
        assertTrue(cleared)
        // Second-function presses.
        assertEquals("asin(", run {
            rows[1][0].secondPress!!.invoke(actions); appended.last()
        })
    }

    // ---- Calculator screen ----

    private class RecordingActions : CalculatorActions {
        val appended = mutableListOf<String>()
        var evaluated = 0
        var usedHistory: HistoryEntry? = null
        override fun append(text: String) { appended += text }
        override fun evaluate() { evaluated++ }
        override fun useHistory(entry: HistoryEntry) { usedHistory = entry }
    }

    @Test fun calculatorScreenShowsInputPreviewAndKeys() {
        val actions = RecordingActions()
        rule.setContent {
            CalculatorScreen(
                state = CalculatorUiState(input = "2+3", preview = "5"),
                actions = actions,
            )
        }
        rule.onNodeWithText("2+3").assertIsDisplayed()
        rule.onAllNodesWithText("5")[0].assertIsDisplayed()
        rule.onNodeWithText("7").assertIsDisplayed()
        rule.onNodeWithText("7").performClick()
        assertEquals(listOf("7"), actions.appended)
        rule.onNodeWithText("=").performClick()
        assertEquals(1, actions.evaluated)
    }

    @Test fun calculatorScreenSecondKeySwapsLabels() {
        val actions = RecordingActions()
        rule.setContent {
            CalculatorScreen(state = CalculatorUiState(), actions = actions)
        }
        rule.onNodeWithText("2nd").performClick()
        rule.onNodeWithText("sin⁻¹").assertIsDisplayed()
        rule.onNodeWithText("sin⁻¹").performClick()
        assertEquals(listOf("asin("), actions.appended)
    }

    @Test fun calculatorScreenWithHistoryAndMemory() {
        val actions = RecordingActions()
        rule.setContent {
            CalculatorScreen(
                state = CalculatorUiState(
                    input = "10",
                    preview = "10",
                    memory = 5.0,
                    history = listOf(HistoryEntry("2+3", "5")),
                ),
                actions = actions,
            )
        }
        rule.onAllNodesWithText("10")[0].assertIsDisplayed()
    }

    @Test fun calculatorScreenUnitPickerSeededOpen() {
        val actions = RecordingActions()
        rule.setContent {
            CalculatorScreen(
                state = CalculatorUiState(unitCategories = UnitRegistry.categories),
                actions = actions,
                initialShowUnitPicker = true,
            )
        }
        rule.onNodeWithText("Insert unit").assertIsDisplayed()
        rule.onNodeWithText("Length").assertIsDisplayed()
    }

    // ---- Unit picker sheet + date helpers ----

    @Test fun unitPickerChipAppendsToken() {
        val actions = RecordingActions()
        var dismissed = false
        rule.setContent {
            UnitPickerSheet(
                state = CalculatorUiState(unitCategories = UnitRegistry.categories),
                actions = actions,
                onDismiss = { dismissed = true },
            )
        }
        rule.onNodeWithText("Length").assertIsDisplayed()
        // First category (Length) units are shown; tap the metre chip by symbol.
        rule.onAllNodesWithText("m")[0].performClick()
        assertEquals(listOf("m"), actions.appended)
        assertTrue(dismissed)
    }

    @Test fun dateTimeHelpersConvertUtcMidnightToLocal() {
        // 2024-01-15T00:00:00Z
        val utcMidnight = 1705276800000L
        val local = localMidnightSeconds(utcMidnight)
        val zoned = java.time.Instant.ofEpochSecond(local).atZone(java.time.ZoneId.systemDefault())
        assertEquals(15, zoned.dayOfMonth)
        assertEquals(0, zoned.hour)
        val combined = combineDateTimeSeconds(utcMidnight, 13, 30)
        val combinedZoned = java.time.Instant.ofEpochSecond(combined).atZone(java.time.ZoneId.systemDefault())
        assertEquals(13, combinedZoned.hour)
        assertEquals(30, combinedZoned.minute)
        val today = java.time.Instant.ofEpochSecond(todayAtTimeSeconds(9, 15))
            .atZone(java.time.ZoneId.systemDefault())
        assertEquals(java.time.LocalDate.now(), today.toLocalDate())
        assertEquals(9, today.hour)
    }

    // ---- Graph screen ----

    @Test fun graphScreenShowsFunctionEditorAndAngleChip() {
        var added = 0
        val actions = object : GraphActions {
            override fun addFunction() { added++ }
        }
        rule.setContent {
            GraphScreen(
                state = GraphUiState(
                    functions = listOf(GraphFunction(id = 0, text = "x", color = Color.Blue)),
                ),
                actions = actions,
            )
        }
        rule.onNodeWithText("RAD").assertIsDisplayed()
        rule.onNodeWithText("Add function").assertIsDisplayed()
        rule.onNodeWithText("x").assertExists()
        rule.onNodeWithText("Add function").performClick()
        assertEquals(1, added)
    }

    @Test fun graphScreenEditsFunctionText() {
        var edited: Pair<Long, String>? = null
        val actions = object : GraphActions {
            override fun updateFunction(id: Long, text: String) { edited = id to text }
        }
        rule.setContent {
            GraphScreen(
                state = GraphUiState(
                    functions = listOf(GraphFunction(id = 3, text = "x", color = Color.Red)),
                ),
                actions = actions,
            )
        }
        rule.onNodeWithText("x").performTextReplacement("x^2")
        assertEquals(3L to "x^2", edited)
    }

    @Test fun graphScreenWithMarkerAndPolarFunction() {
        rule.setContent {
            GraphScreen(
                state = GraphUiState(
                    functions = listOf(
                        GraphFunction(id = 0, text = "x^2", color = Color.Blue),
                        GraphFunction(id = 1, text = "sin", color = Color.Red, polar = true, enabled = false),
                    ),
                    markers = listOf(GraphMarker(GraphPoint(0.0, 0.0), FeatureKind.ROOT, listOf(0))),
                    angleMode = AngleMode.DEGREES,
                ),
                actions = GraphActions.Noop,
            )
        }
        rule.onNodeWithText("DEG").assertIsDisplayed()
        rule.onNodeWithText("r=").assertIsDisplayed()
        rule.onNodeWithText("y=").assertIsDisplayed()
    }

    @Test fun graphFunctionRowInteractions() {
        var polarToggled = -1L
        var removed = -1L
        val actions = object : GraphActions {
            override fun togglePolar(id: Long) { polarToggled = id }
            override fun removeFunction(id: Long) { removed = id }
        }
        rule.setContent {
            GraphScreen(
                state = GraphUiState(
                    functions = listOf(GraphFunction(id = 5, text = "x", color = Color.Blue)),
                ),
                actions = actions,
            )
        }
        rule.onNodeWithText("y=").performClick()
        assertEquals(5L, polarToggled)
        assertEquals(-1L, removed)
    }

    // ---- Unit converter screen ----

    @Test fun converterScreenShowsResultAndCategories() {
        rule.setContent {
            UnitConverterScreen(
                state = UnitConverterUiState(
                    categories = UnitRegistry.categories,
                    selectedCategoryIndex = 0,
                    fromToken = UnitRegistry.categories[0].units[0].token,
                    toToken = UnitRegistry.categories[0].units[1].token,
                    inputText = "1",
                    outputText = "100",
                ),
                actions = UnitConverterActions.Noop,
            )
        }
        rule.onNodeWithText("Units").assertIsDisplayed()
        rule.onNodeWithText("Length").assertIsDisplayed()
        rule.onNodeWithText("Value").assertIsDisplayed()
    }

    @Test fun converterScreenEditsInput() {
        var input: String? = null
        val actions = object : UnitConverterActions {
            override fun setConverterInput(text: String) { input = text }
        }
        rule.setContent {
            UnitConverterScreen(
                state = UnitConverterUiState(
                    categories = UnitRegistry.categories,
                    selectedCategoryIndex = 0,
                    fromToken = UnitRegistry.categories[0].units[0].token,
                    toToken = UnitRegistry.categories[0].units[1].token,
                    inputText = "1",
                    outputText = "",
                ),
                actions = actions,
            )
        }
        rule.onNodeWithText("1").performTextReplacement("250")
        assertEquals("250", input)
    }

    @Test fun converterCurrencyLoadingAndErrorStates() {
        val currency = com.vayunmathur.calculator.util.UnitCategory("Currency", emptyList(), inEquations = false)
        rule.setContent {
            UnitConverterScreen(
                state = UnitConverterUiState(
                    categories = listOf(currency),
                    selectedCategoryIndex = 0,
                    isCurrencyCategory = true,
                    currencyLoading = true,
                ),
                actions = UnitConverterActions.Noop,
            )
        }
        rule.onNodeWithText("Currency").assertIsDisplayed()
    }

    @Test fun converterUnitDropdownSelectsUnit() {
        var fromToken: String? = null
        val actions = object : UnitConverterActions {
            override fun setFrom(token: String) { fromToken = token }
        }
        rule.setContent {
            UnitConverterScreen(
                state = UnitConverterUiState(
                    categories = UnitRegistry.categories,
                    selectedCategoryIndex = 0,
                    fromToken = "m",
                    toToken = "cm",
                    inputText = "1",
                    outputText = "100",
                ),
                actions = actions,
            )
        }
        // The From dropdown shows the selected symbol; opening it lists units.
        rule.onAllNodesWithText("m")[0].performClick()
        rule.onNodeWithText("km — Kilometre").assertIsDisplayed()
        rule.onNodeWithText("km — Kilometre").performClick()
        assertEquals("km", fromToken)
    }

    @Test fun converterCategoryDropdownSelects() {
        var selected = -1
        val actions = object : UnitConverterActions {
            override fun selectCategory(index: Int) { selected = index }
        }
        rule.setContent {
            UnitConverterScreen(
                state = UnitConverterUiState(
                    categories = UnitRegistry.categories,
                    selectedCategoryIndex = 0,
                    fromToken = UnitRegistry.categories[0].units[0].token,
                    toToken = UnitRegistry.categories[0].units[1].token,
                    inputText = "1",
                    outputText = "1",
                ),
                actions = actions,
            )
        }
        rule.onNodeWithText("Length").performClick()
        rule.onNodeWithText("Mass").assertIsDisplayed()
        rule.onNodeWithText("Mass").performClick()
        assertEquals(1, selected)
    }
}
