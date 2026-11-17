package com.vayunmathur.maps.data
import com.vayunmathur.maps.platform.ParkingViewModel

import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A remembered parking spot: where the user left
 * their car, when, and an optional note. Only what we need to drop a pin,
 * recall the spot, and route back to it.
 */
@Serializable
data class ParkingSpot(
    val lat: Double,
    val lon: Double,
    val timestamp: Long,
    val note: String? = null,
) {
    /** Turn the spot into a routable destination so "directions" reuses the
     *  existing routing/nav path. */
    fun toFeature(name: String): SpecificFeature.GenericPlace =
        SpecificFeature.GenericPlace(
            name = name,
            phone = null,
            website = null,
            openingHours = null,
            position = GeoPoint(lon, lat),
        )
}

/**
 * Persistence for parking memory (P9), mirroring [SavedPlaceStore]'s thin
 * DataStore pattern: a single **active** spot plus a small **history** of
 * previously-cleared spots. Each field is one JSON string in DataStore;
 * [com.vayunmathur.maps.platform.ParkingViewModel] owns the coroutine scope and
 * turns these flows into `StateFlow`s.
 */
class ParkingStore(private val ds: DataStoreUtils) {

    fun activeFlow(): Flow<ParkingSpot?> = ds.stringFlow(KEY_ACTIVE).map { decode(it) }
    fun activeInitial(): ParkingSpot? = decode(ds.getString(KEY_ACTIVE))

    suspend fun setActive(spot: ParkingSpot?) = ds.setString(KEY_ACTIVE, encode(spot))

    fun historyFlow(): Flow<List<ParkingSpot>> = ds.stringFlow(KEY_HISTORY).map { decodeList(it) }
    fun historyInitial(): List<ParkingSpot> = decodeList(ds.getString(KEY_HISTORY))

    suspend fun setHistory(list: List<ParkingSpot>) = ds.setString(KEY_HISTORY, Json.encodeToString(list))

    private fun encode(spot: ParkingSpot?): String =
        spot?.let { Json.encodeToString(it) } ?: ""

    private fun decode(raw: String?): ParkingSpot? =
        raw?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Json.decodeFromString<ParkingSpot>(it) }.getOrNull() }

    private fun decodeList(raw: String?): List<ParkingSpot> =
        raw?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Json.decodeFromString<List<ParkingSpot>>(it) }.getOrNull() }
            ?: emptyList()

    /** Shared constants and helpers for ParkingStore. */
    companion object {
        private const val KEY_ACTIVE = "parking_active_spot"
        private const val KEY_HISTORY = "parking_history"
    }
}
