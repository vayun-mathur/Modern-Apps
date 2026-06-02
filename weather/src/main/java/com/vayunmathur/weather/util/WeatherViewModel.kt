package com.vayunmathur.weather.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.weather.data.SavedLocation
import com.vayunmathur.weather.data.WeatherCache
import com.vayunmathur.weather.data.WeatherDao
import com.vayunmathur.weather.data.WeatherRefreshWorker
import com.vayunmathur.weather.network.AirQualityResponse
import com.vayunmathur.weather.network.ForecastResponse
import com.vayunmathur.weather.network.WeatherApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.math.round

/** Per-location forecast state held in [WeatherViewModel.forecasts]. */
data class ForecastUiState(
    val forecast: ForecastResponse? = null,
    val airQuality: AirQualityResponse? = null,
    val refreshing: Boolean = false,
    val error: String? = null,
    val fetchedAtEpochMs: Long = 0,
)

/**
 * Holds saved locations, per-location forecast state, and the user's unit
 * prefs. Mirrors the manual-Factory pattern used everywhere else in this
 * repo (see [com.vayunmathur.passwords.util.PasswordsViewModel]).
 */
class WeatherViewModel(
    application: Application,
    private val dao: WeatherDao,
) : AndroidViewModel(application) {

    private val json = Json { ignoreUnknownKeys = true }

    val savedLocations: StateFlow<List<SavedLocation>> = dao.observeLocations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Per-location forecast state, keyed by [SavedLocation.id]. */
    private val _forecasts = MutableStateFlow<Map<Long, ForecastUiState>>(emptyMap())
    val forecasts: StateFlow<Map<Long, ForecastUiState>> = _forecasts.asStateFlow()

    init {
        WeatherRefreshWorker.scheduleHourlyRefresh(application)
    }

    /**
     * Ensure there's a forecast for [location] — hydrate from the on-disk
     * cache first so the UI gets something immediately, then kick off a
     * background refresh. Repeated calls while a refresh is in flight are
     * no-ops.
     */
    fun ensureForecast(location: SavedLocation) {
        val existing = _forecasts.value[location.id]
        if (existing != null && existing.refreshing) return

        viewModelScope.launch {
            // 1. Hydrate from cache if we don't have anything in memory yet.
            if (existing?.forecast == null) {
                val cache = dao.getCache(roundCoord(location.latitude), roundCoord(location.longitude))
                if (cache != null) {
                    runCatching { json.decodeFromString<ForecastResponse>(cache.forecastJson) }
                        .onSuccess { decoded ->
                            _forecasts.update { current ->
                                current + (location.id to ForecastUiState(
                                    forecast = decoded,
                                    refreshing = true,
                                    fetchedAtEpochMs = cache.fetchedAtEpochMs,
                                ))
                            }
                        }
                } else {
                    _forecasts.update { it + (location.id to ForecastUiState(refreshing = true)) }
                }
            } else {
                _forecasts.update { current ->
                    current + (location.id to existing.copy(refreshing = true))
                }
            }

            // 2. Network refresh — forecast + air quality fetched in parallel.
            data class FetchResult(val forecast: kotlin.Result<ForecastResponse>, val air: AirQualityResponse?)
            val fetched: FetchResult = coroutineScope {
                val forecastDeferred = async {
                    runCatching { WeatherApi.forecast(location.latitude, location.longitude) }
                }
                val airQualityDeferred = async {
                    runCatching { WeatherApi.airQuality(location.latitude, location.longitude) }.getOrNull()
                }
                FetchResult(forecastDeferred.await(), airQualityDeferred.await())
            }
            val forecastResult = fetched.forecast
            val airQuality = fetched.air

            forecastResult
                .onSuccess { fresh ->
                    val now = System.currentTimeMillis()
                    dao.upsertCache(
                        WeatherCache(
                            latRounded = roundCoord(location.latitude),
                            lonRounded = roundCoord(location.longitude),
                            forecastJson = json.encodeToString(fresh),
                            fetchedAtEpochMs = now,
                        )
                    )
                    _forecasts.update { current ->
                        current + (location.id to ForecastUiState(
                            forecast = fresh,
                            airQuality = airQuality,
                            refreshing = false,
                            error = null,
                            fetchedAtEpochMs = now,
                        ))
                    }
                }
                .onFailure { e ->
                    _forecasts.update { current ->
                        val prev = current[location.id]
                        current + (location.id to (prev?.copy(
                            refreshing = false,
                            error = e.message ?: "Failed to load forecast",
                        ) ?: ForecastUiState(refreshing = false, error = e.message)))
                    }
                }
        }
    }

    fun deleteLocation(location: SavedLocation) {
        viewModelScope.launch {
            dao.deleteLocation(location)
            _forecasts.update { it - location.id }
        }
    }

    /**
     * Insert a manually-picked location at the end of the list. The current
     * row count drives [SavedLocation.displayOrder] so the new pin lands last.
     */
    fun addLocation(name: String, country: String, latitude: Double, longitude: Double) {
        viewModelScope.launch {
            val existing = dao.getLocations()
            dao.insertLocation(
                SavedLocation(
                    name = name,
                    country = country,
                    latitude = latitude,
                    longitude = longitude,
                    displayOrder = existing.size,
                    isCurrent = false,
                )
            )
        }
    }

    /** Replace (or insert) the single "current device" row with a fresh fix. */
    fun setCurrentLocation(name: String, latitude: Double, longitude: Double) {
        viewModelScope.launch {
            dao.replaceCurrentDeviceLocation(
                SavedLocation(
                    name = name.ifBlank { "Current location" },
                    country = "",
                    latitude = latitude,
                    longitude = longitude,
                    displayOrder = -1, // current row sorts first
                    isCurrent = true,
                )
            )
        }
    }

    private fun roundCoord(value: Double): Double = round(value * 10000.0) / 10000.0
}

class WeatherViewModelFactory(
    private val application: Application,
    private val dao: WeatherDao,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return WeatherViewModel(application, dao) as T
    }
}
