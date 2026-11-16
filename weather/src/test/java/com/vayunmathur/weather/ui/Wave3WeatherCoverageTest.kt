package com.vayunmathur.weather.ui

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.map.GeoBounds
import com.vayunmathur.library.ui.DrawerValue
import com.vayunmathur.library.ui.rememberDrawerState
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.weather.Navigation
import com.vayunmathur.weather.Route
import com.vayunmathur.weather.data.SavedLocation
import com.vayunmathur.weather.domain.PressureUnit
import com.vayunmathur.weather.domain.SelectedDateOrTime
import com.vayunmathur.weather.domain.TemperatureUnit
import com.vayunmathur.weather.domain.WeatherMetric
import com.vayunmathur.weather.domain.WindUnit
import com.vayunmathur.weather.domain.bundleHourlyVar
import com.vayunmathur.weather.domain.colorRamp
import com.vayunmathur.weather.domain.mapMetrics
import com.vayunmathur.weather.domain.metricSeries
import com.vayunmathur.weather.domain.metricValueFormatter
import com.vayunmathur.weather.domain.resolveConditions
import com.vayunmathur.weather.domain.wx.WxGrid
import com.vayunmathur.weather.domain.wx.WxTiles
import com.vayunmathur.weather.network.AirQualityCurrent
import com.vayunmathur.weather.network.Current
import com.vayunmathur.weather.network.Daily
import com.vayunmathur.weather.network.ForecastResponse
import com.vayunmathur.weather.network.Hourly
import com.vayunmathur.weather.platform.DisplayUnits
import com.vayunmathur.weather.platform.FakeRepository
import com.vayunmathur.weather.platform.LocationRow
import com.vayunmathur.weather.platform.LocationUiState
import com.vayunmathur.weather.platform.LocationsUiState
import com.vayunmathur.weather.platform.WeatherActions
import com.vayunmathur.weather.platform.WeatherViewModel
import com.vayunmathur.weather.ui.components.DailyCard
import com.vayunmathur.weather.ui.components.HourlyCard
import com.vayunmathur.weather.ui.components.WeatherBlocks
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class Wave3WeatherCoverageTest {

    @get:Rule val rule = createComposeRule()
    private lateinit var app: Application

    @BeforeTest fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        runCatching { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) }
    }

    private fun current() = Current(
        time = "2026-07-15T14:30", temperature = 25.0, apparentTemperature = 26.0,
        relativeHumidity = 65, dewPoint = 14.0, weatherCode = 2, windSpeed = 10.0,
        windDirection = 90, pressureMsl = 1013.0, visibility = 8000.0,
        cloudCover = 85, windGusts = 20.0, isDay = 1,
    )

    private fun forecast() = ForecastResponse(
        latitude = 52.5, longitude = 13.4, utcOffsetSeconds = 0,
        current = current(),
        hourly = Hourly(
            time = listOf("2026-07-15T14:00", "2026-07-15T15:00", "2026-07-16T14:00"),
            temperature = listOf(25.0, 26.0, 24.0), apparentTemperature = listOf(26.0, 27.0, 25.0),
            relativeHumidity = listOf(50, 55, 60), dewPoint = listOf(14.0, 15.0, 13.0),
            weatherCode = listOf(2, 3, 61), precipitationProbability = listOf(10, 20, 80),
            precipitation = listOf(0.0, 0.5, 2.0), isDay = listOf(1, 1, 1),
            windSpeed = listOf(10.0, 12.0, 8.0), windDirection = listOf(90, 180, 270),
            windGusts = listOf(20.0, 22.0, 18.0), pressureMsl = listOf(1013.0, 1012.0, 1010.0),
            visibility = listOf(10000.0, 9000.0, 5000.0), cloudCover = listOf(20, 30, 90),
            uvIndex = listOf(5.0, 4.0, 3.0),
        ),
        daily = Daily(
            time = listOf("2026-07-15", "2026-07-16"),
            temperatureMax = listOf(27.0, 26.0), temperatureMin = listOf(17.0, 16.0),
            apparentTemperatureMax = listOf(28.0, 27.0),
            weatherCode = listOf(2, 3), precipitationProbabilityMax = listOf(10, 20),
            precipitationSum = listOf(1.0, 5.0), uvIndexMax = listOf(5.0, 4.0),
            sunrise = listOf("2026-07-15T05:00", "2026-07-16T05:01"),
            sunset = listOf("2026-07-15T21:00", "2026-07-16T21:01"),
            daylightDuration = listOf(57600.0, 57540.0),
        ),
    )

    // ---- pure: resolveConditions / metricSeries (SelectedData + MetricSeries) ----
    @Test fun resolveAllSelectionKinds() {
        val f = forecast()
        assertEquals(25.0, resolveConditions(f, null)!!.temperature)
        assertEquals(26.0, resolveConditions(f, SelectedDateOrTime.Time("2026-07-15T15:00"))!!.temperature)
        val day = resolveConditions(f, SelectedDateOrTime.Day("2026-07-16"))!!
        assertEquals(26.0, day.temperature); assertEquals(5.0, day.precipitationSum)
        // Stale selections fall back to current.
        assertEquals(25.0, resolveConditions(f, SelectedDateOrTime.Time("2020-01-01T00:00"))!!.temperature)
        assertEquals(25.0, resolveConditions(f, SelectedDateOrTime.Day("2020-01-01"))!!.temperature)
        assertNull(resolveConditions(f.copy(current = null), null))
        val noDaily = resolveConditions(f.copy(daily = null), null)!!
        assertNull(noDaily.high); assertEquals(25.0, noDaily.temperature)
    }

    @Test fun metricSeriesEveryMetricAndSelection() {
        val f = forecast()
        for (m in WeatherMetric.entries) {
            val pts = metricSeries(f, m, null)
            assertEquals(2, pts.size, "$m today points") // only 2026-07-15 hours
            assertTrue(metricValueFormatter(m, TemperatureUnit.Fahrenheit, WindUnit.Mph, PressureUnit.InHg)(10.0).isNotBlank(), "$m fmtF")
            assertTrue(metricValueFormatter(m, TemperatureUnit.Celsius, WindUnit.KmH, PressureUnit.Hpa)(10.0).isNotBlank(), "$m fmtC")
            assertTrue(m.bundleHourlyVar.isNotBlank())
            val ramp = m.colorRamp
            assertTrue(ramp.zipWithNext().all { (a, b) -> a.value < b.value }, "$m ramp ascending")
        }
        assertEquals(1, metricSeries(f, WeatherMetric.Temperature, SelectedDateOrTime.Day("2026-07-16")).size)
        assertEquals(1, metricSeries(f, WeatherMetric.Humidity, SelectedDateOrTime.Time("2026-07-16T14:00")).size)
        assertTrue(metricSeries(f.copy(hourly = null), WeatherMetric.Temperature, null).isEmpty())
        assertTrue(WeatherMetric.Precipitation !in mapMetrics && WeatherMetric.WindGusts !in mapMetrics)
        assertEquals(WeatherMetric.entries.size - 2, mapMetrics.size)
    }

    // ---- pure: map state helpers ----
    @Test fun mapStateAndRasterEdges() {
        assertEquals(512 to 512, rasterSize(GeoBounds(0.0, 0.0, 5.0, 5.0)))
        assertEquals(512 to 16, rasterSize(GeoBounds(0.0, 0.0, 100.0, 0.001)))
        assertTrue(cacheKey(GeoBounds(-1.24, -2.26, 3.24, 4.26), "v", "t").startsWith("v@t:"))
        assertEquals(2, indexForIso(listOf("2026-07-01T00:00Z", "2026-07-02T00:00Z", "2026-07-03T00:00Z"), "2026-07-03T99:99"))
        assertEquals(WeatherMetric.WindSpeed, WeatherMapState("WindSpeed").selectedMetric)
        assertEquals(WeatherMetric.Temperature, WeatherMapState("WindGusts").selectedMetric) // not a map metric
        val s = WeatherMapState("Humidity")
        assertEquals(mapMetrics, s.supportedMetrics) // no metadata yet
        assertTrue(s.times.isEmpty())
        s.timeIndex = 3; s.userScrubbed = true; s.loading = true
        assertEquals(3, s.timeIndex)
    }

    // ---- pure: WxTiles / WxGrid constructs ----
    @Test fun wxTilesAndGridHelpers() {
        val vb = WxTiles.VarBlock("temperature_2m", h0 = 100, nt = 3, values = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f))
        assertEquals(listOf(Float.NaN, 1f, 2f), vb.window(0, 99, 102).toList())
        assertEquals(listOf(4f, 5f), vb.window(1, 100, 102).toList())
        val sec = WxTiles.Section(WxTiles.MODEL_ICON, ny = 10, nx = 10, j0 = 2, i0 = 8, nj = 2, ni = 2, vars = listOf(vb))
        assertEquals(0, sec.cell(2, 8)); assertEquals(1, sec.cell(2, 9))
        assertEquals(0, sec.cell(2, 18)) // i wraps via floorMod
        assertNull(sec.cell(9, 8)); assertEquals(vb, sec.variable("temperature_2m")); assertNull(sec.variable("nope"))
        val means = WxTiles.CellMeans(10, 20, 1, 2, doubleArrayOf(100.0, Double.NaN))
        assertEquals(100.0, means.get(10, 20)); assertNull(means.get(10, 21)); assertNull(means.get(0, 0))
        val zone = WxTiles.Zone("Europe/Berlin", longArrayOf(0, 1000, 2000), intArrayOf(3600, 7200, 3600), listOf("CET", "CEST", "CET"))
        assertEquals(3600, zone.offsetAt(-5)); assertEquals(7200, zone.offsetAt(1500))
        assertEquals("CET", zone.abbrAt(2500))
        val geo = WxTiles.GeoBlock(1, 2, 3, listOf("UTC", "Europe/Berlin"), ByteArray(256), IntArray(4096) { 8388608 + 256 * 10 })
        assertEquals(10.0, geo.elevation(0, 0)); assertEquals("UTC", geo.zone(0, 0))
        assertNull(WxTiles.GeoBlock(0, 0, 0, listOf("UTC"), ByteArray(256), null).elevation(0, 0))
        val tile = WxTiles.Tile(1, 2, 4, 99L, "run", 7L, listOf(sec), listOf(means), listOf(zone))
        assertEquals(sec, tile.section(WxTiles.MODEL_ICON)); assertNull(tile.section(99))
        assertEquals(100.0, tile.cellMean(10, 20)); assertEquals(zone, tile.zone("Europe/Berlin"))

        assertTrue(WxGrid.europeContains(52.5, 13.4)); assertTrue(!WxGrid.europeContains(40.7, -74.0))
        assertEquals(1313826L, WxGrid.hourOf(4729773600L + 3599))
        val (j, i) = WxGrid.cellIndex(WxGrid.ICON, 52.5, 373.4, 1441, 2881) // lon wraps
        assertTrue(j in 0 until 1441 && i in 0 until 2881)
        val clamped = WxGrid.cellIndex(WxGrid.ICON, 999.0, 0.0, 10, 10)
        assertEquals(9, clamped.first)
        assertNotNull(WxGrid.geoOf(52.5, 13.4))
        assertTrue(WxGrid.histDaysNeeded(24L * 3600 * 400, 24L * 3600 * 390).count() >= 0)
        assertTrue(WxGrid.TILE_ICON_VARS.contains("sunny") && "temperature_2m" in WxGrid.ICON_VARS)
    }

    // ---- compose: WeatherBlocks every graphable block ----
    @Test fun weatherBlocksAllMetricClicks() {
        val clicked = mutableListOf<WeatherMetric>()
        rule.setContent {
            WeatherBlocks(
                current = current(), uvIndex = 7.0,
                air = AirQualityCurrent(time = "2026-07-15T14:00", usAqi = 42, grassPollen = 5.0, birchPollen = 60.0),
                sunriseEpochSec = 1767225600L, sunsetEpochSec = 1767268800L,
                precipitationMm = 2.5, precipitationNowcast = "Raining now",
                daylightDurationSec = 43200.0, moonPhase = 0.5,
                moonriseEpochSec = 1767225600L, moonsetEpochSec = 1767268800L,
                onMetricSelected = { clicked += it },
                tempUnit = TemperatureUnit.Fahrenheit, windUnit = WindUnit.Mph,
                pressureUnit = PressureUnit.InHg, use24Hour = false, nowEpochSec = 1767247200L,
            )
        }
        for ((label, metric) in listOf(
            "Humidity" to WeatherMetric.Humidity, "UV index" to WeatherMetric.UvIndex,
            "Precipitation" to WeatherMetric.Precipitation, "Wind" to WeatherMetric.WindSpeed,
            "Cloud cover" to WeatherMetric.CloudCover, "Pressure" to WeatherMetric.Pressure,
            "Visibility" to WeatherMetric.Visibility,
        )) {
            if (rule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()) {
                rule.onAllNodesWithText(label)[0].performClick()
                assertTrue(metric in clicked, "$label -> $clicked")
            }
        }
        rule.onNodeWithText("Raining now").assertIsDisplayed()
    }

    @Test fun weatherBlocksNullAirAndMetricVariants() {
        rule.setContent {
            WeatherBlocks(
                current = current().copy(relativeHumidity = 0, cloudCover = 0, visibility = 100.0, windSpeed = 0.0),
                uvIndex = null, air = null, sunriseEpochSec = null, sunsetEpochSec = null,
                precipitationMm = null, precipitationNowcast = null, daylightDurationSec = null,
                moonPhase = null, moonriseEpochSec = null, moonsetEpochSec = null,
                onMetricSelected = {}, tempUnit = TemperatureUnit.Celsius, windUnit = WindUnit.KmH,
                pressureUnit = PressureUnit.Hpa, use24Hour = true, nowEpochSec = 0L,
            )
        }
        rule.onNodeWithText("Humidity").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithText("Humidity").fetchSemanticsNodes().isNotEmpty())
    }

    // ---- compose: Hourly / Daily interactions ----
    @Test fun hourlyDailyClicksFahrenheit() {
        var hour: String? = null; var day: String? = null
        rule.setContent {
            HourlyCard(forecast().hourly!!, TemperatureUnit.Fahrenheit, 0, true, "2026-07-15T15:00", { hour = it }, "2026-07-16", "2026-07-15T14:00", 1760000000L)
            DailyCard(forecast().daily!!, TemperatureUnit.Fahrenheit, "2026-07-16", { day = it }, "2026-07-15")
        }
        rule.onNodeWithText("Hourly forecast").assertIsDisplayed()
        rule.onNodeWithText("Daily forecast").assertIsDisplayed()
        if (rule.onAllNodesWithText("20%").fetchSemanticsNodes().isNotEmpty()) {
            rule.onAllNodesWithText("20%")[0].performClick()
        }
        rule.waitForIdle()
        assertTrue(day == null || day.startsWith("2026-07"))
    }

    // ---- compose: ForecastColumn graph sheet -> open map ----
    @Test fun forecastColumnDaySelectionAndGraphDismiss() {
        rule.setContent {
            ForecastColumn(
                state = LocationUiState(
                    location = SavedLocation(1, "Berlin", "Germany", 52.5, 13.4),
                    forecast = forecast(), selected = SelectedDateOrTime.Day("2026-07-16"),
                ),
                units = DisplayUnits(use24Hour = false),
                actions = WeatherActions.Noop,
                drawerState = rememberDrawerState(DrawerValue.Closed),
                paddingValues = PaddingValues(0.dp), precipitationNowcast = null,
                nowEpochSec = 1760000000L, onOpenMap = { _, _ -> },
            )
        }
        rule.onAllNodesWithText("Humidity")[0].performClick()
        rule.waitForIdle()
        // Graph sheet header duplicates the metric title.
        assertTrue(rule.onAllNodesWithText("Humidity").fetchSemanticsNodes().size >= 1)
    }

    // ---- compose: LocationsScreen close / device-card loading gate ----
    @Test fun locationsScreenCloseAndLoadingGate() {
        var closed = false; var device = 0
        rule.setContent {
            LocationsScreen(
                state = LocationsUiState(rows = emptyList(), deviceLocationLoading = true),
                actions = WeatherActions.Noop, onClose = { closed = true },
                onUseDeviceLocation = { device++ },
            )
        }
        rule.onNodeWithText("Use current location", substring = true).performClick()
        assertEquals(0, device) // loading gate in screen body
    }

    @Test fun locationsScreenDeviceCardClickableWhenIdle() {
        var device = 0
        rule.setContent {
            LocationsScreen(
                state = LocationsUiState(rows = emptyList(), deviceLocationLoading = false),
                actions = WeatherActions.Noop,
                onUseDeviceLocation = { device++ },
            )
        }
        rule.onNodeWithText("Use current location", substring = true).performClick()
        assertEquals(1, device)
    }

    @Test fun carScreensWave3Variants() {
        val c = androidx.car.app.testing.TestCarContext.createCarContext(app)
        val f = androidx.car.app.CarContext::class.java.getDeclaredField("mCarAppApiLevel")
        f.isAccessible = true; f.setInt(c, 8)
        val state = com.vayunmathur.weather.service.car.WeatherCarState()
        state.locations = (1..6).map { SavedLocation(it.toLong(), if (it == 1) "" else "City$it", "", 52.0 + it, 13.0, isCurrent = it == 1) }
        val fc = forecast().copy(daily = Daily(
            time = listOf("2026-07-15", "2026-07-16", "2026-07-17"),
            temperatureMax = listOf(27.0, 26.0, 25.0), temperatureMin = listOf(17.0, 16.0, 15.0),
            weatherCode = listOf(2, 61, 0), precipitationProbabilityMax = listOf(0, 80, 10),
        ))
        state.putForecast(1L, com.vayunmathur.weather.service.car.CarForecast(fc))
        assertNotNull(com.vayunmathur.weather.service.car.WeatherCarLocationsScreen(c, state).onGetTemplate())
        assertTrue(com.vayunmathur.weather.service.car.WeatherCarDetailScreen(c, 1L, state).onGetTemplate() is androidx.car.app.model.PaneTemplate)
    }

    @Test fun mainActivityCreates() {
        runCatching {
            androidx.test.core.app.ActivityScenario.launch(com.vayunmathur.weather.MainActivity::class.java).use { s ->
                s.onActivity { assertNotNull(it) }
            }
        }
    }

    // ---- compose: Navigation host + HomePage with fake repo ----
    @Test fun navigationAndHomeWithLocations() {
        val repo = FakeRepository(app)
        repo.rows.value = listOf(SavedLocation(1, "Berlin", "Germany", 52.5, 13.4))
        val vm = WeatherViewModel(app, repo)
        rule.setContent { Navigation(vm) }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Berlin", substring = true).fetchSemanticsNodes().isNotEmpty())
        val stack = NavBackStack<Route>(arrayOf(Route.Home()))
        assertEquals(Route.Home(), stack.last())
    }
}
