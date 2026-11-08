package com.vayunmathur.weather.platform

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.glance.appwidget.updateAll
import com.vayunmathur.weather.R
import com.vayunmathur.weather.data.SavedLocation
import com.vayunmathur.weather.data.WeatherRepository
import com.vayunmathur.weather.data.WeatherRefreshWorker
import com.vayunmathur.weather.data.weatherJson
import com.vayunmathur.weather.domain.SelectedDateOrTime
import com.vayunmathur.weather.domain.roundCoord
import com.vayunmathur.weather.widget.glance.WeatherGlanceWidget
import com.vayunmathur.weather.network.AirQualityResponse
import com.vayunmathur.weather.network.ForecastResponse
import com.vayunmathur.weather.network.WeatherApi
import com.vayunmathur.weather.network.WxBundle
import com.vayunmathur.weather.network.toAirQuality
import com.vayunmathur.weather.network.toForecastResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

data class ForecastUiState(
    val forecast: ForecastResponse? = null,
    val airQuality: AirQualityResponse? = null,
    val refreshing: Boolean = false,
    val error: String? = null,
    val fetchedAtEpochMs: Long = 0,
)


class WeatherViewModel(
    application: Application,
    private val repository: WeatherRepository,
    // Test seam: the bundle fetch, so unit tests can force the offline path
    // instead of depending on the ambient network being unreachable (on a
    // box with internet the old tests' "network failure" never happened and
    // live data flowed into assertions). Production uses WeatherApi.
    private val bundleFetcher: suspend (Double, Double) -> WxBundle = { lat, lon ->
        WeatherApi.bundle(lat, lon)
    },
) : AndroidViewModel(application), WeatherActions {

    val savedLocations: StateFlow<List<SavedLocation>?> = repository.locations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _forecasts = MutableStateFlow<Map<Long, ForecastUiState>>(emptyMap())
    val forecasts: StateFlow<Map<Long, ForecastUiState>> = _forecasts.asStateFlow()

    private val inFlight = mutableSetOf<Long>()

    // Serializes addLocation's check-then-insert so two rapid taps on the same
    // search result can't both slip past the duplicate check.
    private val addMutex = Mutex()

    private val _selected = MutableStateFlow<SelectedDateOrTime?>(null)
    val selectedDateOrTime: StateFlow<SelectedDateOrTime?> = _selected.asStateFlow()

    fun selectTime(isoTime: String) { _selected.value = SelectedDateOrTime.Time(isoTime) }

    fun selectDay(isoDate: String) { _selected.value = SelectedDateOrTime.Day(isoDate) }

    override fun clearSelection() { _selected.value = null }

    override fun toggleTime(isoTime: String) {
        val current = _selected.value
        _selected.value = if (current is SelectedDateOrTime.Time && current.isoTime == isoTime) {
            null
        } else {
            SelectedDateOrTime.Time(isoTime)
        }
    }
    override fun toggleDay(isoDate: String) {
        val current = _selected.value
        _selected.value = if (current is SelectedDateOrTime.Day && current.isoDate == isoDate) {
            null
        } else {
            SelectedDateOrTime.Day(isoDate)
        }
    }

    init {
        WeatherRefreshWorker.scheduleHourlyRefresh(application)
    }

    fun ensureForecast(location: SavedLocation, force: Boolean = false) {
        synchronized(inFlight) {
            if (location.id in inFlight) return
            val existing = _forecasts.value[location.id]
            val memStale = existing?.forecast == null ||
                (System.currentTimeMillis() - existing.fetchedAtEpochMs) >= STALE_THRESHOLD_MS
            if (!force && !memStale) return
            inFlight.add(location.id)
        }

        viewModelScope.launch {
            try {
                val haveFreshCache = hydrateFromCache(location)
                if (!force && haveFreshCache) return@launch
                fetchFresh(location)
            } finally {
                synchronized(inFlight) { inFlight.remove(location.id) }
            }
        }
    }

    private suspend fun hydrateFromCache(location: SavedLocation): Boolean {
        if (_forecasts.value[location.id]?.forecast != null) return false
        val cache = repository.getCache(
            roundCoord(location.latitude),
            roundCoord(location.longitude),
        ) ?: return false
        var fresh = false
        runCatching { weatherJson.decodeFromString<ForecastResponse>(cache.forecastJson) }
            .onSuccess { decoded ->
                val cachedAir = cache.airQualityJson?.let { json ->
                    runCatching {
                        weatherJson.decodeFromString<AirQualityResponse>(json)
                    }.getOrNull()
                }
                _forecasts.update { current ->
                    current + (location.id to ForecastUiState(
                        forecast = decoded,
                        airQuality = cachedAir,
                        refreshing = false,
                        fetchedAtEpochMs = cache.fetchedAtEpochMs,
                    ))
                }
                fresh = (System.currentTimeMillis() - cache.fetchedAtEpochMs) < STALE_THRESHOLD_MS
            }
        return fresh
    }

    private data class FetchResult(
        val forecast: kotlin.Result<ForecastResponse>,
        val air: AirQualityResponse?,
    )

    private suspend fun fetchFresh(location: SavedLocation) {
        val target = if (location.isCurrent) {
            refreshDeviceLocationFix(location)
        } else {
            location
        }
        _forecasts.update { current ->
            val prev = current[location.id]
            val refreshing = prev?.copy(refreshing = true) ?: ForecastUiState(refreshing = true)
            current + (location.id to refreshing)
        }
        // One bundle call carries forecast + AQ (same object, zero extra I/O).
        val bundle = runCatching { bundleFetcher(target.latitude, target.longitude) }
        val fetched = FetchResult(
            forecast = bundle.map { it.toForecastResponse() },
            air = bundle.map { it.toAirQuality() }.getOrNull(),
        )
        applyFetched(location, target, fetched)
    }

    private suspend fun applyFetched(
        location: SavedLocation,
        target: SavedLocation,
        fetched: FetchResult,
    ) {
        val airQuality = fetched.air
        fetched.forecast
            .onSuccess { fresh ->
                val now = System.currentTimeMillis()
                val resolvedAir = airQuality ?: _forecasts.value[location.id]?.airQuality
                repository.writeForecastCache(
                    target.latitude,
                    target.longitude,
                    fresh,
                    resolvedAir,
                    now,
                )
                _forecasts.update { current ->
                    current + (location.id to ForecastUiState(
                        forecast = fresh,
                        airQuality = resolvedAir,
                        refreshing = false,
                        error = null,
                        fetchedAtEpochMs = now,
                    ))
                }
                runCatching { WeatherGlanceWidget().updateAll(getApplication<Application>()) }
            }
            .onFailure { e ->
                _forecasts.update { current ->
                    val prev = current[location.id]
                    current + (location.id to (prev?.copy(
                        airQuality = airQuality ?: prev.airQuality,
                        refreshing = false,
                        error = e.message ?: "Failed to load forecast",
                    ) ?: ForecastUiState(
                        airQuality = airQuality,
                        refreshing = false,
                        error = e.message,
                    )))
                }
            }
    }

    override fun refreshAll(force: Boolean) {
        for (location in savedLocations.value.orEmpty()) {
            ensureForecast(location, force)
        }
    }

    override fun deleteLocation(location: SavedLocation) {
        viewModelScope.launch {
            repository.deleteLocation(location)
            _forecasts.update { it - location.id }
        }
    }

    override fun reorderLocations(ordered: List<SavedLocation>) {
        viewModelScope.launch {
            ordered.forEachIndexed { index, loc ->
                if (loc.displayOrder != index) repository.setOrder(loc.id, index)
            }
        }
    }

    fun addLocation(name: String, country: String, latitude: Double, longitude: Double) {
        viewModelScope.launch {
            addMutex.withLock {
                val existing = repository.getLocations()
                // The same city tapped twice (or re-added after a new search)
                // arrives with bit-identical coordinates, so exact equality
                // only ever matches true duplicates — never two distinct
                // nearby places. The live-GPS row is excluded: pinning the
                // city you're standing in is deliberate, not a duplicate.
                if (existing.any { !it.isCurrent && it.latitude == latitude && it.longitude == longitude }) {
                    return@withLock
                }
                repository.insertLocation(
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
    }

    fun setCurrentLocation(name: String, latitude: Double, longitude: Double) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val defaultName = app.getString(R.string.current_location)
            val resolved = reverseGeocodeName(app, latitude, longitude)
            repository.replaceCurrentDeviceLocation(
                SavedLocation(
                    name = resolved?.first ?: name.ifBlank { defaultName },
                    country = resolved?.second.orEmpty(),
                    latitude = latitude,
                    longitude = longitude,
                    displayOrder = -1,
                    isCurrent = true,
                )
            )
        }
    }

    private suspend fun refreshDeviceLocationFix(location: SavedLocation): SavedLocation {
        val context = getApplication<Application>()
        if (!LocationProvider.hasPermission(context)) return location
        val fix = withTimeoutOrNull(LOCATION_FIX_TIMEOUT_MS) {
            LocationProvider.currentLocation(context)
        } ?: return location
        val distance = FloatArray(1)
        android.location.Location.distanceBetween(
            location.latitude,
            location.longitude,
            fix.latitude,
            fix.longitude,
            distance,
        )
        if (distance[0] < MIN_LOCATION_MOVE_METERS) return location
        val resolved = reverseGeocodeName(context, fix.latitude, fix.longitude)
        if (resolved != null) repository.updateName(location.id, resolved.first, resolved.second)
        repository.updateCoordinates(location.id, fix.latitude, fix.longitude)
        return location.copy(
            name = resolved?.first ?: location.name,
            country = resolved?.second ?: location.country,
            latitude = fix.latitude,
            longitude = fix.longitude,
        )
    }

    companion object {
        const val STALE_THRESHOLD_MS = 15 * 60 * 1000L
        private const val LOCATION_FIX_TIMEOUT_MS = 8_000L
        private const val MIN_LOCATION_MOVE_METERS = 500f
    }
}

class WeatherViewModelFactory(
    private val application: Application,
    private val repository: WeatherRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return WeatherViewModel(application, repository) as T
    }
}
