package com.vayunmathur.weather.data

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/** Contract for weather dao. */
@Dao
interface WeatherDao {

    // ---- SavedLocation ----

    @Query("SELECT * FROM SavedLocation ORDER BY displayOrder ASC, id ASC")
    fun observeLocations(): Flow<List<SavedLocation>>

    @Query("SELECT * FROM SavedLocation ORDER BY displayOrder ASC, id ASC")
    suspend fun getLocations(): List<SavedLocation>

    @Query("SELECT * FROM SavedLocation WHERE isCurrent = 1 LIMIT 1")
    suspend fun getCurrentDeviceLocation(): SavedLocation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocation(location: SavedLocation): Long

    @Delete
    suspend fun deleteLocation(location: SavedLocation)

    @Query("UPDATE SavedLocation SET displayOrder = :order WHERE id = :id")
    suspend fun setOrder(id: Long, order: Int)

    /** Updates a saved row's coordinates in place, preserving its id (unlike [replaceCurrentDeviceLocation]). */
    @Query("UPDATE SavedLocation SET latitude = :lat, longitude = :lon WHERE id = :id")
    suspend fun updateCoordinates(id: Long, lat: Double, lon: Double)

    /** Updates a saved row's display name in place, e.g. after reverse-geocoding moves. */
    @Query("UPDATE SavedLocation SET name = :name, country = :country WHERE id = :id")
    suspend fun updateName(id: Long, name: String, country: String)

    /**
     * Replaces the existing "current device" row (if any) with [newRow] in a
     * single transaction. Used by the location provider when it gets a fresh
     * GPS fix.
     */
    @androidx.room3.Transaction
    suspend fun replaceCurrentDeviceLocation(newRow: SavedLocation) {
        getCurrentDeviceLocation()?.let { deleteLocation(it) }
        insertLocation(newRow.copy(isCurrent = true))
    }

    // ---- WeatherCache ----

    @Upsert
    suspend fun upsertCache(cache: WeatherCache)

    @Query("SELECT * FROM WeatherCache WHERE latRounded = :lat AND lonRounded = :lon LIMIT 1")
    suspend fun getCache(lat: Double, lon: Double): WeatherCache?
}
