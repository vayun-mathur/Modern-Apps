package com.vayunmathur.weather.data

import android.content.Context
import com.vayunmathur.library.log.Log
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vayunmathur.weather.widget.glance.WeatherBlobGlanceWidget
import com.vayunmathur.weather.widget.glance.WeatherGlanceWidget
import com.vayunmathur.weather.network.WeatherApi
import com.vayunmathur.weather.network.WxStatic
import com.vayunmathur.weather.network.toAirQuality
import com.vayunmathur.weather.network.toForecastResponse
import java.util.concurrent.TimeUnit

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
            WeatherGlanceWidget().updateAll(context)
            WeatherBlobGlanceWidget().updateAll(context)
            Result.success()
        } catch (e: Exception) {
            Log.error(TAG, "Weather refresh failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "WeatherRefresh"
        private const val WORK_NAME = "WeatherHourlyRefresh"

        fun scheduleHourlyRefresh(context: Context) {
            val request = PeriodicWorkRequestBuilder<WeatherRefreshWorker>(1, TimeUnit.HOURS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
