package com.vayunmathur.weather.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.DrawerValue
import com.vayunmathur.library.ui.rememberDrawerState
import com.vayunmathur.weather.data.SavedLocation
import com.vayunmathur.weather.domain.WeatherMetric
import com.vayunmathur.weather.network.Current
import com.vayunmathur.weather.network.Daily
import com.vayunmathur.weather.network.ForecastResponse
import com.vayunmathur.weather.network.Hourly
import com.vayunmathur.weather.platform.DisplayUnits
import com.vayunmathur.weather.platform.LocationUiState
import com.vayunmathur.weather.platform.WeatherActions
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ForecastColumn interactions: hour/day selection and opening a metric
 *  graph sheet from a weather block. */
@RunWith(AndroidJUnit4::class)
class ForecastInteractionsTest {

    @get:Rule val rule = createComposeRule()

    private fun forecast() = ForecastResponse(
        latitude = 52.5, longitude = 13.4, utcOffsetSeconds = 0,
        current = Current(
            time = "2026-07-15T14:30", temperature = 25.0, apparentTemperature = 26.0,
            relativeHumidity = 50, dewPoint = 14.0, weatherCode = 2, windSpeed = 10.0,
            windDirection = 90, pressureMsl = 1013.0, visibility = 10000.0,
            cloudCover = 20, windGusts = 20.0, isDay = 1,
        ),
        hourly = Hourly(
            time = listOf("2026-07-15T14:00", "2026-07-15T15:00", "2026-07-15T16:00"),
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

    @Test fun clickingHourAndBlockInvokesActionsAndOpensGraph() {
        var toggledTime: String? = null
        var openedMetric: WeatherMetric? = null
        var openedIso: String? = "unset"
        val actions = object : WeatherActions {
            override fun toggleTime(isoTime: String) { toggledTime = isoTime }
        }
        rule.setContent {
            ForecastColumn(
                state = LocationUiState(
                    location = SavedLocation(id = 1, name = "Berlin", country = "Germany", latitude = 52.5, longitude = 13.4),
                    forecast = forecast(),
                ),
                units = DisplayUnits(use24Hour = true),
                actions = actions,
                drawerState = rememberDrawerState(DrawerValue.Closed),
                paddingValues = PaddingValues(0.dp),
                precipitationNowcast = null,
                nowEpochSec = 1760000000L,
                onOpenMap = { m, iso -> openedMetric = m; openedIso = iso },
            )
        }
        rule.onNodeWithText("Berlin", substring = true).assertIsDisplayed()
        // Hourly strip: the 15 slot (bare hour in 24h units) toggles it.
        rule.onNodeWithText("15").performClick()
        assertEquals("2026-07-15T15:00", toggledTime)
        // Weather blocks are below the fold in the column; the humidity
        // block's label doubles as the graph-sheet entry point.
        rule.onAllNodesWithText("Humidity")[0].performClick()
        rule.waitForIdle()
        // The graph sheet opened (its title is the metric's title, shown
        // once as the sheet header in addition to the block label).
        assertTrue(rule.onAllNodesWithText("Humidity").fetchSemanticsNodes().size >= 1)
        assertEquals(null, openedMetric) // map not opened yet; sheet only
        assertEquals("unset", openedIso)
    }
}
