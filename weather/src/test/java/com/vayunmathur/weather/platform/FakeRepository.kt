package com.vayunmathur.weather.platform

import android.content.Context
import com.vayunmathur.weather.data.SavedLocation
import com.vayunmathur.weather.data.WeatherCache
import com.vayunmathur.weather.data.WeatherRepository
import com.vayunmathur.weather.network.AirQualityResponse
import com.vayunmathur.weather.network.ForecastResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [WeatherRepository] fake: no Room/SQLCipher, records writes. */
class FakeRepository(context: Context) : WeatherRepository(context) {
    val rows = MutableStateFlow<List<SavedLocation>>(emptyList())
    val caches = mutableMapOf<Pair<Double, Double>, WeatherCache>()
    var nextId = 100L

    override val locations: Flow<List<SavedLocation>> get() = rows
    override suspend fun getLocations(): List<SavedLocation> = rows.value
    override suspend fun getCurrentDeviceLocation(): SavedLocation? = rows.value.firstOrNull { it.isCurrent }
    override suspend fun insertLocation(location: SavedLocation): Long {
        val id = nextId++
        rows.value = rows.value + location.copy(id = id)
        return id
    }
    override suspend fun deleteLocation(location: SavedLocation) {
        rows.value = rows.value.filterNot { it.id == location.id }
    }
    override suspend fun setOrder(id: Long, order: Int) {
        rows.value = rows.value.map { if (it.id == id) it.copy(displayOrder = order) else it }
    }
    override suspend fun updateCoordinates(id: Long, lat: Double, lon: Double) {
        rows.value = rows.value.map { if (it.id == id) it.copy(latitude = lat, longitude = lon) else it }
    }
    override suspend fun updateName(id: Long, name: String, country: String) {
        rows.value = rows.value.map { if (it.id == id) it.copy(name = name, country = country) else it }
    }
    override suspend fun replaceCurrentDeviceLocation(newRow: SavedLocation) {
        rows.value = rows.value.filterNot { it.isCurrent } + newRow.copy(isCurrent = true)
    }
    override suspend fun getCache(lat: Double, lon: Double): WeatherCache? = caches[lat to lon]
    override suspend fun upsertCache(cache: WeatherCache) {
        caches[cache.latRounded to cache.lonRounded] = cache
    }
    override suspend fun writeForecastCache(
        latitude: Double, longitude: Double, forecast: ForecastResponse,
        airQuality: AirQualityResponse?, fetchedAtEpochMs: Long,
    ) {
        caches[latitude to longitude] = WeatherCache(latitude, longitude, "{}", null, fetchedAtEpochMs)
    }
}

