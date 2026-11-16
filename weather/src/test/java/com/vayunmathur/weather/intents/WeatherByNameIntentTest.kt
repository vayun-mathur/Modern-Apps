package com.vayunmathur.weather.intents

import com.vayunmathur.weather.network.GeocodingResult
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.weather.data.GeoDatabase
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** GetWeatherByNameIntent no-match path and GeocodingResult construction. */
@RunWith(AndroidJUnit4::class)
class WeatherByNameIntentTest {

    @BeforeTest fun setUp() {
        GeoDatabase.init(ApplicationProvider.getApplicationContext())
    }

    @Test fun noMatchReturnsNamedError() = runTest {
        // No places catalogue is unpacked in this sandbox, so any name
        // resolves to zero matches and the intent must say so, not throw.
        val data = GetWeatherByNameIntent().performCalculation(LocationQueryInput("Atlantis"))
        assertEquals("No location matched 'Atlantis'", data.error)
        assertEquals("Atlantis", data.locationName)
        assertEquals(0.0, data.temperatureCelsius)
    }

    @Test fun blankNameReturnsError() = runTest {
        val data = GetWeatherByNameIntent().performCalculation(LocationQueryInput(""))
        assertNotNull(data.error)
    }

    @Test fun geocodingResultConstructionAndSerialization() {
        val full = GeocodingResult(
            id = 7L, name = "Berlin", latitude = 52.52, longitude = 13.405,
            country = "Germany", countryCode = "DE", admin1 = "Berlin", timezone = "Europe/Berlin",
        )
        assertEquals(7L, full.id)
        assertEquals("DE", full.countryCode)
        val copy = full.copy(name = "Berlin-Mitte")
        assertEquals("Berlin-Mitte", copy.name)
        assertEquals(full.latitude, copy.latitude)
        assertTrue(full.toString().contains("Berlin"))
        assertEquals(full.hashCode(), full.copy().hashCode())

        val minimal = GeocodingResult(id = 1L, name = "X", latitude = 0.0, longitude = 0.0)
        assertNull(minimal.country)
        assertNull(minimal.countryCode)
        assertNull(minimal.admin1)
        assertNull(minimal.timezone)

        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString<GeocodingResult>(json.encodeToString(GeocodingResult.serializer(), full))
        assertEquals(full, decoded)
        // Serialized names follow the API (country_code), and missing
        // optional fields decode as null.
        val fromApi = json.decodeFromString<GeocodingResult>(
            """{"id":3,"name":"Bern","latitude":46.95,"longitude":7.45,"country_code":"CH"}"""
        )
        assertEquals("CH", fromApi.countryCode)
        assertNull(fromApi.country)
    }
}
