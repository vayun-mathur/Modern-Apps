package com.vayunmathur.weather.data

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.weather.glance.WeatherGlanceWidget
import com.vayunmathur.weather.network.WeatherApi
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import kotlin.math.round

class WeatherRefreshWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun doWork(): Result {
        return try {
            val db = context.buildDatabase<WeatherDatabase>(dbName = "weather-db")
            val dao = db.weatherDao()
            val locations = dao.getLocations()

            for (location in locations) {
                try {
                    val forecast = WeatherApi.forecast(location.latitude, location.longitude)
                    dao.upsertCache(
                        WeatherCache(
                            latRounded = roundCoord(location.latitude),
                            lonRounded = roundCoord(location.longitude),
                            forecastJson = json.encodeToString(forecast),
                            fetchedAtEpochMs = System.currentTimeMillis(),
                        )
                    )
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

    private fun roundCoord(value: Double): Double = round(value * 10000.0) / 10000.0

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
