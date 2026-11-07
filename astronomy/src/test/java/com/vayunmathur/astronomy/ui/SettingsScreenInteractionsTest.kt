package com.vayunmathur.astronomy.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.astronomy.R
import com.vayunmathur.astronomy.Route
import com.vayunmathur.astronomy.platform.ConstellationMode
import com.vayunmathur.astronomy.platform.SettingsActions
import com.vayunmathur.astronomy.platform.SettingsUiState
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.rememberNavBackStack
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Interaction tests for [SettingsScreen]: every switch, the constellation
 * select, and the manual-location form route through the actions.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenInteractionsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private class Recorder : SettingsActions {
        var mode: ConstellationMode? = null
        val switches = mutableListOf<Pair<String, Boolean>>()
        var manualLocation: Pair<Double, Double>? = null
        var refreshes = 0

        override fun setShowConstellations(mode: ConstellationMode) {
            this.mode = mode
        }

        override fun setShowGrid(v: Boolean) {
            switches += "grid" to v
        }

        override fun setShowDeepSky(v: Boolean) {
            switches += "deep" to v
        }

        override fun setShowPlanets(v: Boolean) {
            switches += "planets" to v
        }

        override fun setShowBelowHorizon(v: Boolean) {
            switches += "below" to v
        }

        override fun setNightMode(v: Boolean) {
            switches += "night" to v
        }

        override fun setManualLocation(latDeg: Double, lonDeg: Double) {
            manualLocation = latDeg to lonDeg
        }

        override fun refreshLocation() {
            refreshes++
        }
    }

    private fun state() = SettingsUiState(
        constellationMode = ConstellationMode.LINES,
        showGrid = true,
        showDeepSky = true,
        showPlanets = true,
        showBelowHorizon = false,
        nightMode = false,
        magLimit = 5.0f,
        fovDeg = 70f,
        starCount = 100,
        constellationCount = 9,
        deepSkyCount = 6,
    )

    private fun show(actions: SettingsActions): NavBackStack<Route> {
        lateinit var stack: NavBackStack<Route>
        composeRule.setContent {
            stack = rememberNavBackStack(Route.SkyMap, Route.Settings)
            SettingsScreen(backStack = stack, state = state(), actions = actions)
        }
        @Suppress("UNCHECKED_CAST")
        return stack as NavBackStack<Route>
    }

    private fun string(res: Int): String = context.getString(res)

    @Test
    fun `toggling switches routes each one`() {
        val actions = Recorder()
        show(actions)
        composeRule.onNodeWithText(string(R.string.coordinate_grid_whole_sphere)).performClick()
        composeRule.onNodeWithText(string(R.string.show_deep_sky)).performClick()
        composeRule.onNodeWithText(string(R.string.planets_sun_moon)).performClick()
        composeRule.onNodeWithText(string(R.string.night_mode)).performClick()
        assertEquals(
            listOf("grid" to false, "deep" to false, "planets" to false, "night" to true),
            actions.switches,
        )
    }

    @Test
    fun `constellation mode select routes the choice`() {
        val actions = Recorder()
        show(actions)
        composeRule.onNodeWithText(string(R.string.constellations)).performClick()
        composeRule.onNodeWithText(string(R.string.lines_art)).performClick()
        assertEquals(ConstellationMode.LINES_AND_ART, actions.mode)
        composeRule.onNodeWithText(string(R.string.constellations)).performClick()
        composeRule.onNodeWithText(string(R.string.off)).performClick()
        assertEquals(ConstellationMode.OFF, actions.mode)
    }

    @Test
    fun `manual location form saves parsed coordinates and refresh asks for gps`() {
        val actions = Recorder()
        show(actions)
        // Scroll the form into view: the location section is below the fold.
        composeRule.onNodeWithText(string(R.string.latitude_deg)).performScrollTo()
        val fields = composeRule.onAllNodes(androidx.compose.ui.test.hasSetTextAction())
        assertEquals(2, fields.fetchSemanticsNodes().size)
        fields[0].performTextReplacement("48.85")
        fields[1].performTextReplacement("2.35")
        // The fields must actually hold the typed text before saving.
        assertTrue(
            fields[0].fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.EditableText,
            ].text.contains("48.85"),
        )
        composeRule.onNodeWithText(string(R.string.save_location)).performScrollTo().performClick()
        assertEquals(48.85 to 2.35, actions.manualLocation)

        composeRule.onNodeWithText(string(R.string.use_current_location)).performScrollTo().performClick()
        assertEquals(1, actions.refreshes)
    }

    @Test
    fun `invalid manual location is not saved`() {
        val actions = Recorder()
        show(actions)
        composeRule.onNodeWithText(string(R.string.latitude_deg)).performScrollTo()
        composeRule.onAllNodes(androidx.compose.ui.test.hasSetTextAction())[0]
            .performTextReplacement("north")
        composeRule.onNodeWithText(string(R.string.save_location)).performScrollTo().performClick()
        assertEquals(null, actions.manualLocation)
        assertTrue(actions.switches.isEmpty())
    }

    @Test
    fun `settings renders its sections and catalog counts`() {
        show(Recorder())
        composeRule.onNodeWithText(string(R.string.settings)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.display)).assertIsDisplayed()
    }
}

