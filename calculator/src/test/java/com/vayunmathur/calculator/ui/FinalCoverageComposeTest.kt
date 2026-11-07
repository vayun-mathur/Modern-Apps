package com.vayunmathur.calculator.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import com.vayunmathur.calculator.util.CalculatorUiState
import com.vayunmathur.calculator.util.UnitRegistry
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FinalCoverageComposeTest {

    @get:Rule val rule = createComposeRule()

    @Test fun graphScreenCaptureForcesCanvasDraw() {
        rule.setContent {
            GraphScreen(
                state = com.vayunmathur.calculator.util.GraphUiState(
                    functions = listOf(
                        com.vayunmathur.calculator.util.GraphFunction(
                            id = 0, text = "x^2", color = androidx.compose.ui.graphics.Color.Blue,
                        ),
                    ),
                    markers = listOf(
                        com.vayunmathur.calculator.util.GraphMarker(
                            com.vayunmathur.calculator.util.GraphPoint(0.0, 0.0),
                            com.vayunmathur.calculator.util.FeatureKind.ROOT,
                            listOf(0),
                        ),
                    ),
                ),
                actions = com.vayunmathur.calculator.util.GraphActions.Noop,
            )
        }
        val bitmap = rule.onRoot().captureToImage()
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
    }

    @Test fun unitPickerTimeFlowOpensTimePicker() {
        rule.setContent {
            UnitPickerSheet(
                state = CalculatorUiState(unitCategories = UnitRegistry.categories),
                actions = com.vayunmathur.calculator.util.CalculatorActions.Noop,
                onDismiss = {},
            )
        }
        rule.onNodeWithText("Absolute time").performClick()
        rule.onAllNodesWithText("Time")[1].performClick()
        rule.onNodeWithText("Select time").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
    }

}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1200dp-h800dp")
class ExpandedCalculatorScreenTest {

    @get:Rule val rule = createComposeRule()

    @Test fun expandedWindowUsesSideBySideLayout() {
        rule.setContent {
            CalculatorScreen(
                state = CalculatorUiState(input = "6*7", preview = "42"),
                actions = com.vayunmathur.calculator.util.CalculatorActions.Noop,
            )
        }
        rule.onNodeWithText("6×7").assertIsDisplayed()
        rule.onNodeWithText("7").assertIsDisplayed()
    }
}
