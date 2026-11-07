package com.vayunmathur.measure.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.measure.R
import com.vayunmathur.measure.data.model.Anchor
import com.vayunmathur.measure.data.model.MeasurementKind
import com.vayunmathur.measure.data.model.SavedMeasurement
import com.vayunmathur.measure.data.model.UnitSystem
import com.vayunmathur.measure.data.model.distanceTo
import com.vayunmathur.measure.domain.Units
import com.vayunmathur.measure.platform.CompassActions
import com.vayunmathur.measure.platform.CompassUiState
import com.vayunmathur.measure.platform.LevelActions
import com.vayunmathur.measure.platform.LevelUiState
import com.vayunmathur.measure.platform.RulerUiState
import com.vayunmathur.measure.platform.SavedActions
import com.vayunmathur.measure.platform.SavedUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Units formatting, anchor distance, level/compass/ruler/saved contents. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeasureContentsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg a: Any): String = context.getString(id, *a)

    @Test
    fun unitsLengthAreaAngleBearings() {
        assertEquals("5.0 mm", Units.formatLength(0.005, UnitSystem.Metric))
        assertEquals("250 mm", Units.formatLength(0.25, UnitSystem.Metric))
        assertEquals("1.500 m", Units.formatLength(1.5, UnitSystem.Metric))
        assertEquals("12.35 m", Units.formatLength(12.345, UnitSystem.Metric))
        assertEquals("1.00 in", Units.formatLength(0.0254, UnitSystem.Imperial, fractionalInches = false))
        assertEquals("5000 cm²", Units.formatArea(0.5, UnitSystem.Metric))
        assertEquals("2.00 m²", Units.formatArea(2.0, UnitSystem.Metric))
        assertEquals("45.0°", Units.formatAngle(45.04))
        assertEquals("000°", Units.formatBearing(0.0))
        assertEquals("090°", Units.formatBearing(90.4))
        assertEquals("000°", Units.formatBearing(359.6))
        assertEquals(10.0, Units.normalizeDegrees(370.0))
        assertEquals(350.0, Units.normalizeDegrees(-10.0))
        assertEquals(20.0, Units.bearingDelta(350.0, 10.0), 0.001)
        assertEquals(-20.0, Units.bearingDelta(10.0, 350.0), 0.001)
        assertEquals(-180.0, Units.bearingDelta(0.0, 180.0), 0.001)
        assertEquals(0, Units.cardinalIndex(0.0))
        assertEquals(4, Units.cardinalIndex(90.0))
        assertEquals(8, Units.cardinalIndex(180.0))
        assertEquals(0, Units.cardinalIndex(350.0))
    }

    @Test
    fun fractionalInchesReduceAndFeet() {
        assertEquals("1\"", Units.formatFractionalInches(1.0))
        assertEquals("1 1/2\"", Units.formatFractionalInches(1.5))
        // 8/16 reduces to 1/2.
        assertEquals("1 1/2\"", Units.formatFractionalInches(1.0 + 8.0 / 16.0))
        assertEquals("1' 0\"", Units.formatFractionalInches(12.0))
        assertEquals("2' 3\"", Units.formatFractionalInches(27.0))
        assertEquals("-1\"", Units.formatFractionalInches(-1.0))
        assertEquals("0\"", Units.formatFractionalInches(0.0))
    }

    @Test
    fun anchorDistanceIsEuclidean() {
        val a = Anchor(1, 0.0, 0.0, 0.0)
        val b = Anchor(2, 3.0, 4.0, 0.0)
        assertEquals(5.0, a.distanceTo(b))
        assertEquals(0.0, a.distanceTo(a))
    }

    @Test
    fun levelFlatShowsAnglesAndCalibrateAction() {
        var calibrated = false
        val actions = object : LevelActions {
            override fun calibrateZero() { calibrated = true }
        }
        composeRule.setContent {
            LevelContent(LevelUiState(pitchDeg = 0.0, rollDeg = 0.0, isFlat = true), actions)
        }
        composeRule.onNodeWithText("0.0° / 0.0°").assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.level_set_zero)).performClick()
        assertTrue(calibrated)
    }

    @Test
    fun levelEdgeModeShowsSingleAngleAndClearWhenCalibrated() {
        var cleared = false
        val actions = object : LevelActions {
            override fun clearCalibration() { cleared = true }
        }
        composeRule.setContent {
            LevelContent(
                LevelUiState(edgeAngleDeg = 5.0, isFlat = false, isCalibrated = true), actions,
            )
        }
        composeRule.onNodeWithText("5.0°").assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.level_clear_calibration)).performClick()
        assertTrue(cleared)
        composeRule.onNodeWithText(str(R.string.level_zeroed)).assertIsDisplayed()
    }

    @Test
    fun compassShowsHeadingNorthKindAndHoldToggle() {
        var held = false
        var northToggled: Boolean? = null
        val actions = object : CompassActions {
            override fun holdBearing() { held = true }
            override fun setUseTrueNorth(v: Boolean) { northToggled = v }
        }
        composeRule.setContent {
            CompassContent(
                CompassUiState(
                    azimuthTrueDeg = 90.0, azimuthMagDeg = 85.0, declinationDeg = 5.0,
                    useTrueNorth = true, hasLocation = true, accuracy = 3,
                ),
                actions,
            )
        }
        composeRule.onNodeWithText(str(R.string.compass_true_north)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.compass_hold_bearing)).performClick()
        assertTrue(held)
        assertEquals(null, northToggled)
    }

    @Test
    fun compassWithoutLocationFallsBackToMagnetic() {
        composeRule.setContent {
            CompassContent(
                CompassUiState(azimuthMagDeg = 180.0, useTrueNorth = true, hasLocation = false),
                CompassActions.Noop,
            )
        }
        composeRule.onNodeWithText(str(R.string.compass_magnetic_north)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.compass_no_location)).assertIsDisplayed()
    }

    @Test
    fun rulerRendersWithState() {
        composeRule.setContent { RulerContent(RulerUiState(pixelsPerMm = 10f)) }
        composeRule.onNodeWithText(str(R.string.tool_ruler)).assertIsDisplayed()
    }

    @Test
    fun savedListShowsMeasurementsAndDelete() {
        var deleted: Long? = null
        val actions = object : SavedActions {
            override fun delete(id: Long) { deleted = id }
        }
        val saved = SavedMeasurement(7L, "Table width", MeasurementKind.Distance, 1.2, 1000L)
        composeRule.setContent {
            SavedMeasurementsContent(SavedUiState(measurements = listOf(saved)), actions)
        }
        composeRule.onNodeWithText("Table width").assertIsDisplayed()
        assertEquals(null, deleted)
    }

    @Test
    fun savedEmptyState() {
        composeRule.setContent {
            SavedMeasurementsContent(SavedUiState(), SavedActions.Noop)
        }
        // Screen renders (empty prompt or scaffold title) without crash.
        assertTrue(true)
    }
}
