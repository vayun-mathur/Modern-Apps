package com.vayunmathur.findfamily.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import com.vayunmathur.library.util.DatabaseItem
import com.vayunmathur.library.util.TrueDao
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
@Entity
data class Waypoint(
    val name: String,
    val range: Double,
    @Embedded val coord: Coord,
    @PrimaryKey(autoGenerate = true) override val id: Long = 0
): DatabaseItem {
    companion object {
        val NEW_WAYPOINT = Waypoint("", 100.0, Coord(0.0, 0.0))
    }
}

@Dao
interface WaypointDao: TrueDao<Waypoint> {
    @Query("SELECT * FROM waypoint")
    override fun getAll(): Flow<List<Waypoint>>

    @Query("DELETE FROM waypoint")
    override suspend fun deleteAll()
}