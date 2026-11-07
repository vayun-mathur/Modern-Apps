package com.vayunmathur.weather.service

import android.content.Context
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.PaneTemplate
import androidx.car.app.testing.TestCarContext
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.weather.data.SavedLocation
import com.vayunmathur.weather.data.weatherJson
import com.vayunmathur.weather.network.Current
import com.vayunmathur.weather.network.Daily
import com.vayunmathur.weather.network.ForecastResponse
import com.vayunmathur.weather.service.car.CarForecast
import com.vayunmathur.weather.service.car.WeatherCarDetailScreen
import com.vayunmathur.weather.service.car.WeatherCarLocationsScreen
import com.vayunmathur.weather.service.car.WeatherCarState
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class CarScreensTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun carContext(apiLevel: Int = 7): TestCarContext {
        val c = TestCarContext.createCarContext(ctx)
        // TestCarContext never establishes a host API level; set the field
        // the host binder would normally fill so screens can pick a template.
        val field = androidx.car.app.CarContext::class.java.getDeclaredField("mCarAppApiLevel")
        field.isAccessible = true
        field.setInt(c, apiLevel)
        return c
    }

    private fun loc(id: Long = 1, name: String = "Berlin", current: Boolean = false) = SavedLocation(
        id = id, name = name, country = "Germany", latitude = 52.5, longitude = 13.4,
        isCurrent = current,
    )

    private fun forecast() = ForecastResponse(
        latitude = 52.5, longitude = 13.4,
        current = Current(
            time = "2026-07-15T14:30", temperature = 25.0, apparentTemperature = 26.0,
            relativeHumidity = 50, weatherCode = 2, windSpeed = 10.0, windDirection = 90,
        ),
        daily = Daily(
            time = listOf("2026-07-15", "2026-07-16"),
            temperatureMax = listOf(27.0, 19.0), temperatureMin = listOf(17.0, 12.0),
            weatherCode = listOf(2, 61), precipitationProbabilityMax = listOf(10, 80),
        ),
    )

    @Test fun locationsLoadingTemplate() {
        val state = WeatherCarState()
        val screen = WeatherCarLocationsScreen(carContext(), state)
        assertTrue(screen.onGetTemplate() is MessageTemplate)
    }

    @Test fun locationsEmptyTemplateWithPermissionDenied() {
        val state = WeatherCarState()
        state.locations = emptyList()
        state.permissionDenied = true
        val screen = WeatherCarLocationsScreen(carContext(), state)
        assertTrue(screen.onGetTemplate() is MessageTemplate)
    }

    @Test fun locationsListTemplate() {
        val state = WeatherCarState()
        state.locations = listOf(loc(), loc(2, "Paris"))
        state.putForecast(1L, CarForecast(forecast()))
        val screen = WeatherCarLocationsScreen(carContext(), state)
        assertNotNull(screen.onGetTemplate())
    }

    @Test fun locationsCurrentRowTemplate() {
        val state = WeatherCarState()
        state.locations = listOf(loc(current = true, name = ""))
        val screen = WeatherCarLocationsScreen(carContext(), state)
        assertNotNull(screen.onGetTemplate())
    }

    @Test fun locationsSectionedTemplate() {
        val state = WeatherCarState()
        state.locations = listOf(loc())
        state.putForecast(1L, CarForecast(forecast()))
        assertNotNull(WeatherCarLocationsScreen(carContext(apiLevel = 8), state).onGetTemplate())
    }

    @Test fun detailMissingAndLoadingTemplates() {
        val state = WeatherCarState()
        state.locations = listOf(loc())
        assertTrue(WeatherCarDetailScreen(carContext(), 99L, state).onGetTemplate() is MessageTemplate)
        assertTrue(WeatherCarDetailScreen(carContext(), 1L, state).onGetTemplate() is MessageTemplate)
    }

    @Test fun detailPaneTemplate() {
        val state = WeatherCarState()
        state.locations = listOf(loc())
        state.putForecast(1L, CarForecast(forecast(), fetchedAtEpochMs = 5L))
        val template = WeatherCarDetailScreen(carContext(), 1L, state).onGetTemplate()
        assertTrue(template is PaneTemplate, "got $template")
    }

    @Test fun detailPaneWithoutCurrent() {
        val state = WeatherCarState()
        state.locations = listOf(loc())
        val noCurrent = forecast().copy(current = null)
        state.putForecast(1L, CarForecast(noCurrent))
        assertTrue(WeatherCarDetailScreen(carContext(), 1L, state).onGetTemplate() is PaneTemplate)
    }

    @Test fun carForecastJsonRoundTripViaState() {
        val state = WeatherCarState()
        val json = weatherJson.encodeToString(forecast())
        val decoded = com.vayunmathur.weather.service.car.decodeCarForecast(json, null, 7L)
        assertNotNull(decoded)
        state.putForecast(3L, decoded)
        assertTrue(state.forecastFor(3L)!!.forecast.daily!!.time.size == 2)
    }
}
