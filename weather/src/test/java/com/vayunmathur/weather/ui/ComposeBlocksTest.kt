package com.vayunmathur.weather.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.weather.domain.PressureUnit
import com.vayunmathur.weather.domain.SelectedDateOrTime
import com.vayunmathur.weather.domain.TemperatureUnit
import com.vayunmathur.weather.domain.WindUnit
import com.vayunmathur.weather.network.AirQualityCurrent
import com.vayunmathur.weather.network.Current
import com.vayunmathur.weather.network.Daily
import com.vayunmathur.weather.network.ForecastResponse
import com.vayunmathur.weather.network.Hourly
import com.vayunmathur.weather.ui.components.CurrentWeatherCard
import com.vayunmathur.weather.ui.components.DailyCard
import com.vayunmathur.weather.ui.components.HourlyCard
import com.vayunmathur.weather.ui.components.MetricGraphSheet
import com.vayunmathur.weather.ui.components.SelectedDateTimeHeader
import com.vayunmathur.weather.ui.components.SummaryCard
import com.vayunmathur.weather.ui.components.UseDeviceLocationCard
import com.vayunmathur.weather.ui.components.WeatherBlocks
import com.vayunmathur.weather.ui.components.blocks.AirQualityBlock
import com.vayunmathur.weather.ui.components.blocks.CloudCoverBlock
import com.vayunmathur.weather.ui.components.blocks.HumidityBlock
import com.vayunmathur.weather.ui.components.blocks.MoonBlock
import com.vayunmathur.weather.ui.components.blocks.PollenBlock
import com.vayunmathur.weather.ui.components.blocks.PrecipitationBlock
import com.vayunmathur.weather.ui.components.blocks.PressureBlock
import com.vayunmathur.weather.ui.components.blocks.SunBlock
import com.vayunmathur.weather.ui.components.blocks.UvIndexBlock
import com.vayunmathur.weather.ui.components.blocks.VisibilityBlock
import com.vayunmathur.weather.ui.components.blocks.WindBlock
import com.vayunmathur.weather.domain.MetricPoint
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ComposeBlocksTest {

    @get:Rule val rule = createComposeRule()

    private fun current(
        humidity: Int = 65, cloud: Int = 85, windDir: Int = 90,
    ) = Current(
        time = "2026-07-15T14:30", temperature = 25.0, apparentTemperature = 26.0,
        relativeHumidity = humidity, dewPoint = 14.0, weatherCode = 2, windSpeed = 10.0,
        windDirection = windDir, pressureMsl = 1013.0, visibility = 8000.0,
        cloudCover = cloud, windGusts = 20.0, isDay = 1,
    )

    private fun air(aqi: Int? = 42) = AirQualityCurrent(
        time = "2026-07-15T14:00", usAqi = aqi, grassPollen = 5.0, birchPollen = 60.0,
    )

    @Test fun currentCardShowsTempAndCondition() {
        rule.setContent {
            CurrentWeatherCard(2, true, 25.0, 26.0, 27.0, 17.0, TemperatureUnit.Celsius)
        }
        rule.onNodeWithText("25°").assertIsDisplayed()
        rule.onNodeWithText("Partly cloudy", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Feels like", substring = true).assertIsDisplayed()
    }

    @Test fun currentCardWithoutFeelsLikeOrRange() {
        rule.setContent {
            CurrentWeatherCard(0, false, 10.0, null, null, null, TemperatureUnit.Fahrenheit)
        }
        rule.onNodeWithText("50°").assertIsDisplayed()
    }

    @Test fun blocksRenderValues() {
        rule.setContent {
            HumidityBlock(current(), TemperatureUnit.Celsius)
            UvIndexBlock(7.0)
            AirQualityBlock(air())
            PollenBlock(air())
            CloudCoverBlock(current())
            WindBlock(current(), WindUnit.KmH)
            PressureBlock(current(), PressureUnit.Hpa)
            VisibilityBlock(current(), useMiles = false)
        }
        rule.onNodeWithText("65%").assertIsDisplayed()
        rule.onNodeWithText("7").assertIsDisplayed()
        rule.onNodeWithText("42").assertIsDisplayed()
        rule.onNodeWithText("85%").assertIsDisplayed()
        rule.onNodeWithText("1013").assertIsDisplayed()
        rule.onNodeWithText("8").assertIsDisplayed()
    }

    @Test fun blocksNullAndExtremeVariants() {
        rule.setContent {
            AirQualityBlock(null)
            PollenBlock(null)
            UvIndexBlock(null)
            UvIndexBlock(12.0)
            AirQualityBlock(air(aqi = 350))
            CloudCoverBlock(current(cloud = 5))
            PressureBlock(current().copy(pressureMsl = 970.0), PressureUnit.InHg)
            VisibilityBlock(current().copy(visibility = 16093.0), useMiles = true)
            WindBlock(current(), WindUnit.Mph)
            HumidityBlock(current(humidity = 95), TemperatureUnit.Fahrenheit)
        }
        rule.onNodeWithText("Good", substring = true).assertDoesNotExist()
        rule.onNodeWithText("12").assertIsDisplayed()
    }

    @Test fun sunMoonPrecipBlocks() {
        rule.setContent {
            SunBlock(
                sunriseEpochSec = 1767225600L, sunsetEpochSec = 1767268800L,
                use24Hour = true, daylightDurationSec = 43200.0, nowEpochSec = 1767247200L,
            )
            MoonBlock(
                moonPhase = 0.5, moonriseEpochSec = 1767225600L, moonsetEpochSec = 1767268800L,
                use24Hour = false, nowEpochSec = 1767247200L,
            )
            PrecipitationBlock(amountMm = 2.5, useInches = false, nowcast = "Raining now")
            PrecipitationBlock(amountMm = 25.4, useInches = true, nowcast = null)
        }
        rule.onNodeWithText("Raining now").assertIsDisplayed()
        rule.onNodeWithText("Full Moon", substring = true).assertIsDisplayed()
        rule.onNodeWithText("1.00").assertIsDisplayed()
    }

    @Test fun moonPhaseVariantsAndNullSun() {
        rule.setContent {
            MoonBlock(moonPhase = 0.1, moonriseEpochSec = null, moonsetEpochSec = null, use24Hour = true)
            SunBlock(sunriseEpochSec = null, sunsetEpochSec = null, use24Hour = true)
        }
        rule.onNodeWithText("Waxing Crescent", substring = true).assertIsDisplayed()
    }

    @Test fun weatherBlocksGridAndMetricClick() {
        var clicked: com.vayunmathur.weather.domain.WeatherMetric? = null
        rule.setContent {
            WeatherBlocks(
                current = current(), uvIndex = 5.0, air = air(),
                sunriseEpochSec = 1767225600L, sunsetEpochSec = 1767268800L,
                precipitationMm = 1.0, precipitationNowcast = "Raining now",
                daylightDurationSec = 40000.0, moonPhase = 0.25,
                moonriseEpochSec = 1767225600L, moonsetEpochSec = 1767268800L,
                onMetricSelected = { clicked = it },
                tempUnit = TemperatureUnit.Celsius, windUnit = WindUnit.KmH,
                pressureUnit = PressureUnit.Hpa, use24Hour = true, nowEpochSec = 1767247200L,
            )
        }
        rule.onNodeWithText("65%").assertIsDisplayed()
        rule.onNodeWithText("65%").performClick()
        assertEquals(com.vayunmathur.weather.domain.WeatherMetric.Humidity, clicked)
    }

    @Test fun hourlyAndDailyCards() {
        var hour: String? = null
        var day: String? = null
        val hourly = Hourly(
            time = listOf("2026-07-15T13:00", "2026-07-15T14:00"),
            temperature = listOf(24.0, 25.0), weatherCode = listOf(0, 2),
            precipitationProbability = listOf(0, 20), isDay = listOf(1, 1),
        )
        val daily = Daily(
            time = listOf("2026-07-15", "2026-07-16"),
            temperatureMax = listOf(27.0, 19.0), temperatureMin = listOf(17.0, 12.0),
            weatherCode = listOf(0, 61), precipitationProbabilityMax = listOf(10, 80),
        )
        rule.setContent {
            HourlyCard(hourly, TemperatureUnit.Celsius, 0, false, null, { hour = it }, null, "2026-07-15T14:00", 1760000000L)
            DailyCard(daily, TemperatureUnit.Celsius, "2026-07-16", { day = it }, "2026-07-15")
        }
        rule.mainClock.advanceTimeBy(2_000)
        rule.onNodeWithText("Hourly forecast").assertIsDisplayed()
        rule.onNodeWithText("Daily forecast").assertIsDisplayed()
        rule.onNodeWithText("27°").performClick()
        assertEquals("2026-07-15", day)
        assertTrue(hour == null || hour!!.startsWith("2026"))
    }

    @Test fun emptyCardsRenderNothing() {
        rule.setContent {
            DailyCard(Daily(), TemperatureUnit.Celsius)
            HourlyCard(Hourly(), TemperatureUnit.Celsius)
        }
        rule.onNodeWithText("Daily forecast").assertDoesNotExist()
    }

    @Test fun summarySelectedAndDeviceCards() {
        var cleared = false
        var used = false
        val forecast = ForecastResponse(
            latitude = 0.0, longitude = 0.0, current = current(),
            daily = Daily(
                time = listOf("2026-07-15"), temperatureMax = listOf(27.0),
                temperatureMin = listOf(17.0), precipitationProbabilityMax = listOf(0),
            ),
        )
        rule.setContent {
            SummaryCard(forecast, TemperatureUnit.Celsius)
            SelectedDateTimeHeader(SelectedDateOrTime.Day("2026-06-25"), false) { cleared = true }
            SelectedDateTimeHeader(SelectedDateOrTime.Time("2026-06-25T15:00"), true) {}
            UseDeviceLocationCard(onClick = { used = true }, isLoading = false)
            UseDeviceLocationCard(onClick = {}, isLoading = true)
        }
        rule.onAllNodesWithText("Use current location", substring = true)[0].assertIsDisplayed()
        rule.onNodeWithText("Thu 25 Jun", substring = true).assertIsDisplayed()
    }

    @Test fun metricGraphSheetStates() {
        var opened = false
        var dismissed = false
        rule.setContent {
            MetricGraphSheet(
                title = "Temperature",
                points = listOf(MetricPoint(1767225600L, 10.0), MetricPoint(1767229200L, 12.0), MetricPoint(1767232800L, 11.0)),
                valueLabel = { "${it.toInt()}°" },
                timeLabel = { "t$it" },
                onOpenMap = { opened = true },
                onDismiss = { dismissed = true },
            )
        }
        rule.onNodeWithText("Temperature").assertIsDisplayed()
        rule.onNodeWithText("12°").assertIsDisplayed()
    }

    @Test fun metricGraphSheetInteraction() {
        rule.setContent {
            MetricGraphSheet(
                title = "Temperature",
                points = (0..10).map { MetricPoint(1767225600L + it * 3600, 10.0 + it) },
                valueLabel = { "${it.toInt()}°" },
                timeLabel = { "h" },
                onOpenMap = {},
                onDismiss = {},
            )
        }
        rule.waitForIdle()
        // Tap and drag across the chart so the selection marker draws.
        rule.onAllNodes(androidx.compose.ui.test.isRoot()).onLast().performTouchInput {
            down(androidx.compose.ui.geometry.Offset(160f, 376f))
            moveBy(androidx.compose.ui.geometry.Offset(60f, 0f))
            up()
        }
        rule.waitForIdle()
        rule.onNodeWithText("Temperature").assertIsDisplayed()
    }

    @Test fun metricGraphSheetNotEnoughData() {
        rule.setContent {
            MetricGraphSheet(
                title = "Wind", points = listOf(MetricPoint(0L, 1.0)),
                valueLabel = { it.toString() }, timeLabel = { "" },
                onOpenMap = {}, onDismiss = {},
            )
        }
        rule.onNodeWithText("Not enough data to plot.").assertIsDisplayed()
    }
}
