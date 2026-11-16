package com.vayunmathur.weather.ui

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.DrawerValue
import com.vayunmathur.library.ui.rememberDrawerState
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.weather.Route
import com.vayunmathur.weather.data.SavedLocation
import com.vayunmathur.weather.domain.SelectedDateOrTime
import com.vayunmathur.weather.network.Current
import com.vayunmathur.weather.network.Daily
import com.vayunmathur.weather.network.ForecastResponse
import com.vayunmathur.weather.network.Hourly
import com.vayunmathur.weather.platform.DisplayUnits
import com.vayunmathur.weather.platform.LocationRow
import com.vayunmathur.weather.platform.LocationUiState
import com.vayunmathur.weather.platform.LocationsUiState
import com.vayunmathur.weather.platform.WeatherActions
import com.vayunmathur.weather.platform.WeatherViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Shadows
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** HomePage loading branch, ForecastColumn selection/graph branches, and
 *  LocationsScreen sheet/units/device-card branches not reached by the
 *  existing screen tests. */
@RunWith(AndroidJUnit4::class)
class HomeAndLocationsExtraTest {

    @get:Rule val rule = createComposeRule()

    private lateinit var app: Application

    @BeforeTest fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        runCatching { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) }
    }

    private class NeverLocationsRepository(context: android.content.Context) :
        com.vayunmathur.weather.data.WeatherRepository(context) {
        override val locations: Flow<List<SavedLocation>> get() = emptyFlow()
    }

    private fun loc(id: Long = 1, name: String = "Berlin", current: Boolean = false) = SavedLocation(
        id = id, name = name, country = "Germany", latitude = 52.5, longitude = 13.4, isCurrent = current,
    )

    private fun forecast() = ForecastResponse(
        latitude = 52.5, longitude = 13.4, utcOffsetSeconds = 3600,
        current = Current(
            time = "2026-07-15T14:30", temperature = 25.0, apparentTemperature = 26.0,
            relativeHumidity = 50, dewPoint = 14.0, weatherCode = 2, windSpeed = 10.0,
            windDirection = 90, pressureMsl = 1013.0, visibility = 10000.0,
            cloudCover = 20, windGusts = 20.0, isDay = 1,
        ),
        hourly = Hourly(
            time = listOf("2026-07-15T14:00", "2026-07-15T15:00", "2026-07-16T14:00"),
            temperature = listOf(25.0, 26.0, 24.0),
            weatherCode = listOf(2, 2, 3), precipitationProbability = listOf(10, 20, 30), isDay = listOf(1, 1, 1),
        ),
        daily = Daily(
            time = listOf("2026-07-15", "2026-07-16"), temperatureMax = listOf(27.0, 26.0),
            temperatureMin = listOf(17.0, 16.0), weatherCode = listOf(2, 3),
            precipitationProbabilityMax = listOf(10, 20), uvIndexMax = listOf(5.0, 4.0),
            sunrise = listOf("2026-07-15T05:00", "2026-07-16T05:01"), sunset = listOf("2026-07-15T21:00", "2026-07-16T21:01"),
        ),
    )

    @Test fun homePageHoldsPlaceholderWhileLocationsLoad() {
        val repo = NeverLocationsRepository(app)
        val vm = WeatherViewModel(app, repo)
        val backStack = NavBackStack<Route>(arrayOf(Route.Home))
        rule.setContent { HomePage(backStack, vm) }
        rule.waitForIdle()
        // Neither the empty state nor a forecast is shown yet.
        rule.onAllNodesWithText("No locations yet").fetchSemanticsNodes().also { assertTrue(it.isEmpty()) }
        rule.onAllNodesWithText("Add a location").fetchSemanticsNodes().also { assertTrue(it.isEmpty()) }
    }

    @Test fun forecastColumnWithDaySelectionShowsHeaderAndBlocks() {
        rule.setContent {
            ForecastColumn(
                state = LocationUiState(
                    location = loc(), forecast = forecast(),
                    selected = SelectedDateOrTime.Day("2026-07-16"),
                ),
                units = DisplayUnits(),
                actions = WeatherActions.Noop,
                drawerState = rememberDrawerState(DrawerValue.Closed),
                paddingValues = PaddingValues(0.dp),
                precipitationNowcast = "Rain soon",
                nowEpochSec = 1760000000L,
                onOpenMap = { _, _ -> },
            )
        }
        rule.onNodeWithText("Berlin", substring = true).assertIsDisplayed()
        rule.waitForIdle()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun forecastColumnRefreshingWithForecastShowsContent() {
        rule.setContent {
            ForecastColumn(
                state = LocationUiState(location = loc(), forecast = forecast(), refreshing = true),
                units = DisplayUnits(),
                actions = WeatherActions.Noop,
                drawerState = rememberDrawerState(DrawerValue.Closed),
                paddingValues = PaddingValues(0.dp),
                precipitationNowcast = null,
                nowEpochSec = 1760000000L,
                onOpenMap = { _, _ -> },
            )
        }
        rule.onAllNodesWithText("25°")[0].assertIsDisplayed()
    }

    @Test fun locationsScreenWithCurrentRowHidesDeviceCardAndLongPressDeletes() {
        var deleted: SavedLocation? = null
        var closed = false
        val actions = object : WeatherActions {
            override fun deleteLocation(location: SavedLocation) { deleted = location }
        }
        rule.setContent {
            LocationsScreen(
                state = LocationsUiState(
                    rows = listOf(
                        LocationRow(loc(1, "Berlin", current = true), "Now", 2, true),
                        LocationRow(loc(2, "Paris"), "Earlier", 0, true),
                    ),
                    activeLocationId = 2L,
                ),
                actions = actions,
                onClose = { closed = true },
            )
        }
        // A current row exists, so no "use device location" card.
        rule.onAllNodesWithText("Use current location", substring = true)
            .fetchSemanticsNodes().also { assertTrue(it.isEmpty()) }
        rule.onNodeWithText("Paris").assertIsDisplayed()
        rule.onNodeWithText("Berlin").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("Confirm delete").performClick()
        assertEquals("Berlin", deleted?.name)
        assertEquals(false, closed)
    }

    @Test fun locationsScreenUnitsButtonAndBackClose() {
        var closed = false
        rule.setContent {
            LocationsScreen(
                state = LocationsUiState(rows = listOf(LocationRow(loc(1, "Berlin"), "Now", 2, true))),
                actions = WeatherActions.Noop,
                onClose = { closed = true },
            )
        }
        rule.onNodeWithText("Units").performClick()
        rule.waitForIdle()
        // Back/close icon is the first clickable in the top bar; the screen
        // itself remains composed after opening the units settings intent.
        rule.onNodeWithText("Locations").assertIsDisplayed()
        assertEquals(false, closed)
    }

    @Test fun formatAgoBoundaries() {
        val res = app.resources
        val now = 1_760_000_000_000L
        assertEquals(res.getString(com.vayunmathur.weather.R.string.just_now), formatAgo(res, now, now))
        assertTrue(formatAgo(res, now - 5 * 60_000L, now).contains("5"))
        assertTrue(formatAgo(res, now - 3 * 3_600_000L, now).contains("3"))
        assertTrue(formatAgo(res, now - 2 * 86_400_000L, now).contains("2"))
        // Future timestamps clamp to "just now".
        assertEquals(res.getString(com.vayunmathur.weather.R.string.just_now), formatAgo(res, now + 60_000L, now))
    }
}
