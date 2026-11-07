package com.vayunmathur.weather.data

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.weather.network.AirQualityResponse
import com.vayunmathur.weather.network.ForecastResponse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class WeatherDaoTest {

    private lateinit var db: WeatherDatabase
    private lateinit var dao: WeatherDao

    @BeforeTest fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WeatherDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.weatherDao()
    }

    @AfterTest fun close() { db.close() }

    private fun loc(name: String, order: Int, current: Boolean = false) = SavedLocation(
        name = name, country = "DE", latitude = 52.5, longitude = 13.4,
        displayOrder = order, isCurrent = current,
    )

    @Test fun insertAndOrdering() = runTest {
        val idB = dao.insertLocation(loc("B", 1))
        dao.insertLocation(loc("A", 0))
        assertEquals(listOf("A", "B"), dao.getLocations().map { it.name })
        assertEquals(listOf("A", "B"), dao.observeLocations().first().map { it.name })
        dao.setOrder(idB, -1)
        assertEquals("B", dao.getLocations().first().name)
    }

    @Test fun updateAndDelete() = runTest {
        val id = dao.insertLocation(loc("Old", 0))
        dao.updateCoordinates(id, 1.0, 2.0)
        dao.updateName(id, "New", "FR")
        val row = dao.getLocations().single()
        assertEquals("New", row.name); assertEquals("FR", row.country)
        assertEquals(1.0, row.latitude); assertEquals(2.0, row.longitude)
        dao.deleteLocation(row)
        assertTrue(dao.getLocations().isEmpty())
    }

    @Test fun replaceCurrentKeepsSingleCurrentRow() = runTest {
        dao.insertLocation(loc("Pinned", 0))
        dao.replaceCurrentDeviceLocation(loc("GPS1", -1, current = false))
        dao.replaceCurrentDeviceLocation(loc("GPS2", -1, current = false))
        val all = dao.getLocations()
        assertEquals(2, all.size)
        assertEquals("GPS2", assertNotNull(dao.getCurrentDeviceLocation()).name)
        assertTrue(dao.getCurrentDeviceLocation()!!.isCurrent)
    }

    @Test fun cacheUpsertAndWritePreservesAirQuality() = runTest {
        val forecast = ForecastResponse(latitude = 52.52006, longitude = 13.40495)
        val aq = AirQualityResponse(current = null)
        dao.writeForecastCache(52.52006, 13.40495, forecast, aq, fetchedAtEpochMs = 1000L)
        var cache = assertNotNull(dao.getCache(52.5201, 13.405))
        assertEquals(1000L, cache.fetchedAtEpochMs)
        assertTrue(cache.forecastJson.contains("52.52006"))
        assertNotNull(cache.airQualityJson)
        // Second write without AQ keeps prior AQ json, updates time.
        dao.writeForecastCache(52.52006, 13.40495, forecast.copy(latitude = 1.0), null, fetchedAtEpochMs = 2000L)
        cache = assertNotNull(dao.getCache(52.5201, 13.405))
        assertEquals(2000L, cache.fetchedAtEpochMs)
        assertNotNull(cache.airQualityJson)
        assertNull(dao.getCache(0.0, 0.0))
        // Direct upsert replaces by PK
        dao.upsertCache(cache.copy(forecastJson = "{}", fetchedAtEpochMs = 3000L))
        assertEquals("{}", assertNotNull(dao.getCache(52.5201, 13.405)).forecastJson)
    }

    @Test fun migrationsDeclared() {
        assertEquals(listOf(MIGRATION_1_2), WeatherDatabase.migrations)
        assertEquals("weather-db", DB_NAME)
    }

    @Test fun geoDatabaseAbsentSearch() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        GeoDatabase.init(ctx)
        assertEquals(GeoDatabase.Status.Absent, GeoDatabase.status.value)
        assertTrue(GeoDatabase.searchPlaces("Berlin").isEmpty())
        assertTrue(GeoDatabase.searchPlaces("").isEmpty())
    }
}
