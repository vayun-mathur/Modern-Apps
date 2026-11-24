package com.vayunmathur.weather.data

import android.content.Context
import com.vayunmathur.library.log.Log
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vayunmathur.weather.widget.WeatherBlobGlanceWidget
import com.vayunmathur.weather.widget.WeatherGlanceWidget
import com.vayunmathur.weather.network.WeatherApi
import com.vayunmathur.weather.network.WxStatic
import com.vayunmathur.weather.network.toAirQuality
import com.vayunmathur.weather.network.toForecastResponse
import java.util.concurrent.TimeUnit

/** Background worker performing weather refresh. */
class WeatherRefreshWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    // Broad catches are deliberate: a worker must return Result, never throw, and
    // network plus Room throw undocumented RuntimeExceptions (not just IOException).
    @Suppress("TooGenericExceptionCaught")
    override suspend fun doWork(): Result {
        return try {
            val repo = WeatherRepository.get(context)
            WxStatic.init(context)
            val locations = repo.getLocations()
            for (location in locations) {
                try {
                    // One bundle call carries forecast + AQ (same object,
                    // split into the two cache halves below).
                    val bundle = WeatherApi.bundle(location.latitude, location.longitude)
                    val forecast = bundle.toForecastResponse()
                    val airQuality = bundle.toAirQuality()
                    repo.writeForecastCache(location.latitude, location.longitude, forecast, airQuality)
                } catch (e: Exception) {
                    Log.status(TAG, "Failed to refresh weather for ${location.name}: ${e.message}")
                }
            }
            com.vayunmathur.weather.sync.WearWeatherSync.get(context).onForecastRefreshed()
            WeatherGlanceWidget().updateAll(context)
            WeatherBlobGlanceWidget().updateAll(context)
            Result.success()
        } catch (e: Exception) {
            Log.error(TAG, "Weather refresh failed", e)
            Result.retry()
        }
    }

    /** Shared constants and helpers for WeatherRefreshWorker. */
    companion object {
        private const val TAG = "WeatherRefresh"
        private const val WORK_NAME = "WeatherHourlyRefresh"

        fun scheduleHourlyRefresh(context: Context) {
            // The worker only fetches small forecast bundles, so CONNECTED
            // (metered allowed) matches the other hourly network syncs in
            // the repo (e.g. email); UNMETERED/battery constraints are
            // reserved for large downloads (appstore, maps). Without a
            // network constraint the hourly job woke up and burned a retry
            // cycle even with no connectivity at all.
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<WeatherRefreshWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                // UPDATE (not KEEP) so installs that already enqueued the
                // old unconstrained request actually pick up the constraints.
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
