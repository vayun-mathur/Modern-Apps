package com.vayunmathur.youpipe.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import com.vayunmathur.library.util.DatabaseItem
import com.vayunmathur.library.util.TrueDao
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
@Entity(foreignKeys = [
    ForeignKey(entity = Subscription::class, parentColumns = ["id"], childColumns = ["channelID"], onDelete = ForeignKey.CASCADE)
])
data class SubscriptionVideo(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0, // video id
    val name: String,
    val duration: Long,
    val views: Long,
    val uploadDate: Instant,
    val thumbnailURL: String,
    val author: String,
    @ColumnInfo(index = true)
    val channelID: Long
): DatabaseItem

@Dao
interface SubscriptionVideoDao: TrueDao<SubscriptionVideo> {
    @Query("SELECT * FROM SubscriptionVideo ORDER BY position")
    override fun getAll(): Flow<List<SubscriptionVideo>>
    @Query("DELETE FROM SubscriptionVideo")
    override suspend fun deleteAll()
}