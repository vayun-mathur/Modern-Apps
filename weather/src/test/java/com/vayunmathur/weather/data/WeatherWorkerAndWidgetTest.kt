package com.vayunmathur.weather.data

import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.vayunmathur.library.util.databases
import com.vayunmathur.weather.domain.roundCoord
import com.vayunmathur.weather.network.ForecastResponse
import com.vayunmathur.weather.network.WeatherApi
import com.vayunmathur.weather.network.WxStatic
import com.vayunmathur.weather.widget.loadWeatherSnapshot
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
 * [WeatherRefreshWorker.doWork] and the widget snapshot loader against a
 * real [WeatherRepository] backed by an in-memory database (injected via
 * the `databases` cache, as in [WeatherRepositoryRealTest]). The network is
 * pointed at a dead loopback port so per-location refresh fails hermetically
 * and the worker's failure-isolation and success paths are deterministic.
 */
@RunWith(AndroidJUnit4::class)
class WeatherWorkerAndWidgetTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: WeatherDatabase
    private lateinit var repo: WeatherRepository

    @BeforeTest fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, WeatherDatabase::class.java)
            .allowMainThreadQueries().build()
        synchronized(databases) { databases[WeatherDatabase::class] = db }
        repo = WeatherRepository(ctx)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            ctx, Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        WeatherApi.bundleBaseOverride = "https://127.0.0.1:9/wx/v1"
        WxStatic.enabled = false
    }

    @AfterTest fun tearDown() {
        WeatherApi.bundleBaseOverride = null
        WxStatic.enabled = true
        synchronized(databases) { databases.remove(WeatherDatabase::class) }
        db.close()
        // WeatherRepository.get() caches a singleton whose lazy db would
        // otherwise keep pointing at this test's (now closed) database.
        val instance = WeatherRepository::class.java.getDeclaredField("instance")
        instance.isAccessible = true
        instance.set(null, null)
    }

    private fun loc(name: String, current: Boolean = false, lat: Double = 52.5, lon: Double = 13.4) =
        SavedLocation(name = name, country = "DE", latitude = lat, longitude = lon, isCurrent = current)

    @Test fun workerSucceedsWithNoLocations() = runTest {
        val worker = TestListenableWorkerBuilder<WeatherRefreshWorker>(ctx).build()
        assertTrue(worker.doWork() is ListenableWorker.Result.Success)
    }

    @Test fun workerIsolatesPerLocationFailuresAndSucceeds() = runTest {
        repo.insertLocation(loc("Berlin"))
        repo.insertLocation(loc("Paris", lat = 48.85, lon = 2.35))
        val worker = TestListenableWorkerBuilder<WeatherRefreshWorker>(ctx).build()
        // Both bundles fail (dead loopback); each failure is logged and
        // skipped, the widget update still runs, and the worker succeeds
        // rather than retrying the whole batch.
        assertTrue(worker.doWork() is ListenableWorker.Result.Success)
        // No cache was written for the failed locations.
        assertNull(repo.getCache(roundCoord(52.5), roundCoord(13.4)))
        assertEquals(2, repo.getLocations().size)
    }

    @Test fun widgetSnapshotReadsCurrentLocationCache() = runTest {
        repo.insertLocation(loc("Pinned", lat = 48.85, lon = 2.35))
        val currentId = repo.insertLocation(loc("GPS", current = true))
        assertTrue(currentId > 0)
        val forecast = ForecastResponse(
            latitude = 52.5, longitude = 13.4,
            current = com.vayunmathur.weather.network.Current(
                time = "2026-07-15T14:30", temperature = 21.5, apparentTemperature = 20.0,
                relativeHumidity = 60, windSpeed = 8.0, windDirection = 180,
                weatherCode = 3, isDay = 0,
            ),
        )
        repo.writeForecastCache(52.5, 13.4, forecast, fetchedAtEpochMs = 5000L)
        val snapshot = assertNotNull(loadWeatherSnapshot(ctx))
        assertEquals(21.5, snapshot.temperatureCelsius)
        assertEquals(3, snapshot.weatherCode)
        assertEquals(false, snapshot.isDay)
    }

    @Test fun widgetSnapshotFallsBackToFirstPinnedAndHandlesMisses() = runTest {
        // No locations at all -> no snapshot.
        assertNull(loadWeatherSnapshot(ctx))
        // A location without any cache -> no snapshot.
        repo.insertLocation(loc("Pinned"))
        assertNull(loadWeatherSnapshot(ctx))
        // Corrupt cache JSON -> no snapshot, not a crash.
        repo.upsertCache(
            WeatherCache(
                latRounded = roundCoord(52.5), lonRounded = roundCoord(13.4),
                forecastJson = "{broken", fetchedAtEpochMs = 1L,
            )
        )
        assertNull(loadWeatherSnapshot(ctx))
        // Valid forecast without a current block -> no snapshot.
        repo.writeForecastCache(52.5, 13.4, ForecastResponse(latitude = 52.5, longitude = 13.4), fetchedAtEpochMs = 2L)
        assertNull(loadWeatherSnapshot(ctx))
    }
}
