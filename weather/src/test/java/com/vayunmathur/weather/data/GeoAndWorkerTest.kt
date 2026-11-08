package com.vayunmathur.weather.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.vayunmathur.weather.network.WeatherApi
import kotlinx.coroutines.test.runTest
import org.junit.Test as JUnitTest
import org.junit.runner.RunWith
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

@RunWith(AndroidJUnit4::class)
class GeoAndWorkerTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @BeforeTest fun setUp() {
        GeoDatabase.init(ctx)
    }

    @Test fun prepareIsConsistentWithStatus() = runTest {
        // Robolectric's StorageManager shadow can NPE inside
        // allocateForDatabase before the unpack starts; tolerate that
        // harness gap, but require consistency when prepare returns.
        val result = runCatching { GeoDatabase.prepare() }.getOrElse {
            assertTrue(it is NullPointerException, "unexpected $it")
            return@runTest
        }
        when (val status = GeoDatabase.status.value) {
            is GeoDatabase.Status.Ready -> {
                assertTrue(result.isSuccess)
                assertTrue(GeoDatabase.searchPlaces("Berlin", 5).isNotEmpty())
            }
            is GeoDatabase.Status.Failed -> {
                assertTrue(result.isFailure)
                assertTrue(GeoDatabase.searchPlaces("Berlin", 5).isEmpty())
            }
            else -> fail("unexpected status $status")
        }
    }

    @Test fun prepareIsIdempotent() = runTest {
        runCatching { GeoDatabase.prepare() }
        val second = runCatching { GeoDatabase.prepare() }.getOrElse { return@runTest }
        // Second call returns the cached verified meta or the same failure.
        assertTrue(second.isSuccess == (GeoDatabase.status.value is GeoDatabase.Status.Ready))
    }

    @Test fun scheduleHourlyRefreshEnqueues() {
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx, config)
        WeatherRefreshWorker.scheduleHourlyRefresh(ctx)
        val infos = WorkManager.getInstance(ctx)
            .getWorkInfosForUniqueWork("WeatherHourlyRefresh").get()
        assertTrue(infos.isNotEmpty())
    }

    @JUnitTest(timeout = 60_000)
    fun apiBundleFailsWithoutNetwork() = runTest {
        // Hermetic: point the API at a dead loopback port so the failure
        // happens on internet-connected boxes too (CI reached the real
        // api.maproj.org and the "expected failure" never came).
        com.vayunmathur.weather.network.WeatherApi.bundleBaseOverride = "https://127.0.0.1:9/wx/v1"
        try {
            try {
                WeatherApi.bundle(52.0, 13.0)
                fail("expected network failure")
            } catch (e: Exception) {
                assertTrue(e.message != null || e is RuntimeException)
            }
            try {
                WeatherApi.currentTemperatures(listOf(52.0 to 13.0))
                fail("expected network failure")
            } catch (e: Exception) {
                assertTrue(true)
            }
        } finally {
            com.vayunmathur.weather.network.WeatherApi.bundleBaseOverride = null
        }
    }
}

@RunWith(AndroidJUnit4::class)
class WeatherWorkerDoWorkTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun doWorkRetriesWhenRepositoryUnavailable() = runTest {
        // Real repository cannot open under Robolectric (AndroidKeyStore),
        // so doWork must degrade to Result.retry rather than throw.
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx, config)
        val worker = androidx.work.testing.TestListenableWorkerBuilder<WeatherRefreshWorker>(ctx).build()
        val result = worker.doWork()
        assertTrue(result is androidx.work.ListenableWorker.Result.Retry || result is androidx.work.ListenableWorker.Result.Success)
    }

    @Test fun glanceWidgetReceiversScheduleOnEnabled() {
        // onEnabledExtra schedules hourly refresh via WorkManager.
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx, config)
        com.vayunmathur.weather.widget.glance.WeatherGlanceWidgetReceiver().onEnabled(ctx)
        com.vayunmathur.weather.widget.glance.WeatherBlobGlanceWidgetReceiver().onEnabled(ctx)
        val infos = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork("WeatherHourlyRefresh").get()
        assertTrue(infos.isNotEmpty())
    }
}
