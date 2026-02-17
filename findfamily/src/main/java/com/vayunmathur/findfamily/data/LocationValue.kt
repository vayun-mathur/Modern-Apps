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
import kotlin.time.Instant

@Serializable
@Entity
data class LocationValue(
    val userid: Long,
    @Embedded val coord: Coord,
    val speed: Float,
    val acc: Float,
    val timestamp: Instant,
    val battery: Float,
    @PrimaryKey(autoGenerate = true) override val id: Long = 0
): DatabaseItem

@Dao
interface LocationValueDao: TrueDao<LocationValue> {
    @Query("SELECT * FROM locationvalue")
    override fun getAll(): Flow<List<LocationValue>>

    @Query("DELETE FROM locationvalue")
    override suspend fun deleteAll()
}