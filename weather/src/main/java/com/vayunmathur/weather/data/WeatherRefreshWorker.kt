package com.vayunmathur.weather.data

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vayunmathur.library.room.buildDatabase
import com.vayunmathur.weather.glance.WeatherGlanceWidget
import com.vayunmathur.weather.network.WeatherApi
import java.util.concurrent.TimeUnit

class WeatherRefreshWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val db = context.buildDatabase<WeatherDatabase>(dbName = "weather-db")
            val dao = db.weatherDao()
            val locations = dao.getLocations()

            for (location in locations) {
                try {
                    val forecast = WeatherApi.forecast(location.latitude, location.longitude)
                    val airQuality = runCatching {
                        WeatherApi.airQuality(location.latitude, location.longitude)
                    }.getOrNull()
                    dao.writeForecastCache(location.latitude, location.longitude, forecast, airQuality)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to refresh weather for ${location.name}: ${e.message}")
                }
            }

            WeatherGlanceWidget().updateAll(context)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Weather refresh failed", e)
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
