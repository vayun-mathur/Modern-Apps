package com.vayunmathur.measure.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.measure.R
import com.vayunmathur.measure.data.model.UnitSystem
import com.vayunmathur.measure.platform.CompassActions
import com.vayunmathur.measure.platform.CompassUiState
import com.vayunmathur.measure.platform.LevelActions
import com.vayunmathur.measure.platform.LevelUiState
import com.vayunmathur.measure.platform.RulerUiState
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Canvas-heavy contents with native graphics so the draw lambdas actually run. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeasureCanvasCoverageTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int): String = context.getString(id)

    @Test
    fun compassDialDrawsWithHeldBearing() {
        composeRule.setContent {
            CompassContent(
                CompassUiState(
                    azimuthTrueDeg = 123.0, azimuthMagDeg = 118.0, declinationDeg = 5.0,
                    useTrueNorth = true, hasLocation = true, accuracy = 3,
                    heldBearingDeg = 45.0,
                ),
                CompassActions.Noop,
            )
        }
        composeRule.onNodeWithText("123° ESE").assertIsDisplayed()
        composeRule.waitForIdle()
    }

    @Test
    fun levelBubblesDrawFlatAndEdge() {
        composeRule.setContent {
            LevelContent(
                LevelUiState(pitchDeg = 3.0, rollDeg = -2.0, isFlat = true),
                LevelActions.Noop,
            )
        }
        composeRule.onNodeWithText("3.0° / -2.0°").assertIsDisplayed()
        composeRule.waitForIdle()
    }

    @Test
    fun rulerScaleDrawsMetricAndImperial() {
        composeRule.setContent {
            RulerContent(RulerUiState(pixelsPerMm = 8f, unitSystem = UnitSystem.Metric))
        }
        composeRule.onNodeWithText(str(R.string.ruler_hint)).assertIsDisplayed()
        composeRule.waitForIdle()
    }
}
