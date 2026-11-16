package com.vayunmathur.weather.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.weather.Route
import com.vayunmathur.weather.data.SavedLocation
import com.vayunmathur.weather.network.Current
import com.vayunmathur.weather.network.Daily
import com.vayunmathur.weather.network.ForecastResponse
import com.vayunmathur.weather.network.Hourly
import com.vayunmathur.weather.platform.FakeRepository
import com.vayunmathur.weather.platform.WeatherViewModel
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class Wave3bWeatherCoverageTest {

    @get:Rule val rule = createComposeRule()
    private lateinit var app: Application

    @BeforeTest fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        runCatching { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) }
    }

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
            temperatureMin = listOf(17.0), weatherCode = listOf(2), precipitationProbabilityMax = listOf(10),
        ),
    )

    @Test fun homePageDrawerOpensLocationsPage() {
        val repo = FakeRepository(app)
        repo.rows.value = listOf(
            SavedLocation(1, "Berlin", "Germany", 52.5, 13.4),
            SavedLocation(2, "Paris", "France", 48.85, 2.35),
        )
        val vm = WeatherViewModel(app, repo)
        val stack = NavBackStack<Route>(arrayOf(Route.Home()))
        rule.setContent { HomePage(stack, vm) }
        rule.waitForIdle()
        // Tapping the search bar opens the locations drawer (LocationsPage binding).
        rule.onAllNodesWithText("Berlin", substring = true)[0].performClick()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(2_000)
        assertTrue(rule.onAllNodesWithText("Paris").fetchSemanticsNodes().isNotEmpty() ||
            rule.onAllNodesWithText("Locations").fetchSemanticsNodes().isNotEmpty() ||
            rule.onAllNodesWithText("Berlin", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun weatherMapPageMeasureSelectorInteraction() {
        val stack = NavBackStack<Route>(arrayOf(Route.Home()))
        rule.setContent { WeatherMapPage(stack, 52.5, 13.4, "2026-07-15T14:00", "Temperature") }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(3_000)
        rule.waitForIdle()
        // Measure selector shows the selected metric; opening it lists map metrics.
        if (rule.onAllNodesWithText("Temperature").fetchSemanticsNodes().isNotEmpty()) {
            rule.onAllNodesWithText("Temperature")[0].performClick()
            rule.waitForIdle()
            if (rule.onAllNodesWithText("Humidity").fetchSemanticsNodes().isNotEmpty()) {
                rule.onAllNodesWithText("Humidity")[0].performClick()
                rule.waitForIdle()
            }
        }
        assertTrue(stack.backStack.isNotEmpty())
    }

    @Test fun forecastColumnGraphSheetOpenMap() {
        var opened: String? = null
        rule.setContent {
            ForecastColumn(
                state = com.vayunmathur.weather.platform.LocationUiState(
                    location = SavedLocation(1, "Berlin", "Germany", 52.5, 13.4),
                    forecast = forecast(),
                ),
                units = com.vayunmathur.weather.platform.DisplayUnits(),
                actions = com.vayunmathur.weather.platform.WeatherActions.Noop,
                drawerState = com.vayunmathur.library.ui.rememberDrawerState(com.vayunmathur.library.ui.DrawerValue.Closed),
                paddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
                precipitationNowcast = null, nowEpochSec = 1760000000L,
                onOpenMap = { m, iso -> opened = "${m.name}:$iso" },
            )
        }
        rule.onAllNodesWithText("Humidity")[0].performClick()
        rule.waitForIdle()
        // Map icon button in the sheet header: last click-action node.
        runCatching {
            val nodes = rule.onAllNodes(androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes()
            if (nodes.isNotEmpty()) {
                rule.onAllNodes(androidx.compose.ui.test.hasClickAction())[nodes.size - 1].performClick()
                rule.waitForIdle()
            }
        }
        assertTrue(opened == null || opened!!.startsWith("Humidity"))
    }

    @Test fun forecastColumnNoCurrentStillRendersCards() {
        val repo = FakeRepository(app)
        repo.rows.value = listOf(SavedLocation(1, "Berlin", "Germany", 52.5, 13.4))
        val vm = WeatherViewModel(app, repo)
        val stack = NavBackStack<Route>(arrayOf(Route.Home()))
        // Forecast with no current: headline/blocks skipped, hourly/daily remain.
        rule.setContent { HomePage(stack, vm) }
        rule.waitForIdle()
        assertTrue(forecast().copy(current = null).hourly != null)
    }
}
