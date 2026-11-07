package com.vayunmathur.astronomy.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.astronomy.Route
import com.vayunmathur.astronomy.data.model.DeepSkyObject
import com.vayunmathur.astronomy.data.model.Star
import com.vayunmathur.astronomy.domain.engine.AltAz
import com.vayunmathur.astronomy.domain.engine.RaDec
import com.vayunmathur.astronomy.platform.AstronomyViewModel
import com.vayunmathur.astronomy.platform.ConstellationMode
import com.vayunmathur.astronomy.platform.SkyMapActions
import com.vayunmathur.astronomy.platform.SkyMapUiState
import com.vayunmathur.astronomy.platform.VisibleDeepSky
import com.vayunmathur.astronomy.platform.VisibleMoon
import com.vayunmathur.astronomy.platform.VisiblePlanet
import com.vayunmathur.astronomy.platform.VisibleSky
import com.vayunmathur.astronomy.platform.VisibleStar
import com.vayunmathur.astronomy.platform.VisibleSun
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavResultRegistry
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Tests for [SkyMapScreen] and [SkyMapPage]: top-bar navigation routes,
 * the camera toggle composing the AR background's permission path, night
 * mode, the time scrubber seeding the actions, and a native-graphics
 * capture that forces the canvas draw layers (grid, art, labels) to run.
 */
@OptIn(ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkyMapScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class RecordingActions : SkyMapActions {
        val fovs = mutableListOf<Float>()
        val selected = mutableListOf<String>()
        val times = mutableListOf<Pair<Instant, Boolean>>()

        override fun setFov(v: Float) {
            fovs += v
        }

        override fun selectObject(id: String) {
            selected += id
        }

        override fun setTime(instant: Instant, live: Boolean) {
            times += instant to live
        }
    }

    private fun skyState(
        nightMode: Boolean = false,
        constellationMode: ConstellationMode = ConstellationMode.LINES_AND_ART,
    ): SkyMapUiState {
        val star = Star(id = 1, name = "Testar", properName = "Testar", ra = 1.0, dec = 0.5, mag = 1.5)
        val visible = VisibleSky(
            stars = listOf(VisibleStar(star, AltAz(0.5, 0.5), RaDec(1.0, 0.5))),
            planets = listOf(VisiblePlanet("PLANET_MARS", "Mars", AltAz(0.6, 0.4), RaDec(1.2, 0.3), -0.5, 0.7)),
            sun = VisibleSun(AltAz(0.3, 0.2), RaDec(0.8, 0.1), 1.0),
            moon = VisibleMoon(AltAz(0.9, 0.6), RaDec(1.5, 0.4), 0.5, 0.75, 7.0),
            deepSky = listOf(
                VisibleDeepSky(
                    DeepSkyObject("M31", "Andromeda Galaxy", 0.71, 0.71, 3.4, "Galaxy"),
                    AltAz(0.4, 0.4),
                    RaDec(0.71, 0.71),
                )
            ),
        )
        return SkyMapUiState(
            visibleSky = visible,
            simTime = Instant.fromEpochMilliseconds(1_700_000_000_000),
            timeZone = TimeZone.UTC,
            isLive = true,
            nightMode = nightMode,
            constellationMode = constellationMode,
            selectedObjectId = "STAR_1",
        )
    }

    private fun showScreen(
        state: SkyMapUiState = skyState(),
        actions: SkyMapActions,
        initialCameraOn: Boolean = false,
    ): NavBackStack<Route> {
        lateinit var stack: NavBackStack<Route>
        composeRule.setContent {
            stack = rememberNavBackStack(Route.SkyMap)
            // SkyMapScreen's history scrubber delivers results through the
            // registry MainNavigation normally provides.
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                SkyMapScreen(
                    backStack = stack,
                    state = state,
                    actions = actions,
                    initialCameraOn = initialCameraOn,
                )
            }
        }
        @Suppress("UNCHECKED_CAST")
        return stack
    }

    @Test
    fun `top bar search and settings push their routes`() {
        val stack = showScreen(actions = RecordingActions())
        composeRule.onNodeWithContentDescription("Search").performClick()
        assertEquals(Route.Search, stack.last())
        composeRule.onNodeWithContentDescription("Settings").performClick()
        assertEquals(Route.Settings, stack.last())
    }

    @Test
    fun `camera toggle composes the camera background permission path`() {
        showScreen(actions = RecordingActions())
        // No CAMERA permission under Robolectric: toggling composes
        // CameraBackground, which asks and (unanswered) renders nothing.
        composeRule.onNodeWithContentDescription("Camera").performClick()
        composeRule.waitForIdle()
        // The screen survives the toggle whatever the permission answer was.
        composeRule.onNodeWithContentDescription("Search").assertIsDisplayed()
    }

    @Test
    fun `camera starting on renders behind the sky`() {
        showScreen(actions = RecordingActions(), initialCameraOn = true)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Search").assertIsDisplayed()
    }

    @Test
    fun `time scrubber seeds actions with the sim time in live mode`() {
        val actions = RecordingActions()
        showScreen(actions = actions)
        composeRule.waitForIdle()
        assertTrue(actions.times.isNotEmpty(), "scrubber never set a time")
        assertEquals(true, actions.times.first().second)
    }

    @Test
    fun `night mode composes and draws`() {
        showScreen(state = skyState(nightMode = true), actions = RecordingActions())
        // Forcing a capture rasterises the canvas: grid, constellation art,
        // planet/star/deep-sky layers and labels all execute their draw code.
        val image = composeRule.onRoot().captureToImage()
        assertTrue(image.width > 0 && image.height > 0)
    }

    @Test
    fun `constellations off composes and draws`() {
        showScreen(
            state = skyState(constellationMode = ConstellationMode.OFF),
            actions = RecordingActions(),
        )
        val image = composeRule.onRoot().captureToImage()
        assertTrue(image.width > 0 && image.height > 0)
    }

    @Test
    fun `sky map page binds the real view model`() {
        val viewModel = AstronomyViewModel(app)
        lateinit var stack: NavBackStack<Route>
        composeRule.setContent {
            stack = rememberNavBackStack(Route.SkyMap)
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                SkyMapPage(backStack = stack, viewModel = viewModel)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Search").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Settings").performClick()
        assertEquals(Route.Settings, stack.last())
        assertNotNull(viewModel.visibleSky.value)
    }

    @Test
    fun `low accuracy shows the compass calibration banner`() {
        val state = skyState().copy(
            accuracy = android.hardware.SensorManager.SENSOR_STATUS_UNRELIABLE,
        )
        showScreen(state = state, actions = RecordingActions())
        composeRule.waitForIdle()
        // The banner renders for unreliable sensors (text is library-owned);
        // composing without crashing is the assertion available here.
        composeRule.onNodeWithContentDescription("Search").assertIsDisplayed()
    }
}
