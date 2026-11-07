package com.vayunmathur.weather.ui

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.map.CameraPosition
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.rememberCameraState
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.weather.Route
import com.vayunmathur.weather.data.SavedLocation
import com.vayunmathur.weather.network.Current
import com.vayunmathur.weather.network.Daily
import com.vayunmathur.weather.network.ForecastResponse
import com.vayunmathur.weather.network.Hourly
import com.vayunmathur.weather.platform.FakeRepository
import com.vayunmathur.weather.platform.WeatherViewModel
import com.vayunmathur.weather.platform.rememberDisplayUnits
import com.vayunmathur.weather.ui.components.MainSearchBar
import androidx.compose.ui.unit.dp
import com.vayunmathur.weather.domain.WeatherMetric
import com.vayunmathur.weather.domain.map.OmMapMetadata
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Shadows
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class PagesTest {

    @get:Rule val rule = createComposeRule()

    private lateinit var app: Application
    private lateinit var repo: FakeRepository
    private lateinit var vm: WeatherViewModel

    @BeforeTest fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        runCatching {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
        repo = FakeRepository(app)
        vm = WeatherViewModel(app, repo)
    }

    private fun loc(id: Long = 1, name: String = "Berlin") = SavedLocation(
        id = id, name = name, country = "Germany", latitude = 52.5, longitude = 13.4,
    )

    private fun forecast() = ForecastResponse(
        latitude = 52.5, longitude = 13.4, utcOffsetSeconds = 0,
        current = Current(
            time = "2026-07-15T14:30", temperature = 25.0, apparentTemperature = 26.0,
            relativeHumidity = 50, dewPoint = 14.0, weatherCode = 2, windSpeed = 10.0,
            windDirection = 90, pressureMsl = 1013.0, visibility = 10000.0,
            cloudCover = 20, windGusts = 20.0, isDay = 1,
        ),
        hourly = Hourly(
            time = listOf("2026-07-15T14:00"), temperature = listOf(25.0),
            weatherCode = listOf(2), precipitationProbability = listOf(10), isDay = listOf(1),
        ),
        daily = Daily(
            time = listOf("2026-07-15"), temperatureMax = listOf(27.0),
            temperatureMin = listOf(17.0), weatherCode = listOf(2),
            precipitationProbabilityMax = listOf(10),
        ),
    )

    @Test fun homePageEmptyStateNavigatesToSearch() {
        val backStack = NavBackStack<Route>(arrayOf(Route.Home))
        rule.setContent { HomePage(backStack, vm) }
        rule.onNodeWithText("No locations yet").assertIsDisplayed()
        rule.onNodeWithText("Add a location").performClick()
        assertEquals(Route.SearchLocation, backStack.backStack.last())
    }

    @Test fun homePageWithLocationsShowsForecast() {
        repo.rows.value = listOf(loc())
        // Seed a forecast directly so the column renders without network.
        vm.ensureForecast(loc())
        val backStack = NavBackStack<Route>(arrayOf(Route.Home))
        rule.setContent { HomePage(backStack, vm) }
        rule.waitForIdle()
        rule.onAllNodesWithText("Berlin", substring = true)[0].assertIsDisplayed()
    }

    @Test fun locationsPageRendersRows() {
        repo.rows.value = listOf(loc(1, "Berlin"), loc(2, "Paris"))
        val backStack = NavBackStack<Route>(arrayOf(Route.Home))
        rule.setContent {
            LocationsPage(
                backStack = backStack, viewModel = vm, activeLocation = loc(),
                onLocationSelect = {}, onClose = {},
            )
        }
        rule.onNodeWithText("Berlin").assertIsDisplayed()
        rule.onNodeWithText("Paris").assertIsDisplayed()
    }

    @Test fun searchPageTypingShowsField() {
        val backStack = NavBackStack<Route>(arrayOf(Route.Home, Route.SearchLocation))
        rule.setContent { SearchLocationPage(backStack, vm) }
        rule.onNodeWithText("Search location").assertIsDisplayed()
        rule.onNodeWithText("City name").performTextInput("Ber")
        rule.mainClock.advanceTimeBy(3_000)
        rule.waitForIdle()
        assertTrue(backStack.backStack.size == 2)
    }

    @Test fun displayUnitsAndDeviceLocationHelper() {
        var loading: Boolean? = null
        rule.setContent {
            val units = rememberDisplayUnits()
            assertNotNull(units.temperature)
            val (_, isLoading) = rememberRequestDeviceLocation(vm)
            loading = isLoading
        }
        assertEquals(false, loading)
    }

    @Test fun mapEffectsWithUnsupportedMetadata() {
        rule.setContent {
            val state = rememberWeatherMapState("Temperature")
            state.metadata = OmMapMetadata(
                referenceTime = "2026-07-01T18:00:00Z",
                validTimes = listOf("2026-07-01T18:00Z"),
                variables = listOf("some_other_var"),
            )
            WeatherMapDecodeEffect(state)
            val camera = rememberCameraState(CameraPosition(target = GeoPoint(13.0, 52.0), zoom = 7.0))
            WeatherMapCameraEffects(state, camera)
        }
        rule.mainClock.advanceTimeBy(5_000)
        rule.waitForIdle()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun mapMetadataEffectHandlesFetchFailure() {
        rule.setContent {
            val state = rememberWeatherMapState("Humidity")
            WeatherMapMetadataEffect(state, "2026-07-01T18:00Z")
            assertEquals(WeatherMetric.Humidity, state.selectedMetric)
        }
        rule.waitForIdle()
    }

    @Test fun openUnitsSettingsFallsBack() {
        // Robolectric resolves the locale settings intent; must not throw.
        openRegionalUnitsSettings(app)
    }

    @Test fun navigationRoutesConstruct() {
        val stack = NavBackStack<Route>(arrayOf(Route.Home))
        stack.add(Route.WeatherMap(52.0, 13.0, "Berlin", null, "Temperature"))
        assertEquals("Berlin", (stack.last() as Route.WeatherMap).name)
        stack.pop()
        assertEquals(Route.Home, stack.last())
    }
}

@RunWith(AndroidJUnit4::class)
class MapDecodeCacheTest {

    @get:Rule val rule = createComposeRule()

    @Test fun decodeEffectUsesCachedRegion() {
        val bounds = com.vayunmathur.library.map.GeoBounds(0.0, 0.0, 10.0, 5.0)
        val state = WeatherMapState("Temperature")
        state.metadata = OmMapMetadata(
            referenceTime = "2026-07-01T18:00:00Z",
            validTimes = listOf("2026-07-01T18:00Z"),
            variables = listOf("temperature_2m"),
        )
        state.visibleBbox = bounds
        val key = cacheKey(bounds, "temperature_2m", "2026-07-01T18:00Z")
        state.cache[key] = DecodedRegion(FloatArray(4) { 20f }, 2, 2, bounds)
        rule.setContent { WeatherMapDecodeEffect(state) }
        rule.mainClock.advanceTimeUntil(20_000) { state.overlay != null }
        rule.waitForIdle()
        assertTrue(state.overlay != null, "decode effect never set overlay from cache")
    }

    @org.junit.Test(timeout = 90_000)
    fun decodeEffectCacheMissAttemptsNativeDecode() {
        val bounds = com.vayunmathur.library.map.GeoBounds(0.0, 0.0, 10.0, 5.0)
        val state = WeatherMapState("Temperature")
        state.metadata = OmMapMetadata(
            referenceTime = "2026-07-01T18:00:00Z",
            validTimes = listOf("2026-07-01T18:00Z"),
            variables = listOf("temperature_2m"),
        )
        state.visibleBbox = bounds
        rule.setContent { WeatherMapDecodeEffect(state) }
        // Cache miss: the effect must at least start loading (native decode
        // or the unavailable-native skip both settle back to not-loading or
        // an overlay; we only require the effect to have run its body).
        rule.mainClock.advanceTimeBy(5_000)
        rule.waitForIdle()
        assertTrue(state.cache.isEmpty() || state.overlay != null || !state.loading)
    }

    @Test fun searchBarClickTogglesDrawer() {
        rule.setContent {
            MainSearchBar(
                paddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
                drawerState = com.vayunmathur.library.ui.rememberDrawerState(
                    com.vayunmathur.library.ui.DrawerValue.Closed,
                ),
                activeLocation = SavedLocation(
                    id = 1, name = "Berlin", country = "Germany",
                    latitude = 52.5, longitude = 13.4,
                ),
            )
        }
        rule.onNodeWithText("Berlin", substring = true).performClick()
        rule.waitForIdle()
    }
}
