package com.vayunmathur.weather.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository
import com.vayunmathur.weather.network.AirQualityResponse
import com.vayunmathur.weather.network.ForecastResponse
import kotlinx.coroutines.flow.Flow

open class WeatherRepository internal constructor(context: Context) :
    RoomRepository<WeatherDatabase>(context, WeatherDatabase::class, DB_NAME) {

    private val dao get() = db.weatherDao()

    // Flows
    open val locations: Flow<List<SavedLocation>> get() = dao.observeLocations()

    // SavedLocation
    open suspend fun getLocations(): List<SavedLocation> = dao.getLocations()
    open suspend fun getCurrentDeviceLocation(): SavedLocation? = dao.getCurrentDeviceLocation()
    open suspend fun insertLocation(location: SavedLocation): Long = dao.insertLocation(location)
    open suspend fun deleteLocation(location: SavedLocation) = dao.deleteLocation(location)
    open suspend fun setOrder(id: Long, order: Int) = dao.setOrder(id, order)
    open suspend fun updateCoordinates(id: Long, lat: Double, lon: Double) = dao.updateCoordinates(id, lat, lon)
    open suspend fun updateName(id: Long, name: String, country: String) = dao.updateName(id, name, country)
    open suspend fun replaceCurrentDeviceLocation(newRow: SavedLocation) = dao.replaceCurrentDeviceLocation(newRow)

    // Cache
    open suspend fun getCache(lat: Double, lon: Double): WeatherCache? = dao.getCache(lat, lon)
    open suspend fun upsertCache(cache: WeatherCache) = dao.upsertCache(cache)
    open suspend fun writeForecastCache(
        latitude: Double,
        longitude: Double,
        forecast: ForecastResponse,
        airQuality: AirQualityResponse? = null,
        fetchedAtEpochMs: Long = System.currentTimeMillis(),
    ) = dao.writeForecastCache(latitude, longitude, forecast, airQuality, fetchedAtEpochMs)

    companion object {
        @Volatile private var instance: WeatherRepository? = null
        fun get(context: Context): WeatherRepository =
            instance ?: synchronized(this) {
                instance ?: WeatherRepository(context).also { instance = it }
            }
    }
}
