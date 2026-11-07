package com.vayunmathur.weather.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.weather.domain.TemperatureUnit
import com.vayunmathur.weather.network.Daily
import com.vayunmathur.weather.network.Hourly
import com.vayunmathur.weather.platform.FakeRepository
import com.vayunmathur.weather.platform.WeatherViewModel
import com.vayunmathur.weather.platform.reverseGeocodeName
import com.vayunmathur.weather.ui.components.DailyCard
import com.vayunmathur.weather.ui.components.HourlyCard
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class MoreEdgesTest {

    @get:Rule val rule = createComposeRule()

    private lateinit var vm: WeatherViewModel

    @BeforeTest fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        runCatching {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
        vm = WeatherViewModel(app, FakeRepository(app))
    }

    @Test fun reverseGeocodeReturnsNullWithoutBackend() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Robolectric has no geocoder backend results: must be null, not throw.
        assertNull(reverseGeocodeName(ctx, 52.5, 13.4))
    }

    @Test fun deviceLocationHelperClickWithoutPermission() {
        var pair: Pair<() -> Unit, Boolean>? = null
        rule.setContent { pair = rememberRequestDeviceLocation(vm) }
        // No permission in Robolectric: the click routes to the permission
        // launcher; the helper must stay composed and not crash the test.
        runCatching { pair!!.first() }
        rule.waitForIdle()
        assertEquals(false, pair!!.second)
    }

    @Test fun dailyCardInvalidDateFallsBackToRaw() {
        val daily = Daily(
            time = listOf("bogus"),
            temperatureMax = listOf(20.0), temperatureMin = listOf(10.0),
            weatherCode = listOf(0), precipitationProbabilityMax = listOf(5),
        )
        rule.setContent { DailyCard(daily, TemperatureUnit.Celsius, null, {}, null) }
        rule.onNodeWithText("bogus").assertIsDisplayed()
    }

    @Test fun hourlyCardUnparseableTimesRenderNothing() {
        val hourly = Hourly(time = listOf("not-a-time"), temperature = listOf(1.0))
        rule.setContent { HourlyCard(hourly, TemperatureUnit.Celsius) }
        rule.onNodeWithText("Hourly forecast").assertDoesNotExist()
    }

    @Test fun dailyCardEmptyAndHourlyEmpty() {
        rule.setContent {
            DailyCard(Daily(), TemperatureUnit.Fahrenheit)
            HourlyCard(Hourly(time = listOf("2026-07-15T14:00"), temperature = listOf(25.0), weatherCode = listOf(0), isDay = listOf(1)), TemperatureUnit.Fahrenheit, 0, true, null, {}, null, "2026-07-15T14:00", 1760000000L)
        }
        rule.onNodeWithText("25°").assertDoesNotExist() // 25C = 77F
        rule.onNodeWithText("77°", substring = true).assertIsDisplayed()
    }
}
