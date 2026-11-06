package com.vayunmathur.maps.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * The map-layers toggle sheet.
 * Slice-2 coverage for maps (basename L, outside the slice-1 dirs).
 */
@RunWith(AndroidJUnit4::class)
class LayersSheetSlice2Test {

    @get:Rule
    val rule = createComposeRule()

    private fun sheet(
        traffic: Boolean = false,
        onTraffic: (Boolean) -> Unit = {},
        satellite: Boolean = false,
        onSatellite: (Boolean) -> Unit = {},
        safety: Boolean = false,
        onSafety: (Boolean) -> Unit = {},
        transit: Boolean = false,
        onTransit: (Boolean) -> Unit = {},
    ) {
        rule.setContent {
            LayersSheet(
                onDismiss = {},
                trafficEnabled = traffic,
                onTrafficChange = onTraffic,
                satelliteEnabled = satellite,
                onSatelliteChange = onSatellite,
                safetyEnabled = safety,
                onSafetyChange = onSafety,
                transitEnabled = transit,
                onTransitChange = onTransit,
            )
        }
    }

    @Test
    fun `no tile source is hosted so the satellite row is hidden`() {
        assertFalse(SatelliteSource.available, "precondition: no satellite source")
        sheet()
        rule.onNodeWithText("Satellite").assertDoesNotExist()
    }

    @Test
    fun `traffic and transit rows show`() {
        sheet()
        rule.onNodeWithText("Traffic").assertIsDisplayed()
        rule.onNodeWithText("Transit").assertIsDisplayed()
    }

    @Test
    fun `toggling traffic reports the flip`() {
        var traffic: Boolean? = null
        sheet(traffic = false, onTraffic = { traffic = it })
        rule.onNodeWithText("Traffic").performClick()
        assertEquals(true, traffic)
    }

    @Test
    fun `toggling transit reports the flip`() {
        var transit: Boolean? = null
        sheet(transit = true, onTransit = { transit = it })
        rule.onNodeWithText("Transit").performClick()
        assertEquals(false, transit)
    }

    @Test
    fun `safety is surfaced live when the v5 pack is available`() {
        assertTrue(SafetyLayersSource.available, "precondition: safety available")
        sheet()
        // The sheet column scrolls past the viewport: existence (not
        // on-screen display) is what the sheet guarantees here.
        assertTrue(
            rule.onAllNodesWithText("Safety layers", substring = true).fetchSemanticsNodes().isNotEmpty(),
            "safety row exists",
        )
    }
}
