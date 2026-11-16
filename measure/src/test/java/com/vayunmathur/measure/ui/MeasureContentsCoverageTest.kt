package com.vayunmathur.measure.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.measure.R
import com.vayunmathur.measure.data.model.Anchor
import com.vayunmathur.measure.data.model.TrackingQuality
import com.vayunmathur.measure.data.model.UnitSystem
import com.vayunmathur.measure.domain.HeldOrientation
import com.vayunmathur.measure.platform.ArMeasureActions
import com.vayunmathur.measure.platform.ArMeasureUiState
import com.vayunmathur.measure.platform.CompassActions
import com.vayunmathur.measure.platform.CompassUiState
import com.vayunmathur.measure.platform.LevelActions
import com.vayunmathur.measure.platform.LevelUiState
import com.vayunmathur.measure.platform.RulerUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Compass/level/ruler/AR contents in states the first coverage pass did not reach. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeasureContentsCoverageTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg a: Any): String = context.getString(id, *a)

    @Test
    fun compassHeldBearingTiltAndRelease() {
        var cleared = false
        var north: Boolean? = null
        val actions = object : CompassActions {
            override fun clearHeldBearing() { cleared = true }
            override fun setUseTrueNorth(v: Boolean) { north = v }
        }
        composeRule.setContent {
            CompassContent(
                CompassUiState(
                    azimuthTrueDeg = 45.0, azimuthMagDeg = 40.0, declinationDeg = -3.5,
                    useTrueNorth = true, hasLocation = true, accuracy = 1,
                    heldBearingDeg = 120.0, tiltWarning = true,
                ),
                actions,
            )
        }
        composeRule.onNodeWithText(str(R.string.compass_tilt_warning)).assertIsDisplayed()
        assertEquals(false, cleared)
        assertEquals(null, north)
    }

    @Test
    fun compassReleaseAndNorthToggleClicksFire() {
        var cleared = false
        var north: Boolean? = null
        val actions = object : CompassActions {
            override fun clearHeldBearing() { cleared = true }
            override fun setUseTrueNorth(v: Boolean) { north = v }
        }
        composeRule.setContent {
            CompassContent(
                CompassUiState(
                    azimuthTrueDeg = 45.0, azimuthMagDeg = 40.0,
                    useTrueNorth = true, hasLocation = true, accuracy = 3,
                    heldBearingDeg = 120.0,
                ),
                actions,
            )
        }
        composeRule.onNodeWithText(str(R.string.compass_release_bearing)).performClick()
        assertTrue(cleared)
        composeRule.onNodeWithText(str(R.string.compass_show_magnetic)).performClick()
        assertEquals(false, north)
    }

    @Test
    fun compassMagneticModeShowsTrueNorthButtonDisabledWithoutLocation() {
        composeRule.setContent {
            CompassContent(
                CompassUiState(azimuthMagDeg = 270.0, useTrueNorth = false, hasLocation = false, accuracy = 3),
                CompassActions.Noop,
            )
        }
        composeRule.onNodeWithText("270° W").assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.compass_magnetic_north)).assertIsDisplayed()
    }

    @Test
    fun compassSettingsButtonFires() {
        var opened = false
        composeRule.setContent {
            CompassContent(CompassUiState(), CompassActions.Noop, onOpenSettings = { opened = true })
        }
        composeRule.onNodeWithContentDescription("Settings").performClick()
        assertTrue(opened)
    }

    @Test
    fun levelPortraitEdgeAngle() = levelEdge(HeldOrientation.Portrait, 12.5)

    @Test
    fun levelLandscapeRightEdgeAngle() = levelEdge(HeldOrientation.LandscapeRight, -30.0)

    @Test
    fun levelUpsideDownEdgeAngle() = levelEdge(HeldOrientation.PortraitUpsideDown, 179.0)

    @Test
    fun levelLandscapeLeftEdgeAngle() = levelEdge(HeldOrientation.LandscapeLeft, 45.25)

    private fun levelEdge(orientation: HeldOrientation, angle: Double) {
        composeRule.setContent {
            LevelContent(
                LevelUiState(edgeAngleDeg = angle, isFlat = false, orientation = orientation, uiRotationDeg = 90f),
                LevelActions.Noop,
            )
        }
        composeRule.onNodeWithText(com.vayunmathur.measure.domain.Units.formatAngle(angle)).assertIsDisplayed()
    }

    @Test
    fun levelFlatOutOfToleranceCalibrates() {
        var calibrated = false
        val actions = object : LevelActions {
            override fun calibrateZero() { calibrated = true }
        }
        composeRule.setContent {
            LevelContent(LevelUiState(pitchDeg = 8.0, rollDeg = -4.0, isFlat = true), actions)
        }
        composeRule.onNodeWithText("8.0° / -4.0°").assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.level_set_zero)).performClick()
        assertTrue(calibrated)
    }

    @Test
    fun rulerImperialShowsInchesAndHint() {
        composeRule.setContent {
            RulerContent(RulerUiState(pixelsPerMm = 6f, unitSystem = UnitSystem.Imperial))
        }
        composeRule.onNodeWithText(str(R.string.ruler_hint)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.tool_ruler)).assertIsDisplayed()
    }

    @Test
    fun rulerSettingsButtonFires() {
        var opened = false
        composeRule.setContent {
            RulerContent(RulerUiState(pixelsPerMm = 10f), onOpenSettings = { opened = true })
        }
        composeRule.onNodeWithContentDescription("Settings").performClick()
        assertTrue(opened)
    }

    // MeasureNative.available is true under Robolectric (loadLibrary is
    // shadowed), so the full tracking UI renders; without camera permission
    // the unavailable notice is the reachable guard.
    @Test
    fun arInitialisingShowsCoach() {
        composeRule.setContent {
            ArMeasureContent(
                ArMeasureUiState(quality = TrackingQuality.Initialising, cameraPermissionGranted = true),
                ArMeasureActions.Noop,
            )
        }
        composeRule.onNodeWithText(str(R.string.ar_tracking_initialising)).assertIsDisplayed()
    }

    @Test
    fun arGoodWithAnchorsShowsReadoutAndButtonsFire() {
        var undone = false
        var clearedAll = false
        var closed = false
        val actions = object : ArMeasureActions {
            override fun undoAnchor() { undone = true }
            override fun clearAnchors() { clearedAll = true }
            override fun closePolygon() { closed = true }
        }
        val anchors = listOf(Anchor(1, 0.0, 0.0, 0.0), Anchor(2, 3.0, 4.0, 0.0), Anchor(3, 3.0, 0.0, 0.0))
        composeRule.setContent {
            ArMeasureContent(
                ArMeasureUiState(
                    quality = TrackingQuality.Good, anchors = anchors,
                    distanceM = 5.0, areaM2 = 6.0, perimeterM = 12.0,
                    polygonClosed = false, hasPlane = true, cameraPermissionGranted = true,
                ),
                actions,
            )
        }
        composeRule.onNodeWithText(str(R.string.ar_tracking_ready)).assertIsDisplayed()
        composeRule.onNodeWithText("5.000 m").assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.ar_close_shape)).performClick()
        assertTrue(closed)
        composeRule.onNodeWithText("Clear").performClick()
        assertTrue(clearedAll)
        composeRule.onNodeWithContentDescription("Undo").performClick()
        assertTrue(undone)
    }

    @Test
    fun arLostBanner() {
        composeRule.setContent {
            ArMeasureContent(
                ArMeasureUiState(quality = TrackingQuality.Lost, cameraPermissionGranted = true),
                ArMeasureActions.Noop,
            )
        }
        composeRule.onNodeWithText(str(R.string.ar_tracking_lost)).assertIsDisplayed()
    }

    @Test
    fun arLimitedBanner() {
        composeRule.setContent {
            ArMeasureContent(
                ArMeasureUiState(quality = TrackingQuality.Limited, cameraPermissionGranted = true),
                ArMeasureActions.Noop,
            )
        }
        composeRule.onNodeWithText(str(R.string.ar_tracking_limited)).assertIsDisplayed()
    }

    @Test
    fun arGoodWithoutPlaneBanner() {
        composeRule.setContent {
            ArMeasureContent(
                ArMeasureUiState(quality = TrackingQuality.Good, hasPlane = false, cameraPermissionGranted = true),
                ArMeasureActions.Noop,
            )
        }
        composeRule.onNodeWithText(str(R.string.ar_tracking_ready_no_surface)).assertIsDisplayed()
    }

    @Test
    fun savedImperialMeasurementsDeleteFires() {
        var deleted: Long? = null
        val actions = object : com.vayunmathur.measure.platform.SavedActions {
            override fun delete(id: Long) { deleted = id }
        }
        val saved = com.vayunmathur.measure.data.model.SavedMeasurement(
            9L, "Shelf depth",
            com.vayunmathur.measure.data.model.MeasurementKind.Area, 2.5, 2000L,
        )
        composeRule.setContent {
            SavedMeasurementsContent(
                com.vayunmathur.measure.platform.SavedUiState(
                    measurements = listOf(saved), unitSystem = UnitSystem.Imperial,
                ),
                actions,
            )
        }
        composeRule.onNodeWithText("Shelf depth").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Delete").performClick()
        assertEquals(9L, deleted)
    }

    @Test
    fun savedAngleAndDistanceFormatting() {
        val angle = com.vayunmathur.measure.data.model.SavedMeasurement(
            3L, "Corner angle",
            com.vayunmathur.measure.data.model.MeasurementKind.Angle, 45.0, 3000L,
        )
        val dist = com.vayunmathur.measure.data.model.SavedMeasurement(
            4L, "Door width",
            com.vayunmathur.measure.data.model.MeasurementKind.Distance, 0.9, 3001L,
        )
        composeRule.setContent {
            SavedMeasurementsContent(
                com.vayunmathur.measure.platform.SavedUiState(measurements = listOf(angle, dist)),
                com.vayunmathur.measure.platform.SavedActions.Noop,
            )
        }
        composeRule.onNodeWithText("Corner angle").assertIsDisplayed()
        composeRule.onNodeWithText("45.0°").assertIsDisplayed()
        composeRule.onNodeWithText("Door width").assertIsDisplayed()
        composeRule.onNodeWithText("900 mm").assertIsDisplayed()
    }

    @Test
    fun savedEmptyShowsPrompt() {
        composeRule.setContent {
            SavedMeasurementsContent(
                com.vayunmathur.measure.platform.SavedUiState(),
                com.vayunmathur.measure.platform.SavedActions.Noop,
            )
        }
        composeRule.onNodeWithText(str(R.string.saved_empty_title)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.saved_empty_body)).assertIsDisplayed()
    }

    @Test
    fun diagnosticsWithAllCountsAndResetFires() {
        var reset = false
        val actions = object : com.vayunmathur.measure.platform.DiagnosticsActions {
            override fun resetTracking() { reset = true }
        }
        composeRule.setContent {
            DiagnosticsContent(
                com.vayunmathur.measure.platform.DiagnosticsUiState(
                    featureCount = 120, trackedCount = 80, imuRateHz = 200.5,
                    frameRateHz = 29.5, timestampSkewMs = 3.25,
                    nativeEngineAvailable = false, focalPx = 1400.0,
                    principalPointPx = 320.0 to 240.0, landmarkCount = 42,
                    scaleConfidence = 0.75,
                ),
                actions,
            )
        }
        composeRule.onNodeWithText(str(R.string.diagnostics_title)).assertIsDisplayed()
        // The reset row is at the bottom of a lazy list: scroll it into
        // composition before clicking.
        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        composeRule.onNode(
            androidx.compose.ui.test.hasText(str(R.string.diagnostics_reset_tracking)) and
                androidx.compose.ui.test.hasClickAction(),
        ).performClick()
        assertTrue(reset)
    }

    @Test
    fun settingsImperialFractionalOffTogglesFire() {
        var units: UnitSystem? = null
        var fractional: Boolean? = null
        var haptics: Boolean? = null
        val actions = object : com.vayunmathur.measure.platform.SettingsActions {
            override fun setUnitSystem(v: UnitSystem) { units = v }
            override fun setUseFractionalInches(v: Boolean) { fractional = v }
            override fun setHapticsEnabled(v: Boolean) { haptics = v }
        }
        composeRule.setContent {
            SettingsContent(
                com.vayunmathur.measure.platform.SettingsUiState(
                    unitSystem = UnitSystem.Imperial, useFractionalInches = false,
                    useTrueNorth = false, levelCalibrated = true,
                    showDiagnostics = true, hapticsEnabled = false, keepScreenOn = false,
                ),
                actions,
            )
        }
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        // Flip the unit switch: Metric state -> Imperial.
        composeRule.onNode(
            androidx.compose.ui.test.hasText(str(R.string.settings_imperial_units)) and
                androidx.compose.ui.test.hasClickAction(),
        ).performClick()
        assertEquals(UnitSystem.Metric, units)
        // Haptics switch is in the general section; scroll then flip it on.
        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        composeRule.onNode(
            androidx.compose.ui.test.hasText(str(R.string.settings_haptic_feedback)) and
                androidx.compose.ui.test.hasClickAction(),
        ).performClick()
        assertEquals(true, haptics)
        assertEquals(null, fractional)
    }

    @Test
    fun nativeBridgeGuardsRejectZeroHandle() {
        // Zero is never a live session: every bridge entry must refuse it
        // without touching the native engine.
        assertEquals(null, com.vayunmathur.measure.domain.MeasureNative.rayToWorld(0L, 10f, 20f))
        assertEquals(null, com.vayunmathur.measure.domain.MeasureNative.getState(0L))
        assertEquals(
            0,
            com.vayunmathur.measure.domain.MeasureNative
                .projectPoints(0L, doubleArrayOf(1.0, 2.0, 3.0), 100.0, 100.0).size,
        )
        assertEquals(
            0,
            com.vayunmathur.measure.domain.MeasureNative
                .projectPoints(1L, DoubleArray(0), 100.0, 100.0).size,
        )
    }

    @Test
    fun contractNoopActionsAreCallable() {
        // Every actions interface ships a Noop for previews; calling each
        // default must be a harmless no-op (a preview crash would ship in
        // the store screenshots).
        CompassActions.Noop.setUseTrueNorth(true)
        CompassActions.Noop.holdBearing()
        CompassActions.Noop.clearHeldBearing()
        LevelActions.Noop.calibrateZero()
        LevelActions.Noop.clearCalibration()
        ArMeasureActions.Noop.placeAnchor(0.5f, 0.5f)
        ArMeasureActions.Noop.undoAnchor()
        ArMeasureActions.Noop.clearAnchors()
        ArMeasureActions.Noop.closePolygon()
        ArMeasureActions.Noop.saveCurrentMeasurement("label")
        ArMeasureActions.Noop.resetTracking()
        com.vayunmathur.measure.platform.SavedActions.Noop.delete(1L)
        com.vayunmathur.measure.platform.SavedActions.Noop.rename(1L, "x")
        com.vayunmathur.measure.platform.SavedActions.Noop.exportAll()
        com.vayunmathur.measure.platform.SettingsActions.Noop.setUnitSystem(UnitSystem.Imperial)
        com.vayunmathur.measure.platform.SettingsActions.Noop.setUseFractionalInches(true)
        com.vayunmathur.measure.platform.SettingsActions.Noop.setUseTrueNorth(true)
        com.vayunmathur.measure.platform.SettingsActions.Noop.setShowDiagnostics(true)
        com.vayunmathur.measure.platform.SettingsActions.Noop.setHapticsEnabled(true)
        com.vayunmathur.measure.platform.SettingsActions.Noop.setKeepScreenOn(true)
        com.vayunmathur.measure.platform.SettingsActions.Noop.clearLevelCalibration()
        com.vayunmathur.measure.platform.DiagnosticsActions.Noop.resetTracking()
    }

    @Test
    fun arUnavailableWithoutCameraPermission() {
        composeRule.setContent {
            ArMeasureContent(
                ArMeasureUiState(quality = TrackingQuality.Good, cameraPermissionGranted = false),
                ArMeasureActions.Noop,
            )
        }
        composeRule.onNodeWithText(str(R.string.ar_unavailable_camera)).assertIsDisplayed()
    }
}
