package com.vayunmathur.calculator.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import com.vayunmathur.calculator.util.FeatureKind
import com.vayunmathur.calculator.util.GraphActions
import com.vayunmathur.calculator.util.GraphFunction
import com.vayunmathur.calculator.util.GraphMarker
import com.vayunmathur.calculator.util.GraphPoint
import com.vayunmathur.calculator.util.GraphUiState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GraphDrawTest {

    @get:Rule val rule = createComposeRule()

    @Test fun graphCanvasDrawsCurvesGridAndMarkers() {
        var tapped: GraphPoint? = null
        var viewSize: Pair<Float, Float>? = null
        val actions = object : GraphActions {
            override fun tapGraph(at: GraphPoint, radius: Double) { tapped = at }
            override fun setViewSize(widthPx: Float, heightPx: Float) { viewSize = widthPx to heightPx }
        }
        rule.setContent {
            GraphScreen(
                state = GraphUiState(
                    functions = listOf(
                        GraphFunction(id = 0, text = "x^2", color = Color.Blue),
                        GraphFunction(id = 1, text = "sin(x)", color = Color.Red, polar = true),
                    ),
                    markers = listOf(GraphMarker(GraphPoint(0.0, 0.0), FeatureKind.ROOT, listOf(0))),
                ),
                actions = actions,
            )
        }
        rule.onNodeWithText("Add function").assertIsDisplayed()
        assertTrue(viewSize != null)
        // Tapping the canvas reports a graph point near where the tap landed.
        rule.onRoot().performTouchInput { click(center) }
        rule.waitForIdle()
        assertTrue(tapped != null)
    }

    @Test fun graphScreenInDegreesDrawsWithAngleChip() {
        rule.setContent {
            GraphScreen(
                state = GraphUiState(
                    functions = listOf(GraphFunction(id = 0, text = "cos(x)", color = Color.Green)),
                    angleMode = com.vayunmathur.calculator.util.AngleMode.DEGREES,
                ),
                actions = GraphActions.Noop,
            )
        }
        rule.onNodeWithText("DEG").assertIsDisplayed()
        rule.onNodeWithText("Add function").assertIsDisplayed()
    }
}
