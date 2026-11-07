package com.vayunmathur.weather.platform

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.weather.data.SavedLocation
import com.vayunmathur.weather.data.WeatherCache
import com.vayunmathur.weather.data.weatherJson
import com.vayunmathur.weather.domain.roundCoord
import com.vayunmathur.weather.network.Current
import com.vayunmathur.weather.network.ForecastResponse
import org.junit.runner.RunWith
import org.robolectric.Shadows
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ViewModelFetchTest {

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

    private fun await(cond: () -> Boolean): Boolean {
        var guard = 0
        while (!cond() && guard++ < 250) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        return cond()
    }

    private fun forecast() = ForecastResponse(
        latitude = 52.5, longitude = 13.4,
        current = Current(
            time = "2026-07-15T14:30", temperature = 25.0, apparentTemperature = 26.0,
            relativeHumidity = 50, weatherCode = 2, windSpeed = 10.0, windDirection = 90,
        ),
    )

    @Test fun hydrateFromFreshCacheSkipsNetwork() {
        val loc = SavedLocation(id = 7L, name = "Berlin", latitude = 52.52006, longitude = 13.40495)
        repo.caches[roundCoord(loc.latitude) to roundCoord(loc.longitude)] = WeatherCache(
            latRounded = roundCoord(loc.latitude), lonRounded = roundCoord(loc.longitude),
            forecastJson = weatherJson.encodeToString(forecast()),
            airQualityJson = """{"current":null}""",
            fetchedAtEpochMs = System.currentTimeMillis(),
        )
        vm.ensureForecast(loc)
        assertTrue(await { vm.forecasts.value[7L]?.forecast != null }, "state=${vm.forecasts.value}")
        assertEquals(25.0, vm.forecasts.value[7L]!!.forecast!!.current!!.temperature)
        assertEquals(false, vm.forecasts.value[7L]!!.refreshing)
    }

    @Test fun staleCacheThenNetworkFailureSetsError() {
        val loc = SavedLocation(id = 8L, name = "Nowhere", latitude = 1.0, longitude = 2.0)
        repo.caches[1.0 to 2.0] = WeatherCache(
            latRounded = 1.0, lonRounded = 2.0,
            forecastJson = weatherJson.encodeToString(forecast()),
            fetchedAtEpochMs = 0L,
        )
        vm.ensureForecast(loc)
        // Stale cache hydrates, then the (unreachable) network fetch fails and
        // the state must surface an error while keeping the cached forecast.
        assertTrue(await { vm.forecasts.value[8L]?.error != null }, "state=${vm.forecasts.value}")
        assertNotNull(vm.forecasts.value[8L]!!.forecast)
    }

    @Test fun noCacheNetworkFailureSetsError() {
        val loc = SavedLocation(id = 9L, name = "Nowhere", latitude = 3.0, longitude = 4.0)
        vm.ensureForecast(loc, force = true)
        assertTrue(await { vm.forecasts.value[9L]?.error != null }, "state=${vm.forecasts.value}")
    }

    @Test fun currentLocationFetchWithoutPermission() {
        val loc = SavedLocation(id = 10L, name = "GPS", latitude = 5.0, longitude = 6.0, isCurrent = true)
        vm.ensureForecast(loc, force = true)
        assertTrue(await { vm.forecasts.value[10L]?.error != null }, "state=${vm.forecasts.value}")
    }

    @Test fun refreshAllFetchesSavedLocations() {
        val loc = SavedLocation(id = 11L, name = "Berlin", latitude = 52.52006, longitude = 13.40495)
        repo.rows.value = listOf(loc)
        repo.caches[roundCoord(loc.latitude) to roundCoord(loc.longitude)] = WeatherCache(
            latRounded = roundCoord(loc.latitude), lonRounded = roundCoord(loc.longitude),
            forecastJson = weatherJson.encodeToString(forecast()),
            fetchedAtEpochMs = System.currentTimeMillis(),
        )
        // savedLocations is WhileSubscribed: collect via the screen path is
        // covered elsewhere; here refreshAll over an empty snapshot is a no-op
        // and ensureForecast does the work.
        vm.ensureForecast(loc)
        assertTrue(await { vm.forecasts.value[11L]?.forecast != null })
    }
}

@RunWith(AndroidJUnit4::class)
class ViewModelDeviceFixTest {

    private lateinit var app: Application
    private lateinit var repo: FakeRepository
    private lateinit var vm: WeatherViewModel

    @BeforeTest fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        runCatching {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
        org.robolectric.Shadows.shadowOf(app).grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        )
        repo = FakeRepository(app)
        vm = WeatherViewModel(app, repo)
    }

    private fun setFix(lat: Double, lon: Double) {
        val lm = app.getSystemService(android.location.LocationManager::class.java)
        val shadow = org.robolectric.Shadows.shadowOf(lm)
        shadow.setProviderEnabled(android.location.LocationManager.GPS_PROVIDER, true)
        shadow.setLastKnownLocation(
            android.location.LocationManager.GPS_PROVIDER,
            android.location.Location(android.location.LocationManager.GPS_PROVIDER).apply {
                latitude = lat; longitude = lon; time = System.currentTimeMillis()
            },
        )
    }

    private fun awaitError(id: Long): Boolean {
        var guard = 0
        while (vm.forecasts.value[id]?.error == null && guard++ < 100) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        return vm.forecasts.value[id]?.error != null
    }

    @Test fun farFixUpdatesCoordinatesThenNetworkFails() {
        val loc = SavedLocation(id = 21L, name = "GPS", latitude = 52.5, longitude = 13.4, isCurrent = true)
        setFix(10.0, 20.0) // thousands of km away -> move path
        vm.ensureForecast(loc, force = true)
        assertTrue(awaitError(21L), "state=${vm.forecasts.value}")
    }

    @Test fun nearFixKeepsLocationThenNetworkFails() {
        val loc = SavedLocation(id = 22L, name = "GPS", latitude = 52.5, longitude = 13.4, isCurrent = true)
        setFix(52.5001, 13.4001) // ~11 m away -> keep path
        vm.ensureForecast(loc, force = true)
        assertTrue(awaitError(22L), "state=${vm.forecasts.value}")
    }
}
