package com.vayunmathur.weather.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.rememberDrawerState
import com.vayunmathur.library.ui.DrawerValue
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
import com.vayunmathur.weather.ui.components.LocationItem
import com.vayunmathur.weather.ui.components.MainSearchBar
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ComposeScreensTest {

    @get:Rule val rule = createComposeRule()

    private fun loc(id: Long = 1, name: String = "Berlin", current: Boolean = false) = SavedLocation(
        id = id, name = name, country = "Germany", latitude = 52.5, longitude = 13.4,
        displayOrder = 0, isCurrent = current,
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
            precipitationProbabilityMax = listOf(10), uvIndexMax = listOf(5.0),
            sunrise = listOf("2026-07-15T05:00"), sunset = listOf("2026-07-15T21:00"),
        ),
    )

    @Test fun forecastColumnWithForecast() {
        rule.setContent {
            ForecastColumn(
                state = LocationUiState(location = loc(), forecast = forecast()),
                units = DisplayUnits(),
                actions = WeatherActions.Noop,
                drawerState = rememberDrawerState(DrawerValue.Closed),
                paddingValues = PaddingValues(0.dp),
                precipitationNowcast = "Raining now",
                nowEpochSec = 1760000000L,
                onOpenMap = { _, _ -> },
            )
        }
        rule.onNodeWithText("Berlin", substring = true).assertIsDisplayed()
        rule.onAllNodesWithText("25°")[0].assertIsDisplayed()
    }

    @Test fun forecastColumnLoadingAndError() {
        rule.setContent {
            ForecastColumn(
                state = LocationUiState(location = loc(), forecast = null, error = "Boom"),
                units = DisplayUnits(),
                actions = WeatherActions.Noop,
                drawerState = rememberDrawerState(DrawerValue.Closed),
                paddingValues = PaddingValues(0.dp),
                precipitationNowcast = null,
                nowEpochSec = 0L,
                onOpenMap = { _, _ -> },
            )
        }
        rule.onNodeWithText("Boom").assertIsDisplayed()
    }

    @Test fun forecastColumnSelected() {
        rule.setContent {
            ForecastColumn(
                state = LocationUiState(
                    location = loc(), forecast = forecast(),
                    selected = SelectedDateOrTime.Time("2026-07-15T14:00"),
                ),
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

    @Test fun forecastColumnScrollsAndRendersBlocks() {
        rule.setContent {
            ForecastColumn(
                state = LocationUiState(location = loc(), forecast = forecast()),
                units = DisplayUnits(),
                actions = WeatherActions.Noop,
                drawerState = rememberDrawerState(DrawerValue.Closed),
                paddingValues = PaddingValues(0.dp),
                precipitationNowcast = null,
                nowEpochSec = 1760000000L,
                onOpenMap = { _, _ -> },
            )
        }
        rule.onNodeWithText("Berlin", substring = true).assertIsDisplayed()
        // Scroll the forecast column repeatedly so the blocks grid below
        // the fold composes and draws.
        repeat(8) {
            rule.onAllNodes(androidx.compose.ui.test.isRoot()).onLast()
                .performTouchInput {
                    down(androidx.compose.ui.geometry.Offset(160f, 400f))
                    moveBy(androidx.compose.ui.geometry.Offset(160f, 100f))
                    up()
                }
            rule.waitForIdle()
        }
        rule.waitForIdle()
    }

    @Test fun homeScreenCompact() {
        rule.setContent {
            HomeScreen(
                state = LocationUiState(location = loc(), forecast = forecast()),
                units = DisplayUnits(),
                actions = WeatherActions.Noop,
                precipitationNowcast = null,
                nowEpochSec = 1760000000L,
            )
        }
        rule.onNodeWithText("Berlin", substring = true).assertIsDisplayed()
    }

    @Test fun locationsScreenSelectAndSearch() {
        var selected: SavedLocation? = null
        var searched = false
        var usedDevice = false
        rule.setContent {
            LocationsScreen(
                state = LocationsUiState(
                    rows = listOf(
                        LocationRow(loc(1, "Berlin"), "No data yet", 2, true),
                        LocationRow(loc(2, "Paris"), "Last updated 5m ago", 0, true),
                    ),
                    activeLocationId = 1L,
                ),
                actions = WeatherActions.Noop,
                onLocationSelect = { selected = it },
                onSearchLocation = { searched = true },
                onUseDeviceLocation = { usedDevice = true },
            )
        }
        rule.onNodeWithText("Locations").assertIsDisplayed()
        rule.onNodeWithText("Berlin").assertIsDisplayed()
        rule.onNodeWithText("Paris").performClick()
        assertEquals("Paris", selected?.name)
        rule.onNodeWithText("Search location").performClick()
        assertTrue(searched)
    }

    @Test fun locationsScreenEmptyShowsDeviceCard() {
        rule.setContent {
            LocationsScreen(
                state = LocationsUiState(rows = emptyList()),
                actions = WeatherActions.Noop,
            )
        }
        rule.onNodeWithText("Use current location", substring = true).assertIsDisplayed()
    }

    @Test fun locationItemAndSearchBar() {
        var clicked = false
        rule.setContent {
            LocationItem(
                location = loc(current = true), description = "No data yet",
                currentWeatherCode = 0, isDay = true, isSelected = true,
                onClick = { clicked = true }, onLongClick = {},
            )
            MainSearchBar(
                paddingValues = PaddingValues(0.dp),
                drawerState = rememberDrawerState(DrawerValue.Closed),
                activeLocation = loc(),
            )
        }
        rule.onNodeWithText("Berlin").assertIsDisplayed()
        rule.onNodeWithText("Berlin").performClick()
        assertTrue(clicked)
    }

    @Test fun mainSearchBarNoLocation() {
        rule.setContent {
            MainSearchBar(
                paddingValues = PaddingValues(0.dp),
                drawerState = rememberDrawerState(DrawerValue.Closed),
                activeLocation = null,
            )
        }
        rule.onNodeWithText("••••").assertIsDisplayed()
    }

    @Test fun legendAndMeasureSelector() {
        rule.setContent {
            Legend(com.vayunmathur.weather.domain.WeatherMetric.Temperature, "-20°", "40°")
            MeasureSelector(
                selected = com.vayunmathur.weather.domain.WeatherMetric.Temperature,
                options = com.vayunmathur.weather.domain.mapMetrics,
                onSelect = {},
            )
        }
        rule.onAllNodesWithText("Temperature")[0].assertIsDisplayed()
        rule.onNodeWithText("-20°").assertIsDisplayed()
        rule.onNodeWithText("40°").assertIsDisplayed()
    }

    @Test fun routeSerialization() {
        val json = Json { ignoreUnknownKeys = true }
        val home = json.encodeToString(Route.serializer(), Route.Home)
        assertEquals(Route.Home, json.decodeFromString(Route.serializer(), home))
        val map = Route.WeatherMap(52.5, 13.4, "Berlin", "2026-07-15T14:00", "Temperature")
        val decoded = json.decodeFromString(
            Route.serializer(), json.encodeToString(Route.serializer(), map),
        ) as Route.WeatherMap
        assertEquals("Berlin", decoded.name)
        assertEquals("Temperature", decoded.metric)
        assertEquals(Route.SearchLocation, json.decodeFromString(Route.serializer(), json.encodeToString(Route.serializer(), Route.SearchLocation)))
    }
}
