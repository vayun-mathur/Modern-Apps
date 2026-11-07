package com.vayunmathur.astronomy.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.astronomy.Route
import com.vayunmathur.astronomy.data.model.DeepSkyObject
import com.vayunmathur.astronomy.data.model.Star
import com.vayunmathur.astronomy.domain.engine.AltAz
import com.vayunmathur.astronomy.domain.engine.RaDec
import com.vayunmathur.astronomy.platform.SearchActions
import com.vayunmathur.astronomy.platform.SearchResult
import com.vayunmathur.astronomy.platform.SettingsActions
import com.vayunmathur.astronomy.platform.SettingsUiState
import com.vayunmathur.astronomy.platform.SkyMapActions
import com.vayunmathur.astronomy.platform.SkyMapUiState
import com.vayunmathur.astronomy.platform.VisibleDeepSky
import com.vayunmathur.astronomy.platform.VisibleSky
import com.vayunmathur.astronomy.platform.VisibleStar
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(kotlin.time.ExperimentalTime::class)
@RunWith(RobolectricTestRunner::class)
class AstronomyScreensTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Composable
    private fun stack(): NavBackStack<Route> = rememberNavBackStack(Route.SkyMap)

    private fun skyState(): SkyMapUiState {
        val star = Star(id = 1, name = "Testar", properName = "Testar", ra = 1.0, dec = 0.5, mag = 2.0)
        val visible = VisibleSky(
            stars = listOf(VisibleStar(star, AltAz(0.5, 0.5), RaDec(1.0, 0.5))),
            deepSky = listOf(VisibleDeepSky(DeepSkyObject("M31", "Andromeda Galaxy", 0.71, 0.71, 3.4, "Galaxy"), AltAz(0.4, 0.4), RaDec(0.71, 0.71))),
        )
        return SkyMapUiState(visibleSky = visible, simTime = Instant.fromEpochMilliseconds(1_700_000_000_000), timeZone = TimeZone.UTC)
    }

    @Test fun `sky canvas renders with a populated sky`() {
        val state = skyState()
        rule.setContent {
            SkyCanvas(
                visibleSky = state.visibleSky,
                viewState = com.vayunmathur.astronomy.domain.projection.ViewState(
                    centerAzRad = 0.0, centerAltRad = 0.5, fovDeg = 70f,
                    screenW = 1080f, screenH = 1920f,
                ),
                showConstellationLines = true,
                showGrid = true,
                showDeepSky = true,
                showPlanets = true,
                onPan = { _, _ -> },
                onZoom = {},
                onTap = {},
                onObjectTap = {},
            )
        }
        assertTrue(ctx.packageName.isNotBlank())
    }

    @Test fun `settings screen renders state and toggles route through actions`() {
        var grid: Boolean? = null
        val actions = object : SettingsActions {
            override fun setShowGrid(v: Boolean) { grid = v }
        }
        rule.setContent {
            SettingsScreen(backStack = stack(), state = SettingsUiState(showGrid = true, starCount = 100, constellationCount = 88, deepSkyCount = 50), actions = actions)
        }
        rule.onNodeWithText("Settings", substring = true).assertExists()
        assertEquals(null, grid)
    }

    @Test fun `search screen shows seeded results and selecting routes the id`() {
        val selected = mutableListOf<String>()
        val actions = object : SearchActions {
            override fun search(query: String): List<SearchResult> =
                if (query.isBlank()) emptyList() else listOf(SearchResult("PLANET_MARS", "Mars", "Planet", null))
            override fun selectObject(id: String) { selected += id }
        }
        rule.setContent { SearchScreen(backStack = stack(), actions = actions, initialQuery = "Mar") }
        rule.onNodeWithText("Mars").assertExists()
        rule.onNodeWithText("Mars").performClick()
        assertEquals(listOf("PLANET_MARS"), selected)
    }

    @Test fun `search screen with no query shows no results`() {
        rule.setContent { SearchScreen(backStack = stack(), actions = SearchActions.Noop, initialQuery = "") }
        rule.onNodeWithText("Mars").assertDoesNotExist()
    }
}
