package com.vayunmathur.weather.platform

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.weather.data.SavedLocation
import com.vayunmathur.weather.network.WxAq
import com.vayunmathur.weather.network.WxBundle
import com.vayunmathur.weather.network.WxCurrent
import com.vayunmathur.weather.network.toForecastResponse
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** WeatherViewModel success path: a good bundle is applied, cached, and clears errors. */
@RunWith(AndroidJUnit4::class)
class ViewModelSuccessTest {

    private lateinit var app: Application
    private lateinit var repo: FakeRepository

    @BeforeTest fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        runCatching { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) }
        repo = FakeRepository(app)
    }

    private fun await(cond: () -> Boolean): Boolean {
        var guard = 0
        while (!cond() && guard++ < 250) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        return cond()
    }

    private fun bundle() = WxBundle(
        lat = 52.5, lon = 13.4, utcOffset = 0,
        current = WxCurrent(time = 1784073600, temp = 25.0, feels = 26.0, rh = 50, dew = 14.0, code = 2,
            windSpd = 10.0, windDir = 90, pres = 1013.0, vis = 10000.0, cloud = 20, gust = 15.0, day = 1),
        aq = WxAq(time = 1784073600, usAqi = 33),
    )

    @Test fun successfulFetchAppliesForecastAndAirQuality() {
        val vm = WeatherViewModel(app, repo) { _, _ -> bundle() }
        val loc = SavedLocation(id = 31L, name = "Berlin", latitude = 52.5, longitude = 13.4)
        vm.ensureForecast(loc, force = true)
        assertTrue(await { vm.forecasts.value[31L]?.forecast != null }, "state=${vm.forecasts.value}")
        val state = vm.forecasts.value[31L]!!
        assertEquals(25.0, state.forecast!!.current!!.temperature)
        assertEquals(33, state.airQuality!!.current!!.usAqi)
        assertEquals(false, state.refreshing)
        assertNull(state.error)
        assertTrue(state.fetchedAtEpochMs > 0)
        // The fetch was written through to the repository cache.
        assertTrue(repo.caches.isNotEmpty())
    }

    @Test fun freshInMemoryStateSkipsSecondFetch() {
        var calls = 0
        val vm = WeatherViewModel(app, repo) { _, _ -> calls++; bundle() }
        val loc = SavedLocation(id = 32L, name = "Berlin", latitude = 52.5, longitude = 13.4)
        vm.ensureForecast(loc, force = true)
        assertTrue(await { vm.forecasts.value[32L]?.forecast != null })
        vm.ensureForecast(loc) // not forced, just fetched: no refetch
        Thread.sleep(100)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, calls)
    }

    @Test fun failureAfterSuccessKeepsForecastAndSetsError() {
        // Seed a stale cache (hydrates first), then the network fetch fails:
        // the error must surface while the hydrated forecast stays.
        val loc = SavedLocation(id = 33L, name = "Berlin", latitude = 52.5, longitude = 13.4)
        val key = com.vayunmathur.weather.domain.roundCoord(loc.latitude) to
            com.vayunmathur.weather.domain.roundCoord(loc.longitude)
        repo.caches[key] = com.vayunmathur.weather.data.WeatherCache(
            latRounded = key.first, lonRounded = key.second,
            forecastJson = com.vayunmathur.weather.data.weatherJson.encodeToString(bundle().toForecastResponse()),
            fetchedAtEpochMs = 0L,
        )
        val vm = WeatherViewModel(app, repo) { _, _ -> throw java.io.IOException("down") }
        vm.ensureForecast(loc, force = true)
        assertTrue(await { vm.forecasts.value[33L]?.error != null }, "state=${vm.forecasts.value}")
        assertNotNull(vm.forecasts.value[33L]!!.forecast)
        assertEquals(25.0, vm.forecasts.value[33L]!!.forecast!!.current!!.temperature)
    }

    @Test fun bundleWithoutAqKeepsNullAirQuality() {
        val vm = WeatherViewModel(app, repo) { _, _ -> bundle().copy(aq = null) }
        val loc = SavedLocation(id = 34L, name = "Berlin", latitude = 52.5, longitude = 13.4)
        vm.ensureForecast(loc, force = true)
        assertTrue(await { vm.forecasts.value[34L]?.forecast != null })
        assertNull(vm.forecasts.value[34L]!!.airQuality)
    }

    @Test fun reverseGeocodeWithoutBackendIsNull() = runTest {
        // Robolectric has no Geocoder backend: must return null, not throw.
        assertNull(reverseGeocodeName(app, 52.5, 13.4))
    }
}
