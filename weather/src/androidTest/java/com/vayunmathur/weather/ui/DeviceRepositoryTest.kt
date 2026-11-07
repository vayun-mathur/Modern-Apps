package com.vayunmathur.weather.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vayunmathur.weather.data.SavedLocation
import com.vayunmathur.weather.data.WeatherCache
import com.vayunmathur.weather.data.WeatherRepository
import com.vayunmathur.weather.network.ForecastResponse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Direct exercise of the real SQLCipher-backed [WeatherRepository] on
 * device (Robolectric has no AndroidKeyStore, so these delegates can only
 * run here). Cleans up every row it inserts so other journeys still see
 * the fresh-install empty state.
 */
@RunWith(AndroidJUnit4::class)
class DeviceRepositoryTest {

    @Test
    fun repositoryCrudAndCache() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = WeatherRepository.get(context)
        val marker = "RepoTest-${System.currentTimeMillis()}"
        var id = -1L
        try {
            id = repo.insertLocation(
                SavedLocation(name = marker, country = "DE", latitude = 52.5, longitude = 13.4, displayOrder = 99),
            )
            assertTrue(id > 0)
            assertTrue(repo.getLocations().any { it.name == marker })
            assertTrue(repo.locations.first().any { it.name == marker })

            repo.updateCoordinates(id, 10.0, 20.0)
            repo.updateName(id, "$marker-renamed", "FR")
            var row = repo.getLocations().first { it.id == id }
            assertEquals("$marker-renamed", row.name)
            assertEquals("FR", row.country)
            assertEquals(10.0, row.latitude, 1e-9)

            repo.setOrder(id, 123)
            row = repo.getLocations().first { it.id == id }
            assertEquals(123, row.displayOrder)

            // Cache round-trip through the delegate + extension.
            repo.writeForecastCache(52.52006, 13.40495, ForecastResponse(52.52006, 13.40495), null, 4242L)
            val cache = repo.getCache(52.5201, 13.405)
            assertNotNull(cache)
            assertEquals(4242L, cache!!.fetchedAtEpochMs)
            repo.upsertCache(WeatherCache(1.0, 2.0, "{}", null, 7L))
            assertEquals(7L, repo.getCache(1.0, 2.0)!!.fetchedAtEpochMs)

            // Current-device row replacement delegates.
            repo.replaceCurrentDeviceLocation(
                SavedLocation(name = marker, latitude = 1.0, longitude = 2.0, isCurrent = true),
            )
            val current = repo.getCurrentDeviceLocation()
            assertNotNull(current)
            assertEquals(marker, current!!.name)
            repo.deleteLocation(current)
            assertNull(repo.getCurrentDeviceLocation())
        } finally {
            // Restore the empty state for the journey tests.
            runCatching {
                repo.getLocations().filter { it.name.startsWith("RepoTest-") || it.name.contains(marker) }
                    .forEach { repo.deleteLocation(it) }
                if (id > 0) repo.getLocations().firstOrNull { it.id == id }?.let { repo.deleteLocation(it) }
                repo.getCurrentDeviceLocation()?.takeIf { it.name.startsWith("RepoTest-") }
                    ?.let { repo.deleteLocation(it) }
            }
        }
    }
}
