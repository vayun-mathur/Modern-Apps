package com.vayunmathur.weather.data

import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.util.databases
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

/**
 * The real [WeatherRepository] (not [com.vayunmathur.weather.platform.FakeRepository]):
 * its Room database is an in-memory [WeatherDatabase] injected through the
 * process-wide `databases` cache that `buildDatabase` checks first, so the
 * repository's own dao delegation, cache-write and flow code run without
 * SQLCipher/AndroidKeyStore (which are unavailable under Robolectric).
 */
@RunWith(AndroidJUnit4::class)
class WeatherRepositoryRealTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: WeatherDatabase
    private lateinit var repo: WeatherRepository

    @BeforeTest fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, WeatherDatabase::class.java)
            .allowMainThreadQueries().build()
        synchronized(databases) { databases[WeatherDatabase::class] = db }
        repo = WeatherRepository(ctx)
    }

    @AfterTest fun tearDown() {
        synchronized(databases) { databases.remove(WeatherDatabase::class) }
        db.close()
    }

    private fun loc(name: String, order: Int, current: Boolean = false, lat: Double = 52.5, lon: Double = 13.4) =
        SavedLocation(name = name, country = "DE", latitude = lat, longitude = lon, displayOrder = order, isCurrent = current)

    @Test fun locationsCrudThroughRealRepository() = runTest {
        assertTrue(repo.getLocations().isEmpty())
        assertNull(repo.getCurrentDeviceLocation())
        assertTrue(repo.locations.first().isEmpty())

        val berlinId = repo.insertLocation(loc("Berlin", 1))
        val parisId = repo.insertLocation(loc("Paris", 0, lat = 48.85, lon = 2.35))
        assertTrue(berlinId > 0 && parisId > 0 && berlinId != parisId)
        // Ordered by displayOrder, both via one-shot and via the flow.
        assertEquals(listOf("Paris", "Berlin"), repo.getLocations().map { it.name })
        assertEquals(listOf("Paris", "Berlin"), repo.locations.first().map { it.name })

        repo.setOrder(berlinId, -1)
        assertEquals("Berlin", repo.getLocations().first().name)

        repo.updateCoordinates(berlinId, 52.52, 13.405)
        repo.updateName(berlinId, "Berlin-Mitte", "Germany")
        val berlin = repo.getLocations().first { it.id == berlinId }
        assertEquals("Berlin-Mitte", berlin.name)
        assertEquals("Germany", berlin.country)
        assertEquals(52.52, berlin.latitude)
        assertEquals(13.405, berlin.longitude)

        repo.deleteLocation(berlin)
        assertEquals(listOf("Paris"), repo.getLocations().map { it.name })
    }

    @Test fun currentDeviceLocationReplacementThroughRealRepository() = runTest {
        repo.insertLocation(loc("Pinned", 0))
        repo.replaceCurrentDeviceLocation(loc("GPS-1", -1))
        var current = assertNotNull(repo.getCurrentDeviceLocation())
        assertEquals("GPS-1", current.name)
        assertTrue(current.isCurrent)

        repo.replaceCurrentDeviceLocation(loc("GPS-2", -1, lat = 52.6, lon = 13.5))
        current = assertNotNull(repo.getCurrentDeviceLocation())
        assertEquals("GPS-2", current.name)
        // Pinned row survives; exactly one current row exists.
        assertEquals(2, repo.getLocations().size)
        assertEquals(1, repo.getLocations().count { it.isCurrent })
    }

    @Test fun cacheRoundTripThroughRealRepository() = runTest {
        assertNull(repo.getCache(52.5201, 13.405))
        val forecast = ForecastResponse(latitude = 52.52006, longitude = 13.40495)
        val aq = AirQualityResponse(current = null)
        repo.writeForecastCache(52.52006, 13.40495, forecast, aq, fetchedAtEpochMs = 1111L)
        var cache = assertNotNull(repo.getCache(52.5201, 13.405))
        assertEquals(1111L, cache.fetchedAtEpochMs)
        assertTrue(cache.forecastJson.contains("52.52006"))
        assertNotNull(cache.airQualityJson)

        // A later write without air quality preserves the cached AQ payload.
        repo.writeForecastCache(52.52006, 13.40495, forecast.copy(latitude = 1.5), null, fetchedAtEpochMs = 2222L)
        cache = assertNotNull(repo.getCache(52.5201, 13.405))
        assertEquals(2222L, cache.fetchedAtEpochMs)
        assertNotNull(cache.airQualityJson)
        assertTrue(cache.forecastJson.contains("1.5"))

        // Direct upsert replaces by rounded-coordinate primary key.
        repo.upsertCache(cache.copy(forecastJson = "{}", airQualityJson = null, fetchedAtEpochMs = 3333L))
        val replaced = assertNotNull(repo.getCache(52.5201, 13.405))
        assertEquals("{}", replaced.forecastJson)
        assertNull(replaced.airQualityJson)
        assertEquals(3333L, replaced.fetchedAtEpochMs)
    }

    @Test fun writeForecastCacheDefaultTimestampIsNow() = runTest {
        val before = System.currentTimeMillis()
        repo.writeForecastCache(10.0, 20.0, ForecastResponse(latitude = 10.0, longitude = 20.0))
        val cache = assertNotNull(repo.getCache(10.0, 20.0))
        assertTrue(cache.fetchedAtEpochMs >= before)
        assertTrue(cache.fetchedAtEpochMs <= System.currentTimeMillis())
        assertNull(cache.airQualityJson)
    }
}
