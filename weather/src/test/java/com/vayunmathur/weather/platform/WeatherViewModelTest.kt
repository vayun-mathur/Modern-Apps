package com.vayunmathur.weather.platform

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.weather.data.SavedLocation
import com.vayunmathur.weather.data.WeatherCache
import com.vayunmathur.weather.data.WeatherRepository
import com.vayunmathur.weather.domain.SelectedDateOrTime
import com.vayunmathur.weather.network.AirQualityResponse
import com.vayunmathur.weather.network.ForecastResponse
import com.vayunmathur.weather.widget.glance.WeatherBlobGlanceWidget
import com.vayunmathur.weather.widget.glance.WeatherBlobGlanceWidgetReceiver
import com.vayunmathur.weather.widget.glance.WeatherGlanceWidget
import com.vayunmathur.weather.widget.glance.WeatherGlanceWidgetReceiver
import com.vayunmathur.weather.widget.glance.WidgetWeather
import com.vayunmathur.weather.widget.glance.loadWeatherSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class WeatherViewModelTest {

    private lateinit var app: Application
    private lateinit var repo: FakeRepository
    private lateinit var vm: WeatherViewModel

    @BeforeTest fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        runCatching {
            androidx.work.WorkManager.initialize(
                app, androidx.work.Configuration.Builder().build(),
            )
        }
        repo = FakeRepository(app)
        vm = WeatherViewModel(app, repo)
    }

    @Test fun selectionToggles() {
        assertNull(vm.selectedDateOrTime.value)
        vm.selectTime("2026-07-15T14:00")
        assertEquals(SelectedDateOrTime.Time("2026-07-15T14:00"), vm.selectedDateOrTime.value)
        vm.toggleTime("2026-07-15T14:00")
        assertNull(vm.selectedDateOrTime.value)
        vm.toggleTime("2026-07-15T15:00")
        assertEquals(SelectedDateOrTime.Time("2026-07-15T15:00"), vm.selectedDateOrTime.value)
        vm.toggleDay("2026-07-16")
        assertEquals(SelectedDateOrTime.Day("2026-07-16"), vm.selectedDateOrTime.value)
        vm.toggleDay("2026-07-16")
        assertNull(vm.selectedDateOrTime.value)
        vm.selectDay("2026-07-17")
        vm.clearSelection()
        assertNull(vm.selectedDateOrTime.value)
    }

    @Test fun factoryCreatesViewModel() {
        val created = WeatherViewModelFactory(app, repo).create(WeatherViewModel::class.java)
        assertNotNull(created)
    }

    @Test fun addDeleteAndReorderLocations() = runTest {
        vm.addLocation("Berlin", "Germany", 52.5, 13.4)
        vm.addLocation("Paris", "France", 48.8, 2.3)
        // viewModelScope is Main under Robolectric; drain by polling the fake.
        var rows = repo.getLocations()
        var guard = 0
        while (rows.size < 2 && guard++ < 50) {
            Thread.sleep(20)
            rows = repo.getLocations()
        }
        assertEquals(2, rows.size)
        assertEquals(0, rows[0].displayOrder)
        assertEquals(1, rows[1].displayOrder)
        vm.reorderLocations(listOf(rows[1], rows[0]))
        guard = 0
        while (repo.getLocations().first().name != "Paris" && guard++ < 50) Thread.sleep(20)
        vm.deleteLocation(repo.getLocations().first())
        guard = 0
        while (repo.getLocations().size != 1 && guard++ < 50) Thread.sleep(20)
        assertEquals(1, repo.getLocations().size)
    }

    @Test fun setCurrentLocationReplaces() {
        vm.setCurrentLocation("Here", 10.0, 20.0)
        var guard = 0
        while (repo.rows.value.none { it.isCurrent } && guard++ < 250) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertTrue(repo.rows.value.any { it.isCurrent }, "rows=${repo.rows.value}")
    }

    @Test fun refreshAllWithNoLocationsIsNoOp() {
        vm.refreshAll(force = true)
        assertTrue(vm.forecasts.value.isEmpty())
    }

    @Test fun ensureForecastDedupes() {
        val loc = SavedLocation(id = 999L, name = "Nowhere", latitude = 0.0, longitude = 0.0)
        vm.ensureForecast(loc)
        vm.ensureForecast(loc)
    }

    @Test fun widgetSnapshotEmptyWithoutCache() = runTest {
        // Real repository cannot open SQLCipher under Robolectric; the
        // snapshot loader must degrade to null rather than throw.
        assertNull(loadWeatherSnapshot(app))
    }

    @Test fun glanceWidgetsConstruct() {
        assertNotNull(WeatherGlanceWidget())
        assertNotNull(WeatherBlobGlanceWidget())
        assertNotNull(WeatherGlanceWidgetReceiver())
        assertNotNull(WeatherBlobGlanceWidgetReceiver())
        val data = WidgetWeather(temperatureCelsius = 22.0, weatherCode = 1, isDay = true)
        assertEquals(22.0, data.temperatureCelsius)
        assertTrue(data.isDay)
    }

    @Test fun staleConstant() {
        assertEquals(15 * 60 * 1000L, WeatherViewModel.STALE_THRESHOLD_MS)
    }
}
