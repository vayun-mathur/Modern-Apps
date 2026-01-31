package com.vayunmathur.youpipe.data

import androidx.room.Dao
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
data class SubscriptionVideo(
    val name: String,
    val videoID: Long,
    val views: Long,
    val uploadDate: Instant,
    val thumbnailURL: String,
    val author: String,
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    override val position: Double = 0.0
): DatabaseItem<SubscriptionVideo>() {
    override fun withPosition(position: Double) = copy(position = position)
}

@Dao
interface SubscriptionVideoDao: TrueDao<SubscriptionVideo> {
    @Query("SELECT * FROM SubscriptionVideo ORDER BY position")
    override fun getAll(): Flow<List<SubscriptionVideo>>
    @Query("DELETE FROM SubscriptionVideo")
    override suspend fun deleteAll()
}